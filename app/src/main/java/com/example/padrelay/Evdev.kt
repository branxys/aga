package com.example.padrelay

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

data class AbsInfo(val min: Int, val max: Int, val value: Int = 0) // value = resting value when scanned

data class PadDevice(
    val path: String,
    val name: String,
    val abs: Map<Int, AbsInfo>, // evdev ABS code -> range
    val isGamepad: Boolean
)

/** Reads the kernel's raw input devices as root (works with the screen off). */
object Evdev {
    const val EV_SYN = 0
    const val EV_KEY = 1
    const val EV_ABS = 3

    private val ABS_CODES = mapOf(
        "ABS_X" to 0, "ABS_Y" to 1, "ABS_Z" to 2,
        "ABS_RX" to 3, "ABS_RY" to 4, "ABS_RZ" to 5,
        "ABS_GAS" to 9, "ABS_BRAKE" to 10,
        "ABS_HAT0X" to 16, "ABS_HAT0Y" to 17
    )

    // Key names that only gamepads/joysticks report (mice and touchscreens don't)
    private val GAMEPAD_KEYS = setOf(
        "BTN_GAMEPAD", "BTN_SOUTH", "BTN_A", "BTN_EAST", "BTN_B", "BTN_NORTH", "BTN_X",
        "BTN_WEST", "BTN_Y", "BTN_TL", "BTN_TR", "BTN_JOYSTICK", "BTN_TRIGGER"
    )

    private val sectionRe = Regex("""^\s*([A-Z]+)\s+\([0-9a-fA-F]+\):""")
    private val absRe = Regex("""(ABS_\w+)\s*:\s*value\s+(-?\d+),\s*min\s+(-?\d+),\s*max\s+(-?\d+)""")

    private val SU_CANDIDATES = listOf(
        "su", "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/data/adb/magisk/su",
        "/product/bin/su", "/vendor/bin/su"
    )

    // Controllers usually have one of these words in their name
    private val NAME_RE = Regex("(?i)controller|gamepad|joystick|joypad|xbox|dualsense|dualshock|8bitdo|gamesir|razer|stadia|joy-con|nintendo|playstation|wireless pad")

    // Extra devices that ship inside a controller (PlayStation pads expose a touchpad and motion sensors)
    private val COMPANION_RE = Regex("""(?i)\b(touchpad|motion sensors?|gyro(scope)?|accelerometer|imu)\b""")
    fun isCompanion(name: String) = COMPANION_RE.containsMatchIn(name)

    /** The su binary that worked last time (null until checkRoot succeeds). */
    @Volatile var suPath: String? = null
    fun suBin(): String = suPath ?: "su"

    class RootCheck(val ok: Boolean, val message: String)

    /** Runs `su -c id`. This is what makes the root manager show its permission prompt. */
    fun checkRoot(): RootCheck {
        var executed = false
        var lastOut = ""
        for (c in SU_CANDIDATES) {
            try {
                val p = ProcessBuilder(c, "-c", "id").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                executed = true
                lastOut = out.trim()
                if (out.contains("uid=0")) {
                    suPath = c
                    return RootCheck(true, "Root OK")
                }
                break // su exists but refused: no point trying other paths
            } catch (_: IOException) {
                // this path doesn't exist, try the next one
            }
        }
        return if (executed) {
            RootCheck(
                false,
                "Root was denied or timed out. Open your root manager's Superuser list, allow PadRelay, " +
                    "then try again." + if (lastOut.isNotEmpty()) "\n($lastOut)" else ""
            )
        } else {
            RootCheck(false, "No 'su' binary found. PadRelay can't see root on this phone (is it rooted, and is root hidden from the app?).")
        }
    }

    fun runSu(cmd: String): String = try {
        val p = ProcessBuilder(suBin(), "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: IOException) {
        ""
    }

    /** Lists all input devices (needs root). Parses `getevent -lp`. */
    fun scan(): List<PadDevice> {
        val devices = mutableListOf<PadDevice>()
        var path: String? = null
        var name = ""
        var abs = mutableMapOf<Int, AbsInfo>()
        var hasPadKey = false
        var section = ""

        fun flush() {
            val p = path ?: return
            val hasXY = abs.containsKey(0) && abs.containsKey(1)
            val isPad = hasXY && (hasPadKey || NAME_RE.containsMatchIn(name))
            devices += PadDevice(p, name, abs.toMap(), isPad)
        }

        for (line in runSu("getevent -lp").lineSequence()) {
            if (line.startsWith("add device")) {
                flush()
                path = line.substringAfter(":").trim()
                name = ""
                abs = mutableMapOf()
                hasPadKey = false
                section = ""
                continue
            }
            if (line.trimStart().startsWith("name:")) {
                name = line.substringAfter("\"", "").substringBeforeLast("\"")
                continue
            }
            sectionRe.find(line)?.let { section = it.groupValues[1] }
            when (section) {
                "KEY" -> if (line.split(Regex("\\s+")).any { it in GAMEPAD_KEYS }) hasPadKey = true
                "ABS" -> absRe.find(line)?.let { m ->
                    val code = ABS_CODES[m.groupValues[1]]
                    if (code != null) abs[code] = AbsInfo(m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[2].toInt())
                }
            }
        }
        flush()
        return devices
    }
}

/**
 * Translates raw evdev events into a controller state (raw, untuned) and hands it to Tuning,
 * which applies deadzones, curves and button remapping and builds the UDP packet.
 */
class PadState(dev: PadDevice) {

