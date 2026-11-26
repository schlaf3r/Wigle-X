package com.rfsentinel.app.usb

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Wigle-X version of RF Sentinel's firmware prompt. Some adapters (MT7921AU, MT7612U, MT7610U, RT3070, RTL8822BU)
 * need their maker's non-free firmware, which this build doesn't carry. A notification asks; tapping it opens
 * [FirmwareActivity], which downloads the exact files once from linux-firmware on kernel.org, checked against
 * their SHA-256 ([Firmware]), and restarts the adapter.
 */
object FirmwarePrompt {

    private const val CHANNEL = "usb_firmware"
    private const val NOTIFICATION_ID = 4130

    /** [chip]: what the adapter is called ("MT7921AU"). */
    fun notify(context: Context, chip: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "USB WiFi adapter firmware", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, FirmwareActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            nm.notify(NOTIFICATION_ID, android.app.Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("$chip adapter needs its firmware")
                .setContentText("Tap to download it once from kernel.org")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}

/** Asks before downloading the waiting adapter's firmware, then starts it. Shows only a dialog. */
class FirmwareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chip = UsbWifi.firmwareNeeded
        val blobs = chip?.let { Firmware.missing(this, it.driver) }.orEmpty()
        if (chip == null || blobs.isEmpty()) {
            chip?.let { UsbWifi.retry(this) }
            finish(); return
        }
        val kb = blobs.sumOf { it.size } / 1024
        AlertDialog.Builder(this)
            .setTitle("Download the ${chip.name} firmware?")
            .setMessage("This adapter needs its maker's firmware to run. That firmware isn't free software, so Wigle-X " +
                "doesn't include it.\n\nWigle-X can download it once (about $kb KB) from the Linux firmware repository " +
                "at kernel.org, check it against a fixed SHA-256, and keep it on this phone:\n" +
                blobs.joinToString("\n") { "• ${it.name}" } +
                "\n\nkernel.org sees your IP address, nothing else. The files stay under their maker's redistribution licence.")
            .setPositiveButton("Download") { _, _ -> download(chip, blobs) }
            .setNeutralButton("Licence") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Firmware.licenceUrl(blobs.first())))) }
                finish()
            }
            .setNegativeButton("Not now") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun download(chip: UsbWifi.Chip, blobs: List<Firmware.Blob>) {
        val app = applicationContext
        val main = Handler(Looper.getMainLooper())
        Toast.makeText(app, "Downloading the ${chip.name} firmware…", Toast.LENGTH_SHORT).show()
        finish()
        Thread({
            val error = runCatching { Firmware.download(app, blobs) }.exceptionOrNull()
            main.post {
                if (error == null) {
                    UsbWifi.log("Firmware for ${chip.name} downloaded and checked")
                    FirmwarePrompt.cancel(app)
                    UsbWifi.retry(app)
                    Toast.makeText(app, "Firmware ready: starting the ${chip.name} adapter", Toast.LENGTH_SHORT).show()
                } else {
                    UsbWifi.log("Firmware download failed: ${UsbWifi.describe(error)}")
                    Toast.makeText(app, "Firmware download failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }, "usbwifi-firmware").start()
    }
}
