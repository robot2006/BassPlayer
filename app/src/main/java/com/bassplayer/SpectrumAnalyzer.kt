package com.bassplayer

import android.os.Handler
import android.os.HandlerThread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * A Bass-AnalyzerV2.html élő mérésének 1:1 portja, folyamatos PCM bemenettel.
 *
 * Web Audio AnalyserNode:
 *  - fftSize = 8192
 *  - smoothingTimeConstant = 0.8 (a magnitúdón, frame-enként)
 *  - Blackman-ablak (alpha = 0.16)
 *  - magnitúdó 1/fftSize normalizálással, dB = 20*log10(mag)
 *  - minBin = floor(25 * fftSize / sampleRate)
 *  - maxBin = floor(120 * fftSize / sampleRate)
 *  - csúcs > -58 dB, parabolikus interpoláció,
 *    elfogadás 20..140 Hz, 5 mintás medián, HUD max 100 ms-onként
 *  - 30 fps (mint a HTML VIZ_INTERVAL = 1000/30)
 */
class SpectrumAnalyzer(
    initialSampleRate: Int,
    private val listener: (FloatArray, Float, String) -> Unit
) {

    companion object {
        private const val FFT_SIZE = 8192
        private const val HALF = FFT_SIZE / 2
        private const val MIN_HZ = 25.0
        private const val MAX_HZ = 120.0
        private const val ACCEPT_MIN = 20.0
        private const val ACCEPT_MAX = 140.0
        private const val THRESHOLD_DB = -58.0
        private const val SMOOTHING = 0.8
        private const val HISTORY = 5
        private const val HUD_INTERVAL_MS = 100L
        private const val FRAME_MS = 1000L / 30L
    }

    /** A dekódolt PCM tényleges sample rate-je. */
    @Volatile
    var sampleRate: Int = initialSampleRate.coerceIn(8000, 192000)

    private val window = DoubleArray(FFT_SIZE)
    private val ring = DoubleArray(FFT_SIZE)
    private val windowed = DoubleArray(FFT_SIZE)
    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)
    private val smoothed = DoubleArray(HALF)
    private val spectrum = FloatArray(HALF)
    private val historyWindow = DoubleArray(HISTORY)
    private val sorted = DoubleArray(HISTORY)
    private val noteNames = arrayOf(
        "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
    )

    private var ringPos = 0
    private var totalSamples = 0L
    private var lastHud = 0L
    private var historyCount = 0
    private var historyPos = 0
    private var lastHz = 0f
    private var lastNote = "--"

    @Volatile
    private var running = false
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    init {
        // Web Audio / Chromium Blackman-ablak, alpha = 0.16
        for (i in 0 until FFT_SIZE) {
            val angle = 2.0 * PI * i / FFT_SIZE
            window[i] = 0.42 - 0.5 * cos(angle) + 0.08 * cos(2.0 * angle)
        }
    }

    /** 30 fps-es elemző szál indítása (a HTML VIZ_INTERVAL-jának megfelelően). */
    fun start() {
        if (running) return
        running = true

        val thread = HandlerThread("BassSpectrum").also { it.start() }
        workerThread = thread
        val handler = Handler(thread.looper)
        workerHandler = handler

        val tick = object : Runnable {
            override fun run() {
                if (!running) return
                try {
                    tick()
                } catch (_: Exception) {
                }
                handler.postDelayed(this, FRAME_MS)
            }
        }
        handler.post(tick)
    }

    fun stop() {
        running = false
        val handler = workerHandler
        workerHandler = null
        handler?.removeCallbacksAndMessages(null)
        val thread = workerThread
        workerThread = null
        thread?.quitSafely()
    }

    /** Mono float PCM minták hozzáfűzése ([-1, 1] tartomány). */
    @Synchronized
    fun pushSamples(samples: FloatArray, count: Int, sampleRate: Int) {
        if (sampleRate in 8000..192000) this.sampleRate = sampleRate
        val n = min(count, samples.size)
        for (i in 0 until n) {
            ring[ringPos] = samples[i].toDouble()
            ringPos = (ringPos + 1) % FFT_SIZE
            totalSamples++
        }
    }

    /** Captures decoded player PCM (the same pre-output signal analyzed by Web Audio). */
    fun pushWaveform(waveform: ByteArray) {
        val samples = FloatArray(waveform.size)
        for (i in waveform.indices) samples[i] = ((waveform[i].toInt() and 0xff) - 128) / 128f
        pushSamples(samples, samples.size, sampleRate)
    }

    @Synchronized
    fun reset() {
        ring.fill(0.0)
        windowed.fill(0.0)
        smoothed.fill(0.0)
        spectrum.fill(-200f)
        historyWindow.fill(0.0)
        ringPos = 0
        totalSamples = 0
        lastHud = 0
        historyCount = 0
        historyPos = 0
        lastHz = 0f
        lastNote = "--"
    }

    /** Egy elemzési frame (30 fps-enként hívja a szál; tesztből is hívható). */
    fun tick() {
        synchronized(this) {
            if (totalSamples < FFT_SIZE) return
            val start = ringPos
            for (i in 0 until FFT_SIZE) {
                windowed[i] = ring[(start + i) % FFT_SIZE] * window[i]
            }
        }
        compute()
    }

    private fun compute() {
        val sr = sampleRate.coerceIn(8000, 192000)
        val now = System.currentTimeMillis()

        for (i in 0 until FFT_SIZE) {
            re[i] = windowed[i]
            im[i] = 0.0
        }

        fft(re, im)

        val scale = 1.0 / FFT_SIZE
        for (k in 0 until HALF) {
            val magnitude = hypot(re[k], im[k]) * scale
            // Web Audio: smoothed = 0.8 * smoothed + 0.2 * |X[k]|
            val value = SMOOTHING * smoothed[k] + (1.0 - SMOOTHING) * magnitude
            smoothed[k] = value
            spectrum[k] = if (value > 0.0) {
                (20.0 * log10(value)).toFloat()
            } else {
                -200f
            }
        }

        val minBin = max(0, floor(MIN_HZ * FFT_SIZE / sr).toInt())
        val maxBin = min(HALF - 1, floor(MAX_HZ * FFT_SIZE / sr).toInt())

        var maxDb = -999f
        var peakBin = -1
        for (k in minBin..maxBin) {
            val db = spectrum[k]
            if (db > maxDb) {
                maxDb = db
                peakBin = k
            }
        }

        if (maxDb > THRESHOLD_DB && peakBin > 0 && peakBin < HALF - 1) {
            val dL = spectrum[peakBin - 1].toDouble()
            val dM = spectrum[peakBin].toDouble()
            val dR = spectrum[peakBin + 1].toDouble()
            // JS: (dL - 2*dM + dR || 1) -> 0 esetén 1 a nevező
            val rawDenominator = dL - 2.0 * dM + dR
            val denominator = if (rawDenominator == 0.0 || rawDenominator.isNaN()) {
                1.0
            } else {
                rawDenominator
            }
            val delta = 0.5 * (dL - dR) / denominator
            val strongestFrequency = (peakBin + delta) * sr / FFT_SIZE
            // Match the HTML live analyser: report the strongest peak.
            val liveFrequency = strongestFrequency

            if (liveFrequency >= ACCEPT_MIN && liveFrequency <= ACCEPT_MAX) {
                historyWindow[historyPos] = liveFrequency
                historyPos = (historyPos + 1) % HISTORY
                if (historyCount < HISTORY) historyCount++

                for (i in 0 until historyCount) {
                    sorted[i] = historyWindow[
                        (historyPos - historyCount + i + HISTORY) % HISTORY
                    ]
                }
                val values = sorted.copyOf(historyCount)
                values.sort()
                val stable = values[historyCount / 2]

                if (now - lastHud >= HUD_INTERVAL_MS) {
                    lastHud = now
                    lastHz = stable.toFloat()
                    lastNote = noteName(stable)
                }
            }
        } else {
            // Nincs szubfrekvencia: a HTML ilyenkor üríti a mintákat.
            historyCount = 0
            historyPos = 0
            lastHz = 0f
            lastNote = "--"
            lastHud = 0
        }

        listener(spectrum.copyOf(), lastHz, lastNote)
    }

    /** A HTML freqToNote() formátuma: "A1 (+4ct)" / "F#2 (-3ct)". */
    private fun noteName(frequency: Double): String {
        val midi = 69.0 + 12.0 * log2(frequency / 440.0)
        val roundedMidi = round(midi).toInt()
        val cents = round((midi - roundedMidi) * 100.0).toInt()
        val name = noteNames[((roundedMidi % 12) + 12) % 12]
        val octave = Math.floorDiv(roundedMidi, 12) - 1
        val centsText = if (cents > 0) "+$cents" else "$cents"
        return "$name$octave (${centsText}ct)"
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val n = real.size

        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var temp = real[i]
                real[i] = real[j]
                real[j] = temp
                temp = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = temp
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        var length = 2
        while (length <= n) {
            val half = length shr 1
            val angle = -2.0 * PI / length
            val stepReal = cos(angle)
            val stepImaginary = kotlin.math.sin(angle)

            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                for (m in 0 until half) {
                    val a = i + m
                    val b = a + half
                    val tr = wr * real[b] - wi * imaginary[b]
                    val ti = wr * imaginary[b] + wi * real[b]
                    real[b] = real[a] - tr
                    imaginary[b] = imaginary[a] - ti
                    real[a] += tr
                    imaginary[a] += ti

                    val nextReal = wr * stepReal - wi * stepImaginary
                    wi = wr * stepImaginary + wi * stepReal
                    wr = nextReal
                }
                i += length
            }
            length = length shl 1
        }
    }
}

