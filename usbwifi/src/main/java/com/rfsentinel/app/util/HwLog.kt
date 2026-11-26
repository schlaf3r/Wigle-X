package com.rfsentinel.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wigle-X shim for RF Sentinel's hardware log: the USB WiFi drivers write here. Recent lines stay in memory and in
 * files/[file] (the previous one kept as [file].1) once [init] has run.
 */
class HwLog(val title: String, val file: String, private val maxBytes: Long = 1_000_000L) {
    private val lines = ArrayDeque<String>()

    fun log(line: String) {
        Log.d("HwLog", "$title: $line")
        synchronized(lines) {
            val now = Date()
            lines.addLast(SimpleDateFormat("HH:mm:ss", Locale.US).format(now) + "  " + line)
            while (lines.size > 300) lines.removeFirst()
            dir?.let { File(it, file) }?.let { f ->
                runCatching {
                    if (f.length() > maxBytes) { val old = File(f.path + ".1"); old.delete(); f.renameTo(old) }
                    f.appendText(SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(now) + "  " + line + "\n")
                }
            }
        }
    }

    /** The lines in memory (this run only). */
    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    companion object {
        @Volatile private var dir: File? = null

        /** Where the log files go; set when scanning starts. */
        @JvmStatic fun init(context: Context) { dir = context.applicationContext.filesDir }

        /** An error for a log: its type, message and where it happened. */
        fun describe(e: Throwable): String =
            "${e.javaClass.simpleName}: ${e.message}" + (e.stackTrace.firstOrNull()?.let { " (${it.fileName}:${it.lineNumber})" } ?: "")
    }
}

/** The one hardware log Wigle-X keeps. */
object HwLogs {
    @JvmField val usbWifi = HwLog("USB WiFi adapter", "usbwifi.log", 4_000_000L)
}
