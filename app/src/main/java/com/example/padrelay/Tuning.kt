package com.example.padrelay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Unprocessed controller state. Sticks are -1..1 with UP = +1, triggers are 0..1. */
class RawInput {
    var buttons = 0          // XInput button bits (including the d-pad)
    var ltBtn = false        // digital L2 (counts as a full pull)
    var rtBtn = false
    var lx = 0f
    var ly = 0f
    var rx = 0f
    var ry = 0f
    var lt = 0f
    var rt = 0f
}

class StickCfg {
    var inner = 0.08f   // inner deadzone: raw movement below this is ignored
    var outer = 1f      // outer limit: raw movement at or above this counts as full tilt
    var anti = 0f       // anti-deadzone: output jumps to at least this as soon as the stick leaves the deadzone
    var curve = 1f      // response curve exponent (<1 = snappier near the centre, >1 = finer near the centre)
    var invertX = false
    var invertY = false
    var radial = true   // radial (round) deadzone; false = per-axis (square)

    fun reset() {
        inner = 0.08f; outer = 1f; anti = 0f; curve = 1f
        invertX = false; invertY = false; radial = true
    }
}

class TriggerCfg {
    var start = 0f      // pull below this is ignored
    var end = 1f        // pull at or above this counts as fully pressed
    var curve = 1f

    fun reset() { start = 0f; end = 1f; curve = 1f }
}

class PadConfig {
    val left = StickCfg()
    val right = StickCfg()
    val lt = TriggerCfg()
    val rt = TriggerCfg()
    var swapSticks = false

    /** For each physical button (index into Tuning.BUTTON_NAMES): 0..14 = button, 15 = LT, 16 = RT, -1 = disabled. */
    val map = IntArray(15) { it }

    fun resetAll() {
        left.reset(); right.reset(); lt.reset(); rt.reset()
        swapSticks = false
        for (i in map.indices) map[i] = i
    }
}

/** The most recent processed frame, for the live preview in the Tune tab. */
object Live {
    class Frame(
        val inLx: Float, val inLy: Float, val outLx: Float, val outLy: Float,
        val inRx: Float, val inRy: Float, val outRx: Float, val outRy: Float,
        val inLt: Float, val outLt: Float, val inRt: Float, val outRt: Float,
        val buttonsIn: Int, val buttonsOut: Int
    )

    @Volatile var frame: Frame? = null
}

object Tuning {
    val BUTTON_NAMES = listOf(
        "A", "B", "X", "Y", "LB", "RB", "L3 (stick click)", "R3 (stick click)",
        "Start", "Back", "Guide", "D-pad up", "D-pad down", "D-pad left", "D-pad right"
    )
    val BUTTON_BITS = intArrayOf(
        0x1000, 0x2000, 0x4000, 0x8000, 0x0100, 0x0200, 0x0040, 0x0080,
        0x0010, 0x0020, 0x0400, 0x0001, 0x0002, 0x0004, 0x0008
    )

    val cfg = PadConfig()

