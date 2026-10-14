package com.example.padrelay

import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/**
 * Runs as ROOT through app_process (started by RelayService), not inside the normal app process.
 *
 * It opens /dev/input/eventN, takes exclusive control of the device (EVIOCGRAB) so Android itself
 * never sees the controller (no screen wake-ups, no assistant on the Guide button), and copies the
 * raw input events to stdout for RelayService to parse.
 *
 * With a second argument "discard" it only grabs the device and throws its events away. That is used
 * for a controller's touchpad and motion sensors, so touching them can't wake the screen either.
 *
 * Usage: CLASSPATH=<base.apk> app_process /system/bin com.example.padrelay.RootHelperKt /dev/input/eventN [discard]
 */
fun main(args: Array<String>) {
    val path = args.getOrNull(0) ?: return
    val discard = args.getOrNull(1) == "discard"
    val input = try {
        FileInputStream(path)
    } catch (e: Exception) {
        System.err.println("open failed: $e")
        return
    }

    val err = grabDevice(input.fd)
    if (err != null) System.err.println("grab failed: $err") else System.err.println("grabbed")

    val out = FileOutputStream(FileDescriptor.out)
    val buf = ByteArray(24 * 64)
    try {
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            if (!discard) out.write(buf, 0, n)
        }
    } catch (_: IOException) {
        // controller unplugged or the app closed the pipe: exit, which also releases the grab
    }
}

/** EVIOCGRAB with a non-null argument = grab. Uses libcore's ioctlInt through reflection. */
private fun grabDevice(fd: FileDescriptor): String? {
    return try {
        val os = Class.forName("libcore.io.Libcore").getField("os").get(null)
        val osClass = Class.forName("libcore.io.Os")
        val evioGrab = 0x40044590
        val m = osClass.methods.firstOrNull { it.name == "ioctlInt" } ?: return "ioctlInt not found"
        val types = m.parameterTypes
        if (types.size == 2) {
            m.invoke(os, fd, evioGrab)
        } else {
            val ref = types[2].getConstructor(Int::class.javaPrimitiveType).newInstance(1)
            m.invoke(os, fd, evioGrab, ref)
        }
        null
    } catch (e: InvocationTargetException) {
        e.targetException?.toString() ?: e.toString()
    } catch (e: Throwable) {
        e.toString()
    }
}
