// Author: dishanhai
package com.muoniumplayer.core.screens.ncm

import kotlin.math.abs
import kotlin.math.exp

/** Smooths the playback onset and loudness signals into two independent backdrop envelopes. */
internal class CoverRhythmMotion {
    data class Frame(val pulse: Double, val energy: Double)

    private var session = Long.MIN_VALUE
    private var position = 0L
    private var pulse = 0.0
    private var energy = 0.0

    fun reset() {
        session = Long.MIN_VALUE
        pulse = 0.0
        energy = 0.0
    }

    fun sample(
        seconds: Double, sessionId: Long, positionMs: Long, playing: Boolean,
        staticMode: Boolean, onset: Float, loudness: Float,
    ): Frame {
        if (staticMode) {
            reset()
            return Frame(0.0, 0.0)
        }
        val discontinuity = session != sessionId ||
            abs(positionMs.toDouble() - position) > 360 + seconds * 3000
        session = sessionId
        position = positionMs
        if (discontinuity) {
            pulse = 0.0
            energy = 0.0
            return Frame(0.0, 0.0)
        }
        pulse = approach(pulse, if (playing) level(onset) else 0.0, seconds, .032, .21)
        energy = approach(energy, if (playing) level(loudness) else 0.0, seconds, .14, .42)
        return Frame(pulse, energy)
    }

    private fun level(value: Float): Double =
        if (value.isFinite()) value.toDouble().coerceIn(0.0, 1.0) else 0.0

    private fun approach(value: Double, target: Double, seconds: Double, attack: Double, release: Double): Double {
        val tau = if (target > value) attack else release
        val next = target + (value - target) * exp(-seconds.coerceIn(0.0, .1) / tau)
        return if (next < .001) 0.0 else next
    }
}
