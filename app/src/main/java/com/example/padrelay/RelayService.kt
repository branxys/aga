package com.example.padrelay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Root relay: reads one or more controllers straight from /dev/input as root and forwards
 * them to the PC (one player slot per controller). Runs as a foreground service, so it keeps
 * working with the screen off.
 */
class RelayService : Service() {

    companion object {
        const val ACTION_START = "padrelay.START"
        const val ACTION_STOP = "padrelay.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_DEVICES = "devices"
        const val EXTRA_GRAB = "grab"

        @Volatile var running = false
        @Volatile var status = ""
        @Volatile var listener: ((String) -> Unit)? = null

        private const val CHANNEL_ID = "padrelay"
        private const val NOTIF_ID = 1
    }

    private var worker: Thread? = null
    @Volatile private var deviceThreads: List<Thread> = emptyList()
    @Volatile private var stopFlag = false
    private val procs = CopyOnWriteArrayList<Process>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setStatus(s: String) {
        status = s
        listener?.invoke(s)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must go foreground immediately after startForegroundService()
        goForeground()

        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        val host = intent?.getStringExtra(EXTRA_HOST)
        val names = intent?.getStringArrayListExtra(EXTRA_DEVICES)
        val port = intent?.getIntExtra(EXTRA_PORT, 9876) ?: 9876
        val grab = intent?.getBooleanExtra(EXTRA_GRAB, true) ?: true
        if (host == null || names.isNullOrEmpty()) {
            shutdown()
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_NOT_STICKY

        stopFlag = false
        running = true
        Tuning.ensureLoaded(applicationContext)
        acquireLocks()
        worker = Thread { runLoop(host, port, names.take(4), grab) }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Controller relay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("PadRelay is relaying your controllers")
            .setContentText("Works with the screen off")
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "padrelay:cpu").apply { acquire() }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "padrelay:wifi").apply { acquire() }
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    private fun shutdown() {
        stopFlag = true
        procs.forEach { it.destroy() }
        killHelpers(null)
        deviceThreads.forEach { it.interrupt() }
        worker?.interrupt()
        releaseLocks()
        running = false
        setStatus("Stopped.")
        removeForeground()
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun removeForeground() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
    }

    override fun onDestroy() {
        stopFlag = true
        procs.forEach { it.destroy() }
        killHelpers(null)
        releaseLocks()
        running = false
        super.onDestroy()
    }

    // ---------- worker ----------

    private fun runLoop(host: String, port: Int, names: List<String>, grab: Boolean) {
        // The kernel's input_event layout depends on the reader's bitness (64-bit Android = 24 bytes)
        val frameSize = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) 24 else 16
        val bodyOffset = frameSize - 8

        val lines = Array(names.size) { "Player ${it + 1}: starting..." }
        fun update(i: Int, text: String) {
            synchronized(lines) {
                lines[i] = text
                setStatus(lines.joinToString("\n"))
            }
        }

        // Ask for root once, up front
        while (!stopFlag && Evdev.suPath == null) {
            setStatus("Asking for root permission...")
            val rc = Evdev.checkRoot()
            if (!rc.ok) {
                setStatus(rc.message)
                sleepQuietly(3000)
            }
        }
        if (stopFlag) return

