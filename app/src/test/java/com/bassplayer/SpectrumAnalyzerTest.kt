package com.bassplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * A HTML-mérés portjának ellenőrzése szintetikus, 8 bites szinuszokkal.
 */
class SpectrumAnalyzerTest {

    private class Capture {
        var hz = 0f
        var note = "--"
        var calls = 0
    }

    private fun measure(frequency: Double, amplitude: Double = 0.8, sampleRate: Int = 48000): Capture {
        val capture = Capture()
        val analyzer = SpectrumAnalyzer(sampleRate) { _, hz, note ->
            capture.calls++
            if (hz > 0f) {
                capture.hz = hz
                capture.note = note
            }
        }

        // 8 x 1024 minta, ahogy a Visualizer adja
        val chunk = 1024
        var index = 0
        repeat(8) {
            val bytes = ByteArray(chunk)
            for (i in 0 until chunk) {
                val value = amplitude * sin(2.0 * PI * frequency * index / sampleRate)
                val quantized = (128 + value * 127.0).toInt().coerceIn(0, 255)
                bytes[i] = quantized.toByte()
                index++
            }
            analyzer.pushWaveform(bytes)
        }

        // 30 fps-es frame-ek (a smoothing beállásához)
        repeat(10) { analyzer.tick() }

        return capture
    }

    @Test
    fun detectsLowBassFrequencies() {
        for (frequency in doubleArrayOf(30.0, 45.0, 55.0, 82.0, 110.0)) {
            val result = measure(frequency)
            assertEquals("f = $frequency Hz", frequency, result.hz.toDouble(), 0.6)
        }
    }

    @Test
    fun reportsNoteForA1() {
        val result = measure(55.0)
        assertTrue("note = ${result.note}", result.note.startsWith("A1"))
    }

    @Test
    fun silenceGivesNoReading() {
        val capture = Capture()
        val analyzer = SpectrumAnalyzer(48000) { _, hz, _ -> if (hz > 0f) capture.hz = hz }
        repeat(8) { analyzer.pushWaveform(ByteArray(1024) { 128.toByte() }) }
        analyzer.tick()
        assertEquals(0f, capture.hz, 0f)
    }
}
