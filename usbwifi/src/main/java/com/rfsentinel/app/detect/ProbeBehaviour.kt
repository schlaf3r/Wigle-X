package com.rfsentinel.app.detect

import java.util.Locale

/**
 * How steadily a WiFi client sends wildcard probe requests, heard by a USB adapter (FlipDeFlock's
 * observation): a camera on a pole runs on mains power and probes without pause, from the same
 * address, while a phone probes in short bursts to save its battery. The adapter hops channels and
 * misses some probes, so this counts the 8 s windows that held at least one, not single frames.
 */
// Wigle-X: RF Sentinel's detection match (supportHit) removed; only the probe-run counter the drivers keep is here.
object ProbeBehaviour {
    const val WINDOW_MS = 8_000L
    /** A silence this long ends a run. */
    const val BREAK_MS = 45_000L
    /** A run counts as non-stop once it lasts this long with probes in at least [MIN_COVERAGE] of its windows. */
    const val MIN_SPAN_MS = 120_000L
    const val MIN_COVERAGE = 0.5

    /** One unbroken run of wildcard probes from a device. */
    data class Run(val probes: Int, val firstAt: Long, val lastAt: Long, val windows: Int) {
        val spanMs: Long get() = lastAt - firstAt
        /** Share of the 8 s windows from first to last probe that held a probe. */
        val coverage: Double get() = windows.toDouble() / (lastAt / WINDOW_MS - firstAt / WINDOW_MS + 1)
        val nonStop: Boolean get() = spanMs >= MIN_SPAN_MS && coverage >= MIN_COVERAGE
        /** Probes heard per 8 s window that held any. */
        val perWindow: Double get() = probes.toDouble() / windows.coerceAtLeast(1)

        /** "3.2 per 8 s, in 90% of windows for 4 min". */
        fun describe(): String = String.format(Locale.US, "%.1f per 8 s, in %d%% of windows for %s",
            perWindow, (coverage * 100).toInt().coerceAtMost(100), duration(spanMs))
    }

    /** Kept per device by the frame parser; not thread-safe (like MonitorFrames). */
    class Counter {
        private var probes = 0; private var windows = 0
        private var firstAt = 0L; private var lastAt = 0L; private var window = -1L

        fun probe(now: Long) {
            if (probes == 0 || now - lastAt > BREAK_MS || now < lastAt) {
                probes = 0; windows = 0; window = -1L; firstAt = now
            }
            probes++; lastAt = now
            val w = now / WINDOW_MS
            if (w != window) { window = w; windows++ }
        }

        fun run(): Run? = if (probes == 0) null else Run(probes, firstAt, lastAt, windows)
    }

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 120 -> "$s s"
            s < 7200 -> "${s / 60} min"
            else -> "${s / 3600} h"
        }
    }
}