        var socket: DatagramSocket? = null
        val heartbeat = Executors.newSingleThreadScheduledExecutor()
        try {
            val sock = DatagramSocket()
            socket = sock
            val addr = InetAddress.getByName(host)
            val states = Array(names.size) { AtomicReference<PadState?>(null) }
            val claimed = HashSet<String>() // event paths already used by one of our player slots

            fun send(slot: Int, st: PadState, publish: Boolean = true) {
                val d = st.packet(slot, publish)
                try { sock.send(DatagramPacket(d, d.size, addr, port)) } catch (_: Exception) {}
            }

            // Re-send every active pad's state a few times a second so a lost UDP packet can't
            // leave a button stuck. The PC also uses these packets to know a controller is alive.
            heartbeat.scheduleAtFixedRate({
                for (i in states.indices) states[i].get()?.let { send(i, it, false) }
            }, 200, 200, TimeUnit.MILLISECONDS)

            fun deviceLoop(i: Int, name: String) {
                val label = "Player ${i + 1}"
                while (!stopFlag) {
                    update(i, "$label: waiting for \"$name\"...")
                    // Pick the first matching device that no other player slot is using
                    val dev = Evdev.scan().firstOrNull { d ->
                        d.name == name && d.abs.isNotEmpty() && synchronized(claimed) { claimed.add(d.path) }
                    }
                    if (dev == null) {
                        sleepQuietly(2000)
                        continue
                    }

                    val st = PadState(dev)
                    states[i].set(st)
                    update(i, "$label: relaying \"$name\"")

                    // With grab, a small root helper takes exclusive control of the controller so Android
                    // never sees it (no screen wake-ups). Without it we just read the events with cat.
                    val cmd = if (grab) {
                        "CLASSPATH=${applicationInfo.sourceDir} exec app_process /system/bin " +
                            "com.example.padrelay.RootHelperKt ${dev.path}"
                    } else {
                        "exec cat ${dev.path}"
                    }
                    var proc: Process? = null
                    try {
                        val p = ProcessBuilder(Evdev.suBin(), "-c", cmd).start()
                        proc = p
                        procs.add(p)
                        Thread {
                            try {
                                p.errorStream.bufferedReader().forEachLine { line ->
                                    if (line.startsWith("grab failed") || line.startsWith("open failed")) {
                                        update(i, "$label: warning: $line (the screen may still wake)")
                                    }
                                }
                            } catch (_: IOException) {
                            }
                        }.start()

                        val input = DataInputStream(p.inputStream)
                        val buf = ByteArray(frameSize)
                        while (!stopFlag) {
                            input.readFully(buf)
                            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                            val type = bb.getShort(bodyOffset).toInt() and 0xFFFF
                            val code = bb.getShort(bodyOffset + 2).toInt() and 0xFFFF
                            val value = bb.getInt(bodyOffset + 4)
                            st.onEvent(type, code, value)
                            if (type == Evdev.EV_SYN && code == 0) send(i, st)
                        }
                    } catch (_: IOException) {
                        // controller disconnected or su stream closed
                    } finally {
                        proc?.let { it.destroy(); procs.remove(it) }
                        if (grab) killHelpers(dev.path)
                        synchronized(claimed) { claimed.remove(dev.path) }
                    }

                    st.reset()
                    send(i, st) // release everything on the PC
                    states[i].set(null)
                    if (!stopFlag) {
                        update(i, "$label: controller disconnected, waiting for it to come back...")
                        sleepQuietly(1000)
                    }
                }
            }

            // Touchpads / motion sensors that belong to the selected controllers get grabbed too,
            // otherwise touching the touchpad wakes the screen.
            val companionThread: Thread? = if (grab) Thread {
                val active = HashMap<String, Process>() // event path -> helper process
                while (!stopFlag) {
                    val iter = active.entries.iterator()
                    while (iter.hasNext()) {
                        val e = iter.next()
                        if (!alive(e.value)) {
                            procs.remove(e.value)
                            iter.remove()
                        }
                    }
                    val wanted = Evdev.scan().filter { d ->
                        Evdev.isCompanion(d.name) && names.any { n -> d.name.startsWith("$n ") }
                    }
                    for (d in wanted) {
                        if (active.containsKey(d.path)) continue
                        try {
                            val cmd = "CLASSPATH=${applicationInfo.sourceDir} exec app_process /system/bin " +
                                "com.example.padrelay.RootHelperKt ${d.path} discard"
                            val p = ProcessBuilder(Evdev.suBin(), "-c", cmd).start()
                            procs.add(p)
                            active[d.path] = p
                            Thread {
                                try { p.errorStream.bufferedReader().readText() } catch (_: IOException) {}
                            }.start()
                        } catch (_: IOException) {
                        }
                    }
                    sleepQuietly(3000)
                }
                active.values.forEach { it.destroy() }
            } else null

            val threads = names.mapIndexed { i, n -> Thread { deviceLoop(i, n) } } + listOfNotNull(companionThread)
            deviceThreads = threads
            threads.forEach { it.start() }
            threads.forEach { try { it.join() } catch (_: InterruptedException) {} }
        } catch (e: Exception) {
            if (!stopFlag) setStatus("Error: ${e.message}")
        } finally {
            heartbeat.shutdownNow()
            socket?.close()
        }
    }

    /** Make sure no root helper keeps a controller grabbed after we stop (null = all of them). */
    private fun killHelpers(path: String?) {
        val pattern = if (path == null) "[R]ootHelperKt" else "[R]ootHelperKt $path\$"
        Thread { Evdev.runSu("pkill -f '$pattern'") }.start()
    }

    private fun alive(p: Process): Boolean = try {
        p.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}
