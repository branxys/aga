package com.example.padrelay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/** State of one controller in normal (non-root) mode. Each controller gets its own player slot (0..3). */
private class AndroidPad(val slot: Int) {
    var keyMask = 0
    var hatMask = 0
    var ltKey = false
    var rtKey = false
    var lt = 0f
    var rt = 0f
    var lx = 0f
    var ly = 0f
    var rx = 0f
    var ry = 0f

    fun reset() {
        keyMask = 0; hatMask = 0; ltKey = false; rtKey = false
        lt = 0f; rt = 0f; lx = 0f; ly = 0f; rx = 0f; ry = 0f
    }

    fun packet(publish: Boolean): ByteArray {
        val raw = RawInput()
        raw.buttons = keyMask or hatMask
        raw.ltBtn = ltKey
        raw.rtBtn = rtKey
        raw.lx = lx; raw.ly = ly; raw.rx = rx; raw.ry = ry
        raw.lt = lt; raw.rt = rt
        return Tuning.buildPacket(slot, raw, publish)
    }
}

/**
 * GamepadRelay: forwards Bluetooth gamepads connected to your phone to a PC over Wi-Fi (UDP).
 * Up to 4 controllers at once, each shows up as its own Xbox 360 controller on the PC.
 *
 * Normal mode: reads controllers through Android's input system (screen must stay on).
 * Root mode:   RelayService reads /dev/input directly as root, so it works with the screen off.
 * Tune tab:    deadzones, anti-deadzone, curves, trigger ranges and button remapping, applied before sending.
 */
