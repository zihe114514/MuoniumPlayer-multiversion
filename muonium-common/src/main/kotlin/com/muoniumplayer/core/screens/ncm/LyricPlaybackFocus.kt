// Author: dishanhai
package com.muoniumplayer.core.screens.ncm

/** Playback-relative focus, independent of scroll position, wrapping and viewport size. */
object LyricPlaybackFocus {
    @JvmStatic
    fun blur(upcomingOrder: Int, untilStartMs: Double, inspection: Double): Double {
        if (upcomingOrder <= 0 || !untilStartMs.isFinite() || untilStartMs <= 0) return 0.0
        val depth = .30 + .70 * smooth((upcomingOrder - 1) / 4.0)
        return depth * smooth(untilStartMs / 1800.0) * (1 - inspection.coerceIn(0.0, 1.0))
    }

    private fun smooth(value: Double): Double {
        val t = value.coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
}
