package net.astrorbits.differangle.media

import kotlin.math.sqrt

data class AudioEmitter(val x: Double, val y: Double, val z: Double, val available: Boolean = true)

/** Stereo PCM matrix: input L/R are independently projected from their world emitters. */
data class AudioMix(val ll: Float, val lr: Float, val rl: Float, val rr: Float) {
    companion object {
        val SILENT = AudioMix(0f, 0f, 0f, 0f)
        fun spatial(request: MediaRequest, x: Double, y: Double, z: Double, rightX: Double, rightY: Double, rightZ: Double): AudioMix {
            fun gains(emitter: AudioEmitter): Pair<Float, Float> {
                if (!emitter.available) return 0f to 0f
                val dx = emitter.x - x; val dy = emitter.y - y; val dz = emitter.z - z
                val distance = sqrt(dx * dx + dy * dy + dz * dz)
                val volume = if (request.config.attenuation == AudioAttenuation.NONE) 1.0
                    else (1.0 - distance / request.config.audibleDistance).coerceIn(0.0, 1.0)
                val pan = if (distance < 1e-6) 0.0 else ((dx * rightX + dy * rightY + dz * rightZ) / distance).coerceIn(-1.0, 1.0)
                return (volume * sqrt((1.0 - pan) / 2.0)).toFloat() to (volume * sqrt((1.0 + pan) / 2.0)).toFloat()
            }
            val emitters = request.speakers.ifEmpty { listOf(AudioEmitter(request.x, request.y, request.z)) }
            val left = gains(emitters[0])
            if (emitters.size == 1) return AudioMix(left.first * 0.5f, left.second * 0.5f, left.first * 0.5f, left.second * 0.5f)
            val right = gains(emitters[1])
            return AudioMix(left.first, left.second, right.first, right.second)
        }
    }
}