    private const val PREFS = "padrelay"
    private const val KEY = "tuning"
    private var loaded = false

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        try { fromJson(JSONObject(s)) } catch (_: Exception) { }
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, toJson().toString()).apply()
    }

    fun applyPreset(name: String) {
        val sticks = listOf(cfg.left, cfg.right)
        val trigs = listOf(cfg.lt, cfg.rt)
        sticks.forEach { it.reset() }
        trigs.forEach { it.reset() }
        when (name) {
            "Drift fix" -> {
                sticks.forEach { it.inner = 0.16f; it.outer = 0.95f }
                trigs.forEach { it.start = 0.05f }
            }
            "Precise" -> sticks.forEach { it.inner = 0.06f; it.curve = 1.7f }
            "Snappy" -> {
                sticks.forEach { it.inner = 0.05f; it.curve = 0.65f; it.outer = 0.9f; it.anti = 0.05f }
                trigs.forEach { it.end = 0.9f }
            }
        }
    }

    // ---------- processing ----------

    private fun shape(m: Float, inner: Float, outer: Float, anti: Float, curve: Float): Float {
        if (m <= inner) return 0f
        val span = max(outer - inner, 0.01f)
        val t = ((m - inner) / span).coerceIn(0f, 1f).pow(curve)
        return anti + t * (1f - anti)
    }

    private fun stick(x: Float, y: Float, c: StickCfg): FloatArray {
        var ox: Float
        var oy: Float
        if (c.radial) {
            val mag = hypot(x, y)
            val m = shape(mag, c.inner, c.outer, c.anti, c.curve)
            if (mag < 0.0001f || m == 0f) {
                ox = 0f; oy = 0f
            } else {
                val k = m / mag
                ox = x * k; oy = y * k
            }
        } else {
            val sx = shape(abs(x), c.inner, c.outer, c.anti, c.curve)
            val sy = shape(abs(y), c.inner, c.outer, c.anti, c.curve)
            ox = if (x < 0f) -sx else sx
            oy = if (y < 0f) -sy else sy
        }
        if (c.invertX) ox = -ox
        if (c.invertY) oy = -oy
        return floatArrayOf(ox.coerceIn(-1f, 1f), oy.coerceIn(-1f, 1f))
    }

    private fun trigger(v: Float, c: TriggerCfg): Float {
        if (v <= c.start) return 0f
        val span = max(c.end - c.start, 0.01f)
        return ((v - c.start) / span).coerceIn(0f, 1f).pow(c.curve)
    }

    private fun s16(v: Float) = (v * 32767f).toInt().coerceIn(-32767, 32767)
    private fun u8(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    /**
     * Applies the current tuning to a raw controller state and builds the 14-byte UDP packet:
     * magic(0xC8) slot buttons(u16) LT RT (u8) LX LY RX RY (s16), little endian.
     * publish = true also updates the live preview.
     */
    fun buildPacket(slot: Int, raw: RawInput, publish: Boolean): ByteArray {
        val c = cfg

        var lx = raw.lx; var ly = raw.ly; var rx = raw.rx; var ry = raw.ry
        if (c.swapSticks) {
            lx = raw.rx; ly = raw.ry; rx = raw.lx; ry = raw.ly
        }
        val l = stick(lx, ly, c.left)
        val r = stick(rx, ry, c.right)

        // Buttons: remap each pressed physical button to its configured target
        var out = 0
        var ltForce = false
        var rtForce = false
        for (i in 0 until 15) {
            if ((raw.buttons and BUTTON_BITS[i]) != 0) {
                val t = c.map[i]
                when {
                    t in 0..14 -> out = out or BUTTON_BITS[t]
                    t == 15 -> ltForce = true
                    t == 16 -> rtForce = true
                }
            }
        }

        val ltIn = max(raw.lt, if (raw.ltBtn) 1f else 0f)
        val rtIn = max(raw.rt, if (raw.rtBtn) 1f else 0f)
        val ltOut = max(trigger(ltIn, c.lt), if (ltForce) 1f else 0f)
        val rtOut = max(trigger(rtIn, c.rt), if (rtForce) 1f else 0f)

        if (publish) {
            Live.frame = Live.Frame(
                lx, ly, l[0], l[1], rx, ry, r[0], r[1],
                ltIn, ltOut, rtIn, rtOut, raw.buttons, out
            )
        }

        return ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0xC8.toByte())
            put(slot.toByte())
            putShort(out.toShort())
            put(u8(ltOut).toByte())
            put(u8(rtOut).toByte())
            putShort(s16(l[0]).toShort())
            putShort(s16(l[1]).toShort())
            putShort(s16(r[0]).toShort())
            putShort(s16(r[1]).toShort())
        }.array()
    }

    // ---------- persistence ----------

    private fun stickJson(s: StickCfg) = JSONObject()
        .put("inner", s.inner.toDouble()).put("outer", s.outer.toDouble())
        .put("anti", s.anti.toDouble()).put("curve", s.curve.toDouble())
        .put("ix", s.invertX).put("iy", s.invertY).put("radial", s.radial)

    private fun stickLoad(o: JSONObject?, s: StickCfg) {
        if (o == null) return
        s.inner = o.optDouble("inner", s.inner.toDouble()).toFloat()
        s.outer = o.optDouble("outer", s.outer.toDouble()).toFloat()
        s.anti = o.optDouble("anti", s.anti.toDouble()).toFloat()
        s.curve = o.optDouble("curve", s.curve.toDouble()).toFloat()
        s.invertX = o.optBoolean("ix", s.invertX)
        s.invertY = o.optBoolean("iy", s.invertY)
        s.radial = o.optBoolean("radial", s.radial)
    }

    private fun trigJson(t: TriggerCfg) = JSONObject()
        .put("start", t.start.toDouble()).put("end", t.end.toDouble()).put("curve", t.curve.toDouble())

    private fun trigLoad(o: JSONObject?, t: TriggerCfg) {
        if (o == null) return
        t.start = o.optDouble("start", t.start.toDouble()).toFloat()
        t.end = o.optDouble("end", t.end.toDouble()).toFloat()
        t.curve = o.optDouble("curve", t.curve.toDouble()).toFloat()
    }

    private fun toJson(): JSONObject {
        val arr = JSONArray()
        cfg.map.forEach { arr.put(it) }
        return JSONObject()
            .put("l", stickJson(cfg.left)).put("r", stickJson(cfg.right))
            .put("lt", trigJson(cfg.lt)).put("rt", trigJson(cfg.rt))
            .put("swap", cfg.swapSticks).put("map", arr)
    }

    private fun fromJson(o: JSONObject) {
        stickLoad(o.optJSONObject("l"), cfg.left)
        stickLoad(o.optJSONObject("r"), cfg.right)
        trigLoad(o.optJSONObject("lt"), cfg.lt)
        trigLoad(o.optJSONObject("rt"), cfg.rt)
        cfg.swapSticks = o.optBoolean("swap", false)
        val arr = o.optJSONArray("map")
        if (arr != null && arr.length() == cfg.map.size) {
            for (i in cfg.map.indices) cfg.map[i] = arr.optInt(i, i)
        }
    }
}