    companion object {
        // evdev key code -> XInput button bit. Edit here if your controller's buttons come out wrong.
        private val KEYMAP = mapOf(
            0x130 to 0x1000, // BTN_SOUTH / A
            0x131 to 0x2000, // BTN_EAST  / B
            0x133 to 0x4000, // BTN_NORTH / X
            0x134 to 0x8000, // BTN_WEST  / Y
            0x136 to 0x0100, // BTN_TL  (LB)
            0x137 to 0x0200, // BTN_TR  (RB)
            0x13a to 0x0020, // BTN_SELECT (Back)
            0x13b to 0x0010, // BTN_START
            0x13c to 0x0400, // BTN_MODE (Guide)
            0x13d to 0x0040, // BTN_THUMBL
            0x13e to 0x0080, // BTN_THUMBR
            0x220 to 0x0001, // BTN_DPAD_UP
            0x221 to 0x0002, // BTN_DPAD_DOWN
            0x222 to 0x0004, // BTN_DPAD_LEFT
            0x223 to 0x0008  // BTN_DPAD_RIGHT
        )
        private const val BTN_TL2 = 0x138
        private const val BTN_TR2 = 0x139
    }

    private val abs = dev.abs
    private val padName = dev.name

    // Different controllers put the right stick and the triggers on different axes (Xbox pads use RX/RY for
    // the stick, PlayStation pads use Z/RZ and put the triggers on RX/RY). So we look at where each axis
    // rests when the controller is untouched: sticks rest in the middle of their range, triggers at the minimum.
    private fun isCentered(c: Int): Boolean {
        val i = abs[c] ?: return false
        val center = (i.min + i.max) / 2f
        val half = max((i.max - i.min) / 2f, 1f)
        return kotlin.math.abs(i.value - center) <= half * 0.3f
    }

    private fun restsAtMin(c: Int): Boolean {
        val i = abs[c] ?: return false
        return (i.value - i.min).toFloat() <= max(i.max - i.min, 1) * 0.15f
    }

    private val rightPair: Pair<Int, Int> = when {
        isCentered(3) && isCentered(4) -> 3 to 4
        isCentered(2) && isCentered(5) -> 2 to 5
        abs.containsKey(3) && abs.containsKey(4) -> 3 to 4
        else -> 2 to 5
    }
    private val rsX = rightPair.first
    private val rsY = rightPair.second

    private fun pickTrigger(candidates: List<Int>): Int =
        candidates.firstOrNull { abs.containsKey(it) && it != rsX && it != rsY && restsAtMin(it) }
            ?: candidates.firstOrNull { abs.containsKey(it) && it != rsX && it != rsY }
            ?: -1

    private val ltCode = pickTrigger(listOf(2, 10, 3))
    private val rtCode = pickTrigger(listOf(5, 9, 4))

    private var keyMask = 0
    private var hatX = 0
    private var hatY = 0
    private var ltBtn = false
    private var rtBtn = false
    private var lt = 0f
    private var rt = 0f
    private var lx = 0f
    private var ly = 0f
    private var rx = 0f
    private var ry = 0f

    /** -1..1 */
    private fun norm(v: Int, i: AbsInfo): Float {
        val center = (i.min + i.max) / 2f
        val half = max((i.max - i.min) / 2f, 1f)
        return ((v - center) / half).coerceIn(-1f, 1f)
    }

    /** 0..1 */
    private fun pull(v: Int, i: AbsInfo): Float {
        val range = max(i.max - i.min, 1)
        return ((v - i.min).toFloat() / range).coerceIn(0f, 1f)
    }

    @Synchronized
    fun onEvent(type: Int, code: Int, value: Int) {
        if (type == Evdev.EV_KEY) {
            when (code) {
                BTN_TL2 -> ltBtn = value != 0
                BTN_TR2 -> rtBtn = value != 0
                else -> KEYMAP[code]?.let { bit ->
                    keyMask = if (value != 0) keyMask or bit else keyMask and bit.inv()
                }
            }
        } else if (type == Evdev.EV_ABS) {
            val info = abs[code] ?: return
            when {
                code == 0 -> lx = norm(value, info)
                code == 1 -> ly = -norm(value, info) // evdev up is negative, XInput up is positive
                code == rsX -> rx = norm(value, info)
                code == rsY -> ry = -norm(value, info)
                code == ltCode -> lt = pull(value, info)
                code == rtCode -> rt = pull(value, info)
                code == 16 -> hatX = value
                code == 17 -> hatY = value
            }
        }
    }

    @Synchronized
    fun reset() {
        keyMask = 0; hatX = 0; hatY = 0; ltBtn = false; rtBtn = false
        lt = 0f; rt = 0f; lx = 0f; ly = 0f; rx = 0f; ry = 0f
    }

    @Synchronized
    fun packet(slot: Int, publish: Boolean = true): ByteArray {
        var hat = 0
        if (hatY < 0) hat = hat or 0x0001
        if (hatY > 0) hat = hat or 0x0002
        if (hatX < 0) hat = hat or 0x0004
        if (hatX > 0) hat = hat or 0x0008
        val raw = RawInput()
        raw.buttons = keyMask or hat
        raw.ltBtn = ltBtn
        raw.rtBtn = rtBtn
        raw.lx = lx; raw.ly = ly; raw.rx = rx; raw.ry = ry
        raw.lt = lt; raw.rt = rt
        return Tuning.buildPacket(slot, raw, publish, padName)
    }
}
