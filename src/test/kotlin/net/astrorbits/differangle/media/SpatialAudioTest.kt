package net.astrorbits.differangle.media

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpatialAudioTest {
    private val request = MediaRequest(MediaConfig(audibleDistance = 10f), 256, 144, 15)
    private fun mix(r: MediaRequest = request, x: Double = 0.0, right: Double = 1.0) =
        AudioMix.spatial(r, x, 0.0, 0.0, right, 0.0, 0.0)

    @Test fun `moving listener changes direction and attenuates to silence`() {
        val center = mix()
        val nearby = mix(x = 5.0)
        assertEquals(nearby.ll, nearby.rl)
        assertEquals(0f, nearby.lr)
        assertTrue(nearby.ll < center.ll)
        assertEquals(AudioMix.SILENT, mix(x = 10.0))
        assertEquals(AudioMix.SILENT, mix(x = 20.0))
    }
    @Test fun `turning listener swaps ears`() {
        val forward = mix(x = 2.0)
        val turned = mix(x = 2.0, right = -1.0)
        assertEquals(forward.ll, turned.lr)
        assertEquals(forward.lr, turned.ll)
    }
    @Test fun `two speakers retain separate left and right channels`() {
        val stereo = request.copy(speakers = listOf(AudioEmitter(-2.0, 0.0, 0.0), AudioEmitter(2.0, 0.0, 0.0)))
        val result = mix(stereo)
        assertEquals(0.8f, result.ll, 0.0001f)
        assertEquals(0.8f, result.rr, 0.0001f)
        assertEquals(0f, result.lr)
        assertEquals(0f, result.rl)
    }
    @Test fun `single speaker mixes both channels at its position`() {
        val single = request.copy(speakers = listOf(AudioEmitter(2.0, 0.0, 0.0)))
        val result = mix(single)
        assertEquals(0f, result.ll)
        assertEquals(0.4f, result.lr, 0.0001f)
        assertEquals(result.lr, result.rr)
    }
    @Test fun `missing speaker stays silent without moving sound to base`() {
        assertEquals(AudioMix.SILENT, mix(request.copy(speakers = listOf(AudioEmitter(0.0, 0.0, 0.0, false)))))
        val result = mix(request.copy(speakers = listOf(AudioEmitter(-2.0, 0.0, 0.0, false), AudioEmitter(2.0, 0.0, 0.0))))
        assertEquals(0f, result.ll)
        assertEquals(0.8f, result.rr, 0.0001f)
    }
    @Test fun `none attenuation remains audible beyond distance`() {
        val result = mix(request.copy(config = request.config.copy(attenuation = AudioAttenuation.NONE)), x = 100.0)
        assertEquals(0.5f, result.ll)
    }
}