class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var port = 9876
    private var running = false // normal (non-root) mode
    private var searching = false
    private var tab = 0         // 0 = Relay, 1 = Tune

    // Normal mode: one entry per connected controller (Android device id -> state). Main thread only.
    private val pads = HashMap<Int, AndroidPad>()
    private val padChecks = mutableListOf<CheckBox>()

    // views
    private lateinit var dot: View
    private lateinit var tabRelay: TextView
    private lateinit var tabTune: TextView
    private lateinit var relayPage: ScrollView
    private lateinit var tunePage: ScrollView
    private lateinit var ipField: EditText
    private lateinit var portField: EditText
    private lateinit var findPill: TextView
    private lateinit var rootToggle: Ui.Toggle
    private lateinit var rootSection: LinearLayout
    private lateinit var padContainer: LinearLayout
    private lateinit var scanPill: TextView
    private lateinit var grabToggle: Ui.Toggle
    private lateinit var startPill: TextView
    private lateinit var status: TextView
    private lateinit var leftPrev: StickPreview
    private lateinit var rightPrev: StickPreview
    private lateinit var ltBar: BarPreview
    private lateinit var rtBar: BarPreview
    private val buttonLabels = ArrayList<TextView>()
    private val mapPills = ArrayList<TextView>()
    private val refreshers = ArrayList<() -> Unit>()

    private val prefs by lazy { getSharedPreferences("padrelay", MODE_PRIVATE) }
    private val pct: (Float) -> String = { v -> "${(v * 100).toInt()}%" }

    private fun anyRunning() = running || RelayService.running
    private fun tuneActive() = tab == 1 && !RelayService.running

    // Controller unplugged while relaying in normal mode: release its buttons on the PC
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) {}
        override fun onInputDeviceRemoved(deviceId: Int) {
            pads.remove(deviceId)?.let { it.reset(); send(it, false) }
        }
    }

    // Keeps every active controller alive on the PC and fixes any lost packet
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            pads.values.forEach { send(it, false) }
            handler.postDelayed(this, 200)
        }
    }

    // Refreshes the live preview while the Tune tab is open
    private val ticker = object : Runnable {
        override fun run() {
            if (tab != 1) return
            val f = Live.frame
            if (f != null) {
                leftPrev.set(f.inLx, f.inLy, f.outLx, f.outLy)
                rightPrev.set(f.inRx, f.inRy, f.outRx, f.outRy)
                ltBar.set(f.inLt, f.outLt)
                rtBar.set(f.inRt, f.outRt)
                for (i in buttonLabels.indices) {
                    val down = (f.buttonsIn and Tuning.BUTTON_BITS[i]) != 0
                    buttonLabels[i].setTextColor(if (down) Ui.ACCENT else Ui.TEXT)
                }
            }
            handler.postDelayed(this, 33)
        }
    }

    // =====================================================================================
    // setup
    // =====================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Tuning.ensureLoaded(applicationContext)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            fitsSystemWindows = true
        }
        root.addView(buildHeader())

        val content = FrameLayout(this)
        relayPage = buildRelayPage()
        tunePage = buildTunePage()
        content.addView(relayPage)
        content.addView(tunePage)
        root.addView(content, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        setContentView(root)

        showTab(0)
        updateRootUi(rootToggle.sw.isChecked)
        refreshButtons()
        if (rootToggle.sw.isChecked) scanControllers()
        findPc(null) // search automatically on launch
    }

    override fun onResume() {
        super.onResume()
        RelayService.listener = { s -> runOnUiThread { status.text = s; refreshButtons() } }
        if (RelayService.running) status.text = RelayService.status
        refreshButtons()
        if (tab == 1) startTicker()
    }

    override fun onPause() {
        RelayService.listener = null
        stopTicker()
        super.onPause()
    }

    override fun onDestroy() {
        if (running) stopRelay()
        io.shutdown()
        super.onDestroy()
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun stopTicker() = handler.removeCallbacks(ticker)

    private fun scroll(col: View): ScrollView = ScrollView(this).apply {
        addView(col)
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    private fun pageColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this@MainActivity, 16)
        setPadding(p, Ui.dp(this@MainActivity, 8), p, p)
    }

    // =====================================================================================
    // header + tabs
    // =====================================================================================

    private fun buildHeader(): View {
        val ctx = this
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(ctx, 16)
            setPadding(p, p, p, Ui.dp(ctx, 8))
        }
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dot = View(ctx).apply { background = Ui.rounded(Ui.MUTED, 6, ctx) }
        titleRow.addView(dot, LinearLayout.LayoutParams(Ui.dp(ctx, 12), Ui.dp(ctx, 12)).apply {
            rightMargin = Ui.dp(ctx, 10)
        })
        titleRow.addView(Ui.text(ctx, "GamepadRelay", 22f, Ui.TEXT, true))
        col.addView(titleRow)

        val tabs = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(Ui.SURFACE, 16, ctx)
            val p = Ui.dp(ctx, 4)
            setPadding(p, p, p, p)
        }
        tabRelay = Ui.pill(ctx, "Relay") { showTab(0) }
        tabTune = Ui.pill(ctx, "Tune") { showTab(1) }
        tabs.addView(tabRelay, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        tabs.addView(tabTune, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        col.addView(tabs, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 14) })
        return col
    }

    private fun showTab(i: Int) {
        tab = i
        relayPage.visibility = if (i == 0) View.VISIBLE else View.GONE
        tunePage.visibility = if (i == 1) View.VISIBLE else View.GONE
        Ui.style(tabRelay, if (i == 0) Ui.SURFACE2 else Color.TRANSPARENT, if (i == 0) Ui.TEXT else Ui.MUTED)
        Ui.style(tabTune, if (i == 1) Ui.SURFACE2 else Color.TRANSPARENT, if (i == 1) Ui.TEXT else Ui.MUTED)
        if (i == 1) startTicker() else stopTicker()
    }

    // =====================================================================================
    // relay page
    // =====================================================================================

    private fun buildRelayPage(): ScrollView {
        val ctx = this
        val col = pageColumn()

        // --- connection ---
        val conn = Ui.card(ctx)
        conn.addView(Ui.text(ctx, "PC", 13f, Ui.MUTED))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        ipField = Ui.field(ctx, "IP address", prefs.getString("ip", "") ?: "")
        portField = Ui.field(ctx, "Port", prefs.getString("port", "9876") ?: "9876")
        portField.inputType = InputType.TYPE_CLASS_NUMBER
        row.addView(ipField, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        row.addView(portField, LinearLayout.LayoutParams(Ui.dp(ctx, 88), Ui.WRAP).apply {
            leftMargin = Ui.dp(ctx, 8)
        })
        conn.addView(row, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 8) })
        findPill = Ui.pill(ctx, "Find PC automatically") { findPc(null) }
        conn.addView(findPill, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 10) })
        col.addView(conn)

        // --- controllers ---
        val ctl = Ui.card(ctx)
        rootToggle = Ui.toggle(ctx, "Root mode", "Keeps working with the screen off", prefs.getBoolean("root", false)) { on ->
            prefs.edit().putBoolean("root", on).apply()
            updateRootUi(on)
            if (on && padChecks.isEmpty()) scanControllers()
        }
        ctl.addView(rootToggle.view)

        rootSection = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        padContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scanPill = Ui.pill(ctx, "Scan controllers") { scanControllers() }
        grabToggle = Ui.toggle(
            ctx, "Block phone from seeing them", "Stops the screen waking on button or touchpad input",
            prefs.getBoolean("grab", true)
        ) { on -> prefs.edit().putBoolean("grab", on).apply() }
        rootSection.addView(padContainer)
        rootSection.addView(scanPill, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply {
            topMargin = Ui.dp(ctx, 8)
            bottomMargin = Ui.dp(ctx, 4)
        })
        rootSection.addView(grabToggle.view)
        ctl.addView(rootSection)
        col.addView(ctl)

        // --- start / stop ---
        startPill = Ui.pill(ctx, "Start relay", Ui.ACCENT, Color.WHITE) {
            when {
                running -> stopRelay()
                RelayService.running -> stopRootRelay()
                rootToggle.sw.isChecked -> startRootRelay()
                else -> startRelay()
            }
        }
        startPill.textSize = 16f
        startPill.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 16), Ui.dp(ctx, 16), Ui.dp(ctx, 16))
        col.addView(startPill, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = Ui.dp(ctx, 12) })

        status = Ui.text(
            ctx, "Pair your controllers in Android's Bluetooth settings (up to 4), and run the receiver on your PC.",
            13f, Ui.MUTED
        )
        col.addView(status)
        return scroll(col)
    }

    private fun refreshButtons() {
        val busy = anyRunning()
        Ui.style(startPill, if (busy) Ui.SURFACE2 else Ui.ACCENT, if (busy) Ui.TEXT else Color.WHITE)
        startPill.text = if (busy) "Stop relay" else "Start relay"
        Ui.enable(findPill, !busy && !searching)
        Ui.enable(scanPill, !busy)
        Ui.enable(rootToggle.sw, !busy)
        Ui.enable(grabToggle.sw, !busy)
        padChecks.forEach { it.isEnabled = !busy }
        dot.background = Ui.rounded(if (busy) Ui.GOOD else Ui.MUTED, 6, this)
    }

    private fun updateRootUi(on: Boolean) {
        rootSection.visibility = if (on) View.VISIBLE else View.GONE
    }

    // =====================================================================================
    // tune page
    // =====================================================================================

    private fun buildTunePage(): ScrollView {
        val ctx = this
        val col = pageColumn()

        // --- presets ---
        val presets = Ui.card(ctx)
        presets.addView(Ui.text(ctx, "Presets", 13f, Ui.MUTED))
        val prow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (name in listOf("Default", "Drift fix", "Precise", "Snappy")) {
            val p = Ui.pill(ctx, name) {
                Tuning.applyPreset(name)
                refreshers.forEach { it() }
                Tuning.save(applicationContext)
            }
            prow.addView(p, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { rightMargin = Ui.dp(ctx, 8) })
        }
        val hs = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(prow)
        }
        presets.addView(hs, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 8) })
        col.addView(presets)

        // --- live preview ---
        val live = Ui.card(ctx)
        live.addView(Ui.text(ctx, "Live preview", 13f, Ui.MUTED))
        val sticks = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        leftPrev = StickPreview(ctx, true)
        rightPrev = StickPreview(ctx, false)
        sticks.addView(leftPrev, LinearLayout.LayoutParams(0, Ui.dp(ctx, 150), 1f))
        sticks.addView(rightPrev, LinearLayout.LayoutParams(0, Ui.dp(ctx, 150), 1f))
        live.addView(sticks, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 8) })

        ltBar = BarPreview(ctx, true)
        rtBar = BarPreview(ctx, false)
        val trow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        trow.addView(Ui.text(ctx, "LT", 12f, Ui.MUTED))
        trow.addView(ltBar, LinearLayout.LayoutParams(0, Ui.dp(ctx, 14), 1f).apply {
            leftMargin = Ui.dp(ctx, 6); rightMargin = Ui.dp(ctx, 14)
        })
        trow.addView(Ui.text(ctx, "RT", 12f, Ui.MUTED))
        trow.addView(rtBar, LinearLayout.LayoutParams(0, Ui.dp(ctx, 14), 1f).apply { leftMargin = Ui.dp(ctx, 6) })
        live.addView(trow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 6) })
        live.addView(
            Ui.text(
                ctx,
                "Grey = raw input, blue = what the PC receives. Rings show the deadzone and outer limit. " +
                    "Works with the screen on in normal mode; in root mode start the relay first.",
                12f, Ui.MUTED
            ),
            LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 10) }
        )
        col.addView(live)

        col.addView(stickCard("Left stick", Tuning.cfg.left))
        col.addView(stickCard("Right stick", Tuning.cfg.right))
        col.addView(triggerCard("Left trigger", Tuning.cfg.lt))
        col.addView(triggerCard("Right trigger", Tuning.cfg.rt))
        col.addView(buttonsCard())
        return scroll(col)
    }

    private fun saveTuning() = Tuning.save(applicationContext)

    private fun addSlider(
        card: LinearLayout, label: String, lo: Float, hi: Float,
        get: () -> Float, set: (Float) -> Unit, fmt: (Float) -> String
    ) {
        val s = Ui.slider(this, label, lo, hi, get(), fmt, { set(it) }, { saveTuning() })
        card.addView(s.view)
        refreshers.add { s.set(get()) }
    }

    private fun addToggle(
        card: LinearLayout, label: String, sub: String?,
        get: () -> Boolean, set: (Boolean) -> Unit
    ) {
        val t = Ui.toggle(this, label, sub, get()) { on -> set(on); saveTuning() }
        card.addView(t.view)
        refreshers.add { t.set(get()) }
    }

    private fun stickCard(title: String, c: StickCfg): View {
        val card = Ui.card(this)
        card.addView(Ui.text(this, title, 17f, Ui.TEXT, true))
        addSlider(card, "Inner deadzone", 0f, 0.6f, { c.inner }, { c.inner = it }, pct)
        addSlider(card, "Outer limit", 0.5f, 1f, { c.outer }, { c.outer = it }, pct)
        addSlider(card, "Anti-deadzone", 0f, 0.5f, { c.anti }, { c.anti = it }, pct)
        addSlider(card, "Response curve (below 1 snappy, above 1 precise)", 0.4f, 3f, { c.curve }, { c.curve = it }) {
            String.format("%.2f", it)
        }
        addToggle(card, "Radial deadzone", "Off uses a square, per-axis deadzone", { c.radial }, { c.radial = it })
        addToggle(card, "Invert X", null, { c.invertX }, { c.invertX = it })
        addToggle(card, "Invert Y", null, { c.invertY }, { c.invertY = it })
        return card
    }

    private fun triggerCard(title: String, c: TriggerCfg): View {
        val card = Ui.card(this)
        card.addView(Ui.text(this, title, 17f, Ui.TEXT, true))
        addSlider(card, "Start (deadzone)", 0f, 0.6f, { c.start }, { c.start = it }, pct)
        addSlider(card, "Max (counts as fully pressed)", 0.4f, 1f, { c.end }, { c.end = it }, pct)
        addSlider(card, "Response curve", 0.4f, 3f, { c.curve }, { c.curve = it }) { String.format("%.2f", it) }
        return card
    }

    private fun targetName(code: Int): String = when {
        code in 0..14 -> Tuning.BUTTON_NAMES[code]
        code == 15 -> "LT (trigger)"
        code == 16 -> "RT (trigger)"
        else -> "Disabled"
    }

    private fun refreshMapPills() {
        for (i in mapPills.indices) {
            val code = Tuning.cfg.map[i]
            mapPills[i].text = targetName(code)
            Ui.style(mapPills[i], Ui.SURFACE2, if (code == i) Ui.MUTED else Ui.ACCENT)
        }
    }

    private fun pickTarget(i: Int) {
        val items = Tuning.BUTTON_NAMES + listOf("LT (trigger)", "RT (trigger)", "Disabled")
        AlertDialog.Builder(this)
            .setTitle("${Tuning.BUTTON_NAMES[i]} acts as")
            .setItems(items.toTypedArray()) { _, which ->
                Tuning.cfg.map[i] = if (which in 0..16) which else -1
                refreshMapPills()
                saveTuning()
            }
            .show()
    }

    private fun buttonsCard(): View {
        val ctx = this
        val card = Ui.card(ctx)
        card.addView(Ui.text(ctx, "Buttons", 17f, Ui.TEXT, true))
        card.addView(
            Ui.text(ctx, "Tap a button's target to remap it. Buttons light up blue while you press them.", 12f, Ui.MUTED),
            LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = Ui.dp(ctx, 4) }
        )
        addToggle(card, "Swap sticks", "Left stick controls right, and the other way round",
            { Tuning.cfg.swapSticks }, { Tuning.cfg.swapSticks = it })

        for (i in Tuning.BUTTON_NAMES.indices) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4))
            }
            val label = Ui.text(ctx, Tuning.BUTTON_NAMES[i], 15f, Ui.TEXT)
            val pill = Ui.pill(ctx, "", Ui.SURFACE2, Ui.MUTED) { pickTarget(i) }
            pill.textSize = 13f
            pill.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 8), Ui.dp(ctx, 14), Ui.dp(ctx, 8))
            row.addView(label, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            row.addView(pill)
            card.addView(row)
            buttonLabels.add(label)
            mapPills.add(pill)
        }
        refreshMapPills()
        refreshers.add { refreshMapPills() }
        return card
    }

    // =====================================================================================
    // discovery
    // =====================================================================================

    /** Broadcasts a discovery request and fills the IP field with the first PC that answers. */
    private fun findPc(then: (() -> Unit)?) {
        if (searching || anyRunning()) return
        val p = portField.text.toString().toIntOrNull() ?: 9876
        searching = true
        refreshButtons()
        status.text = "Searching for your PC..."

        Thread {
            var foundIp: String? = null
            var foundName = ""
            try {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    s.soTimeout = 500
                    val msg = "PADRELAY?".toByteArray()

                    // Global broadcast plus each network interface's own broadcast address
                    val targets = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
                    NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                        if (ni.isUp && !ni.isLoopback) {
                            ni.interfaceAddresses.forEach { ia -> ia.broadcast?.let { targets.add(it) } }
                        }
                    }

                    val end = System.currentTimeMillis() + 5000
                    val buf = ByteArray(128)
                    while (foundIp == null && System.currentTimeMillis() < end) {
                        for (t in targets) {
                            try { s.send(DatagramPacket(msg, msg.size, t, p)) } catch (_: Exception) {}
                        }
                        try {
                            val pkt = DatagramPacket(buf, buf.size)
                            s.receive(pkt)
                            val text = String(pkt.data, 0, pkt.length)
                            if (text.startsWith("PADRELAY!")) {
                                foundIp = pkt.address.hostAddress
                                foundName = text.removePrefix("PADRELAY!")
                            }
                        } catch (_: SocketTimeoutException) {
                        }
                    }
                }
            } catch (_: Exception) {
            }

            runOnUiThread {
                searching = false
                refreshButtons()
                if (foundIp != null) {
                    ipField.setText(foundIp)
                    status.text = "Found ${if (foundName.isNotEmpty()) "\"$foundName\" at " else ""}$foundIp"
                    then?.invoke()
                } else {
                    status.text = "No PC found. Make sure the receiver is running on the same Wi-Fi " +
                        "and UDP port $p is allowed through the firewall, or enter the IP manually."
                }
            }
        }.start()
    }

    // =====================================================================================
    // root mode
    // =====================================================================================

    private fun scanControllers() {
        status.text = "Asking for root permission... (accept the prompt from your root manager)"
        Thread {
            val root = Evdev.checkRoot()
            if (!root.ok) {
                runOnUiThread { status.text = root.message }
                return@Thread
            }
            val all = try { Evdev.scan() } catch (_: Exception) { emptyList() }
            val gamepads = all.filter { it.isGamepad && !Evdev.isCompanion(it.name) }
            // Fallback: if nothing looks like a gamepad, offer every device that has analog axes
            val shown = if (gamepads.isNotEmpty()) gamepads
            else all.filter { it.abs.isNotEmpty() && !Evdev.isCompanion(it.name) }
            runOnUiThread {
                padContainer.removeAllViews()
                padChecks.clear()
                // Saved selection (names; duplicates are allowed for two identical controllers)
                val saved = (prefs.getString("pads", "") ?: "").split("\n").filter { it.isNotEmpty() }.toMutableList()
                for (dev in shown) {
                    val cb = CheckBox(this).apply {
                        text = "${dev.name}  (${dev.path.substringAfterLast('/')})"
                        setTextColor(Ui.TEXT)
                        textSize = 14f
                        tag = dev.name
                        isChecked = saved.remove(dev.name)
                    }
                    padChecks.add(cb)
                    padContainer.addView(cb)
                }
                if (padChecks.none { it.isChecked } && padChecks.size == 1) padChecks[0].isChecked = true
                status.text = when {
                    gamepads.isNotEmpty() -> "Root OK. Found ${shown.size} controller(s). Tick the ones to relay (max 4), then tap Start relay."
                    shown.isNotEmpty() -> "Root OK, but nothing looked like a gamepad. Showing all devices with axes; tick your controllers."
                    else -> "Root OK, but no controller was found (${all.size} input devices seen). " +
                        "Make sure your controllers are connected via Bluetooth, then scan again."
                }
                refreshButtons()
            }
        }.start()
    }

    private fun startRootRelay() {
        val host = ipField.text.toString().trim()
        val p = portField.text.toString().toIntOrNull()
        if (p == null) {
            status.text = "Enter a valid port."
            return
        }
        val names = padChecks.filter { it.isChecked }.map { it.tag as String }
        if (names.isEmpty()) {
            status.text = "Scan and tick at least one controller first."
            return
        }
        if (names.size > 4) {
            status.text = "Up to 4 controllers are supported. Untick some."
            return
        }
        if (host.isEmpty()) {
            findPc { startRootRelay() }
            return
        }
        prefs.edit()
            .putString("ip", host).putString("port", p.toString())
            .putString("pads", names.joinToString("\n")).apply()
        val i = Intent(this, RelayService::class.java).apply {
            action = RelayService.ACTION_START
            putExtra(RelayService.EXTRA_HOST, host)
            putExtra(RelayService.EXTRA_PORT, p)
            putStringArrayListExtra(RelayService.EXTRA_DEVICES, ArrayList(names))
            putExtra(RelayService.EXTRA_GRAB, grabToggle.sw.isChecked)
        }
        ContextCompat.startForegroundService(this, i)
        status.text = "Starting root relay..."
        refreshButtons()
    }

    private fun stopRootRelay() {
        startService(Intent(this, RelayService::class.java).setAction(RelayService.ACTION_STOP))
    }

    // =====================================================================================
    // normal mode: start / stop
    // =====================================================================================

    private fun startRelay() {
        val host = ipField.text.toString().trim()
        val p = portField.text.toString().toIntOrNull()
        if (p == null) {
            status.text = "Enter a valid port."
            return
        }
        if (host.isEmpty()) {
            // No IP yet: search first, then start automatically if something answers
            findPc { startRelay() }
            return
        }
        prefs.edit().putString("ip", host).putString("port", p.toString()).apply()
        port = p
        status.text = "Connecting..."
        io.execute {
            try {
                socket = DatagramSocket()
                address = InetAddress.getByName(host)
                runOnUiThread {
                    running = true
                    pads.clear()
                    (getSystemService(INPUT_SERVICE) as InputManager).registerInputDeviceListener(deviceListener, handler)
                    handler.postDelayed(heartbeat, 200)
                    refreshButtons()
                    status.text = "Relaying to $host:$port\nKeep this app open and press a button on each controller " +
                        "(up to 4). Each one becomes its own player on the PC."
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Error: ${e.message}" }
            }
        }
    }

    private fun stopRelay() {
        handler.removeCallbacks(heartbeat)
        (getSystemService(INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(deviceListener)
        pads.values.forEach { it.reset(); send(it, false) } // tell the PC everything is released
        pads.clear()
        running = false
        io.execute { socket?.close(); socket = null }
        refreshButtons()
        status.text = "Stopped."
    }

    // =====================================================================================
    // normal mode: input handling (also feeds the live preview while the Tune tab is open)
    // =====================================================================================

    /** The state for this controller, giving it the lowest free player slot the first time it is seen. */
    private fun padFor(deviceId: Int): AndroidPad? {
        pads[deviceId]?.let { return it }
        val slot = (0..3).firstOrNull { s -> pads.values.none { it.slot == s } } ?: return null
        return AndroidPad(slot).also { pads[deviceId] = it }
    }

    private fun keyToBit(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> 0x0001
        KeyEvent.KEYCODE_DPAD_DOWN -> 0x0002
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x0004
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x0008
        KeyEvent.KEYCODE_BUTTON_START -> 0x0010
        KeyEvent.KEYCODE_BUTTON_SELECT -> 0x0020
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 0x0040
        KeyEvent.KEYCODE_BUTTON_THUMBR -> 0x0080
        KeyEvent.KEYCODE_BUTTON_L1 -> 0x0100
        KeyEvent.KEYCODE_BUTTON_R1 -> 0x0200
        KeyEvent.KEYCODE_BUTTON_MODE -> 0x0400
        KeyEvent.KEYCODE_BUTTON_A -> 0x1000
        KeyEvent.KEYCODE_BUTTON_B -> 0x2000
        KeyEvent.KEYCODE_BUTTON_X -> 0x4000
        KeyEvent.KEYCODE_BUTTON_Y -> 0x8000
        else -> 0
    }

    private fun isGamepad(source: Int) =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if ((running || tuneActive()) && isGamepad(event.source)) {
            val down = event.action == KeyEvent.ACTION_DOWN
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true
            val p = padFor(event.deviceId) ?: return true // more than 4 controllers: ignore extras

            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_L2 -> { p.ltKey = down; send(p); return true }
                KeyEvent.KEYCODE_BUTTON_R2 -> { p.rtKey = down; send(p); return true }
            }
            val bit = keyToBit(event.keyCode)
            if (bit != 0) {
                p.keyMask = if (down) p.keyMask or bit else p.keyMask and bit.inv()
                send(p)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun axisOf(e: MotionEvent, axis: Int): Float? {
        e.device?.getMotionRange(axis, e.source) ?: return null
        return e.getAxisValue(axis)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if ((running || tuneActive()) &&
            (e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            e.action == MotionEvent.ACTION_MOVE
        ) {
            val p = padFor(e.deviceId) ?: return true

            // Raw values only: deadzones and curves are applied by Tuning. Up is +1 (Android Y is down-positive).
            p.lx = axisOf(e, MotionEvent.AXIS_X) ?: 0f
            p.ly = -(axisOf(e, MotionEvent.AXIS_Y) ?: 0f)

            // Right stick: most Xbox-style pads use Z/RZ, some use RX/RY
            val hasZ = e.device?.getMotionRange(MotionEvent.AXIS_Z, e.source) != null
            val rxAxis = if (hasZ) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX
            val ryAxis = if (hasZ) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY
            p.rx = axisOf(e, rxAxis) ?: 0f
            p.ry = -(axisOf(e, ryAxis) ?: 0f)

            // Triggers
            val l = axisOf(e, MotionEvent.AXIS_LTRIGGER) ?: axisOf(e, MotionEvent.AXIS_BRAKE)
            val r = axisOf(e, MotionEvent.AXIS_RTRIGGER) ?: axisOf(e, MotionEvent.AXIS_GAS)
            if (l != null) p.lt = l.coerceIn(0f, 1f)
            if (r != null) p.rt = r.coerceIn(0f, 1f)

            // D-pad as hat axes
            val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
            var hat = 0
            if (hy < -0.5f) hat = hat or 0x0001
            if (hy > 0.5f) hat = hat or 0x0002
            if (hx < -0.5f) hat = hat or 0x0004
            if (hx > 0.5f) hat = hat or 0x0008
            p.hatMask = hat

            send(p)
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    // =====================================================================================
    // normal mode: networking
    // =====================================================================================

    /** Builds the tuned packet (updating the live preview when publish is true) and sends it if relaying. */
    private fun send(p: AndroidPad, publish: Boolean = true) {
        val data = p.packet(publish)
        if (!running) return
        io.execute {
            try {
                val s = socket
                val a = address
                if (s != null && a != null) s.send(DatagramPacket(data, data.size, a, port))
            } catch (_: Exception) {
            }
        }
    }
}
