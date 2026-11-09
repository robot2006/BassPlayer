package com.bassplayer

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/** Offline, read-only PCM observation point. The TeeAudioProcessor owns playback passthrough. */
object AudioTap {
    fun interface Sink {
        fun pushSamples(samples: FloatArray, count: Int, sampleRate: Int)
    }

    @Volatile var sink: Sink? = null
}

@UnstableApi
class MonoTapSink(private val target: () -> AudioTap.Sink?) : TeeAudioProcessor.AudioBufferSink {
    private var sampleRate = 48000
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var mono = FloatArray(16384)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz
        channels = max(1, channelCount)
        this.encoding = encoding
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val sink = target() ?: return
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val src = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = src.position()
        val frameBytes = bytesPerSample * channels
        val frames = src.remaining() / frameBytes
        if (frames <= 0) return
        if (mono.size < frames) mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val offset = base + f * frameBytes + c * bytesPerSample
                sum += when (encoding) {
                    C.ENCODING_PCM_8BIT -> ((src.get(offset).toInt() and 0xff) - 128) / 128f
                    C.ENCODING_PCM_16BIT -> src.getShort(offset) / 32768f
                    C.ENCODING_PCM_24BIT -> {
                        val value = (src.get(offset + 2).toInt() shl 16) or
                            ((src.get(offset + 1).toInt() and 0xff) shl 8) or
                            (src.get(offset).toInt() and 0xff)
                        value / 8388608f
                    }
                    C.ENCODING_PCM_32BIT -> src.getInt(offset) / 2147483648f
                    C.ENCODING_PCM_FLOAT -> src.getFloat(offset)
                    else -> 0f
                }
            }
            mono[f] = sum / channels
        }
        try {
            sink.pushSamples(mono, frames, sampleRate)
        } catch (_: Exception) {
            // Analysis must never interrupt the audio output.
        }
    }
}
