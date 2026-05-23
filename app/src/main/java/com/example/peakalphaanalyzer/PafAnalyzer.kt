package com.example.peakalphaanalyzer
package com.example.pafanalyzer

import android.util.Log
import kotlin.math.*
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.lang.StringBuilder
import java.util.concurrent.Executors

/**
 * PafAnalyzer.kt
 *
 * Self-contained analyzer that:
 * - Loads Muse-style CSV files (or simple CSV with RAW_TP9/RAW_TP10 columns)
 * - Computes Welch PSD, spectrogram, and per-window PSDs
 * - Returns three IAF estimates: argmax, parabolic-refined argmax, center-of-gravity (CoG)
 * - Provides a rapid-IAF quick estimator (median of per-window argmaxes)
 * - Detects an alpha ridge in the spectrogram and computes continuity
 * - Exposes diagnostics (SNR, prominence, peak width, per-window PAFs, percent clean windows)
 *
 * This file is intentionally self-contained and does not require external libraries.
 *
 * NOTE: For production use, replace the simple FFT implementation with a tested DSP library.
 */

data class IafCandidates(
    val argmaxHz: Double,
    val parabolicHz: Double,
    val cogHz: Double,
    val rapidIafHz: Double,
    val snr: Double,
    val peakProminence: Double,
    val peakWidthHz: Double,
    val percentWindowsClean: Double,
    val pafMean: Double,
    val pafStd: Double
)

data class WindowDiag(val pafHz: Double, val isClean: Boolean, val hfRatio: Double, val accMax: Double)

data class SpectrogramResult(
    val times: DoubleArray,
    val freqs: DoubleArray,
    val spectrogram: Array<DoubleArray>, // [time][freq]
    val ridgeFreqs: DoubleArray,
    val ridgeContinuity: Double
)

class PafAnalyzer(private val sampleRate: Double = 256.0) {

    private val TAG = "PafAnalyzer"

    // -------------------------
    // CSV loading utilities
    // -------------------------
    data class CsvLoadResult(val samples: DoubleArray, val timestamps: DoubleArray, val meta: Map<String, String>)

    /**
     * Load a Muse-style CSV or a CSV that contains RAW_TP9 and RAW_TP10 columns.
     * The function will try to find RAW_TP9/RAW_TP10 or TP9/TP10 columns and average them.
     * If only one channel is present, it will use that channel.
     */
    fun loadMuseCsv(file: File): CsvLoadResult {
        val br = BufferedReader(FileReader(file))
        val headerLine = br.readLine() ?: throw IllegalArgumentException("Empty file")
        val headers = headerLine.split(",").map { it.trim() }
        val colIndex = headers.mapIndexed { idx, name -> name to idx }.toMap()

        // find timestamp column (TimeStamp or Timestamp or time)
        val tsIdx = colIndex["TimeStamp"] ?: colIndex["Timestamp"] ?: colIndex["time"] ?: -1

        // find RAW_TP9/RAW_TP10 or TP9/TP10
        val tp9Names = listOf("RAW_TP9", "TP9", "TP9_RAW", "TP9_RAW_V")
        val tp10Names = listOf("RAW_TP10", "TP10", "TP10_RAW", "TP10_RAW_V")

        val tp9Idx = tp9Names.mapNotNull { colIndex[it] }.firstOrNull() ?: -1
        val tp10Idx = tp10Names.mapNotNull { colIndex[it] }.firstOrNull() ?: -1

        val samples = ArrayList<Double>()
        val times = ArrayList<Double>()

        br.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val parts = line.split(",")
            try {
                val t = if (tsIdx >= 0 && tsIdx < parts.size) parts[tsIdx].toDoubleOrNull() ?: Double.NaN else Double.NaN
                val v = when {
                    tp9Idx >= 0 && tp9Idx < parts.size && tp10Idx >= 0 && tp10Idx < parts.size -> {
                        val a = parts[tp9Idx].toDoubleOrNull() ?: 0.0
                        val b = parts[tp10Idx].toDoubleOrNull() ?: 0.0
                        (a + b) / 2.0
                    }
                    tp9Idx >= 0 && tp9Idx < parts.size -> parts[tp9Idx].toDoubleOrNull() ?: 0.0
                    tp10Idx >= 0 && tp10Idx < parts.size -> parts[tp10Idx].toDoubleOrNull() ?: 0.0
                    else -> {
                        // fallback: try first numeric column
                        val num = parts.mapNotNull { it.toDoubleOrNull() }
                        if (num.isNotEmpty()) num[0] else 0.0
                    }
                }
                samples.add(v)
                times.add(if (t.isNaN()) times.size.toDouble() / sampleRate else t)
            } catch (ex: Exception) {
                // ignore parse errors
            }
        }
        br.close()
        val meta = mapOf("source" to file.name)
        return CsvLoadResult(samples.toDoubleArray(), times.toDoubleArray(), meta)
    }

    // -------------------------
    // FFT and Welch PSD
    // -------------------------
    // Simple FFT implementation (Cooley-Tukey). Input length must be power of two.
    // Returns complex arrays as pairs of DoubleArray (real, imag)
    private fun fftReal(input: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = input.size
        val levels = (ln(n.toDouble()) / ln(2.0)).roundToInt()
        if ((1 shl levels) != n) throw IllegalArgumentException("FFT length must be power of two")
        val real = input.copyOf()
        val imag = DoubleArray(n) { 0.0 }

        // bit-reverse
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tmp = real[i]; real[i] = real[j]; real[j] = tmp
            }
        }

        var size = 2
        while (size <= n) {
            val half = size / 2
            val tableStep = n / size
            var k = 0
            while (k < n) {
                var i = 0
                while (i < half) {
                    val l = k + i
                    val r = l + half
                    val angle = -2.0 * Math.PI * i / size
                    val wr = cos(angle)
                    val wi = sin(angle)
                    val tr = wr * real[r] - wi * imag[r]
                    val ti = wr * imag[r] + wi * real[r]
                    real[r] = real[l] - tr
                    imag[r] = imag[l] - ti
                    real[l] += tr
                    imag[l] += ti
                    i++
                }
                k += size
            }
            size = size shl 1
        }
        return Pair(real, imag)
    }

    // Compute PSD for a single window using FFT
    private fun psdFromWindow(window: DoubleArray, fs: Double): Pair<DoubleArray, DoubleArray> {
        // zero-pad to next power of two for frequency resolution
        val n = window.size
        val nfft = nextPow2(n)
        val padded = DoubleArray(nfft) { 0.0 }
        for (i in 0 until n) padded[i] = window[i] * hamming(n)[i]
        val (real, imag) = fftReal(padded)
        val half = nfft / 2
        val psd = DoubleArray(half)
        val freqs = DoubleArray(half)
        val scale = 1.0 / (fs * n)
        for (i in 0 until half) {
            val re = real[i]
            val im = imag[i]
            psd[i] = (re * re + im * im) * scale
            freqs[i] = i.toDouble() * fs / nfft
        }
        return Pair(freqs, psd)
    }

    private fun nextPow2(n: Int): Int {
        var v = 1
        while (v < n) v = v shl 1
        return v
    }

    private fun hamming(n: Int): DoubleArray {
        val w = DoubleArray(n)
        for (i in 0 until n) {
            w[i] = 0.54 - 0.46 * cos(2.0 * Math.PI * i / (n - 1))
        }
        return w
    }

    /**
     * computeWelch: compute averaged PSD using Welch's method
     * - windowSec: length of each segment in seconds
     * - overlap: fraction 0..1
     */
    fun computeWelch(signal: DoubleArray, fs: Double, windowSec: Double = 3.0, overlap: Double = 0.5): Pair<DoubleArray, DoubleArray> {
        val winN = max(4, (windowSec * fs).roundToInt())
        val step = max(1, (winN * (1.0 - overlap)).roundToInt())
        val segments = ArrayList<Pair<DoubleArray, DoubleArray>>()
        var i = 0
        while (i + winN <= signal.size) {
            val window = signal.copyOfRange(i, i + winN)
            val (freqs, psd) = psdFromWindow(window, fs)
            segments.add(Pair(freqs, psd))
            i += step
        }
        if (segments.isEmpty()) {
            // fallback: single window
            val (freqs, psd) = psdFromWindow(signal, fs)
            return Pair(freqs, psd)
        }
        val freqs = segments[0].first
        val avg = DoubleArray(freqs.size) { 0.0 }
        for (seg in segments) {
            val p = seg.second
            for (k in p.indices) avg[k] += p[k]
        }
        for (k in avg.indices) avg[k] /= segments.size.toDouble()
        return Pair(freqs, avg)
    }

    // -------------------------
    // IAF candidate computations
    // -------------------------
    // Parabolic interpolation around peak index
    fun parabolicRefine(freqs: DoubleArray, psd: DoubleArray, peakIdx: Int): Double {
        val i = peakIdx
        if (i <= 0 || i >= psd.size - 1) return freqs[i]
        val y0 = psd[i - 1]; val y1 = psd[i]; val y2 = psd[i + 1]
        val denom = (y0 - 2.0 * y1 + y2)
        if (abs(denom) < 1e-12) return freqs[i]
        val delta = 0.5 * (y0 - y2) / denom
        val df = freqs[1] - freqs[0]
        return freqs[i] + delta * df
    }

    // Center of Gravity in alpha band
    fun centerOfGravity(freqs: DoubleArray, psd: DoubleArray, lowHz: Double = 8.0, highHz: Double = 13.0): Double {
        var num = 0.0; var den = 0.0
        for (i in freqs.indices) {
            val f = freqs[i]
            if (f >= lowHz && f <= highHz) {
                num += f * psd[i]
                den += psd[i]
            }
        }
        return if (den <= 0.0) (lowHz + highHz) / 2.0 else num / den
    }

    // Simple SNR and prominence estimate
    fun computePeakMetrics(freqs: DoubleArray, psd: DoubleArray, peakIdx: Int, lowHz: Double = 8.0, highHz: Double = 13.0): Pair<Double, Double> {
        val peakPower = psd[peakIdx]
        var alphaSum = 0.0; var alphaCount = 0
        for (i in freqs.indices) {
            val f = freqs[i]
            if (f >= lowHz && f <= highHz && i != peakIdx) {
                alphaSum += psd[i]; alphaCount++
            }
        }
        val meanAlpha = if (alphaCount > 0) alphaSum / alphaCount else 1e-12
        val snr = if (meanAlpha > 0) peakPower / meanAlpha else Double.POSITIVE_INFINITY

        var edgeSum = 0.0; var edgeCount = 0
        for (i in freqs.indices) {
            val f = freqs[i]
            if ((f >= lowHz && f <= lowHz + 1.0) || (f >= highHz - 1.0 && f <= highHz)) {
                edgeSum += psd[i]; edgeCount++
            }
        }
        val baseline = if (edgeCount > 0) edgeSum / edgeCount else meanAlpha
        val prominence = peakPower - baseline
        return Pair(snr, prominence)
    }

    // Peak width (approx half-power width)
    fun estimatePeakWidth(freqs: DoubleArray, psd: DoubleArray, peakIdx: Int): Double {
        val peak = psd[peakIdx]
        val half = peak / 2.0
        var leftIdx = peakIdx; var rightIdx = peakIdx
        while (leftIdx > 0 && psd[leftIdx] > half) leftIdx--
        while (rightIdx < psd.size - 1 && psd[rightIdx] > half) rightIdx++
        return (freqs[rightIdx] - freqs[leftIdx]).coerceAtLeast(0.0)
    }

    /**
     * computeIafCandidates:
     * - freqs, psd: averaged PSD (from computeWelch)
     * - perWindowPsd: optional list of per-window (freqs, psd) pairs for rapid-IAF and diagnostics
     */
    fun computeIafCandidates(
        freqs: DoubleArray,
        psd: DoubleArray,
        perWindowPsd: List<Pair<DoubleArray, DoubleArray>>? = null,
        lowHz: Double = 8.0,
        highHz: Double = 13.0
    ): Pair<IafCandidates, List<WindowDiag>> {
        var peakIdx = -1; var peakVal = Double.NEGATIVE_INFINITY
        for (i in freqs.indices) {
            val f = freqs[i]
            if (f >= lowHz && f <= highHz && psd[i] > peakVal) {
                peakVal = psd[i]; peakIdx = i
            }
        }
        if (peakIdx < 0) {
            val fallback = IafCandidates(lowHz, lowHz, (lowHz + highHz) / 2.0, lowHz, 0.0, 0.0, 0.0, 0.0, lowHz, 0.0)
            return Pair(fallback, emptyList())
        }
        val argmaxHz = freqs[peakIdx]
        val parabolicHz = parabolicRefine(freqs, psd, peakIdx)
        val cogHz = centerOfGravity(freqs, psd, lowHz, highHz)
        val (snr, prominence) = computePeakMetrics(freqs, psd, peakIdx, lowHz, highHz)
        val widthHz = estimatePeakWidth(freqs, psd, peakIdx)

        // per-window diagnostics
        val windowDiags = ArrayList<WindowDiag>()
        var percentClean = 1.0
        var pafList = ArrayList<Double>()
        if (perWindowPsd != null && perWindowPsd.isNotEmpty()) {
            var cleanCount = 0
            for (pw in perWindowPsd) {
                val wf = pw.first; val wp = pw.second
                var wi = -1; var wv = Double.NEGATIVE_INFINITY
                for (i in wf.indices) {
                    val f = wf[i]
                    if (f >= lowHz && f <= highHz && wp[i] > wv) { wv = wp[i]; wi = i }
                }
                val wPeak = if (wi >= 0) wf[wi] else Double.NaN
                // compute HF ratio (30-80 Hz) as contamination proxy
                var hfSum = 0.0; var total = 0.0
                for (i in wf.indices) {
                    val f = wf[i]
                    val p = wp[i]
                    if (f >= 30.0 && f <= 80.0) hfSum += p
                    if (f >= 1.0 && f <= 80.0) total += p
                }
                val hfRatio = if (total > 0) hfSum / total else 0.0
                val accMax = 0.0 // placeholder: if accelerometer data available, compute here
                val isClean = hfRatio < 0.2 && accMax < 1.0
                if (isClean) cleanCount++
                if (!wPeak.isNaN()) pafList.add(wPeak)
                windowDiags.add(WindowDiag(if (wPeak.isNaN()) Double.NaN else wPeak, isClean, hfRatio, accMax))
            }
            percentClean = if (windowDiags.isNotEmpty()) cleanCount.toDouble() / windowDiags.size.toDouble() else 0.0
        }

        val rapidIaf = if (pafList.isNotEmpty()) {
            pafList.sorted()[pafList.size / 2]
        } else argmaxHz

        val pafMean = if (pafList.isNotEmpty()) pafList.average() else argmaxHz
        val pafStd = if (pafList.size > 1) {
            val mean = pafMean
            sqrt(pafList.map { (it - mean).pow(2) }.sum() / (pafList.size - 1))
        } else 0.0

        val candidates = IafCandidates(
            argmaxHz = argmaxHz,
            parabolicHz = parabolicHz,
            cogHz = cogHz,
            rapidIafHz = rapidIaf,
            snr = snr,
            peakProminence = prominence,
            peakWidthHz = widthHz,
            percentWindowsClean = percentClean,
            pafMean = pafMean,
            pafStd = pafStd
        )
        return Pair(candidates, windowDiags)
    }

    // -------------------------
    // Rapid IAF from raw signal
    // -------------------------
    fun rapidIafFromSignal(samples: DoubleArray, fs: Double = sampleRate, totalSec: Int = 60): Double {
        val nNeeded = min(samples.size, (fs * totalSec).toInt())
        if (nNeeded < (fs * 2).toInt()) return Double.NaN
        val data = samples.copyOfRange(0, nNeeded)
        val winSec = 1.0
        val winN = max(4, (fs * winSec).toInt())
        val step = max(1, winN / 2)
        val windowPeaks = mutableListOf<Double>()
        var i = 0
        while (i + winN <= data.size) {
            val window = data.copyOfRange(i, i + winN)
            val (freqs, psd) = computeWelch(window, fs, winSec, 0.0)
            var bestF = Double.NaN; var bestP = Double.NEGATIVE_INFINITY
            for (j in freqs.indices) {
                if (freqs[j] >= 8.0 && freqs[j] <= 13.0 && psd[j] > bestP) { bestP = psd[j]; bestF = freqs[j] }
            }
            if (!bestF.isNaN()) windowPeaks.add(bestF)
            i += step
        }
        if (windowPeaks.isEmpty()) return Double.NaN
        windowPeaks.sort()
        return windowPeaks[windowPeaks.size / 2]
    }

    // -------------------------
    // Spectrogram and ridge detection
    // -------------------------
    fun computeSpectrogramAndRidge(signal: DoubleArray, fs: Double = sampleRate, windowSec: Double = 2.0, stepSec: Double = 0.2): SpectrogramResult {
        val winN = max(4, (windowSec * fs).toInt())
        val stepN = max(1, (stepSec * fs).toInt())
        val frames = ArrayList<DoubleArray>()
        val times = ArrayList<Double>()
        var i = 0
        while (i + winN <= signal.size) {
            val frame = signal.copyOfRange(i, i + winN)
            val (freqs, psd) = computeWelch(frame, fs, windowSec, 0.0)
            frames.add(psd)
            times.add(i.toDouble() / fs)
            i += stepN
        }
        if (frames.isEmpty()) throw IllegalArgumentException("Signal too short for spectrogram")
        val freqs = computeWelch(signal.copyOfRange(0, winN), fs, windowSec, 0.0).first
        val spec = Array(frames.size) { DoubleArray(freqs.size) }
        for (t in frames.indices) for (f in freqs.indices) spec[t][f] = frames[t][f]

        val ridgeFreqs = DoubleArray(spec.size)
        for (t in spec.indices) {
            var bestIdx = -1; var bestP = Double.NEGATIVE_INFINITY
            for (f in freqs.indices) {
                val fr = freqs[f]
                if (fr >= 6.0 && fr <= 30.0) {
                    if (spec[t][f] > bestP) { bestP = spec[t][f]; bestIdx = f }
                }
            }
            ridgeFreqs[t] = if (bestIdx >= 0) freqs[bestIdx] else Double.NaN
        }

        val medianRidge = ridgeFreqs.filter { !it.isNaN() && it in 6.0..30.0 }.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
        val within = ridgeFreqs.count { !it.isNaN() && abs(it - medianRidge) <= 0.5 && it >= 8.0 && it <= 13.0 }
        val continuity = within.toDouble() / ridgeFreqs.size.toDouble()

        return SpectrogramResult(times.toDoubleArray(), freqs, spec, ridgeFreqs, continuity)
    }

    // -------------------------
    // Diagnostics CSV writer
    // -------------------------
    fun writeDiagnosticsCsv(path: String, freqs: DoubleArray, psd: DoubleArray, windowDiags: List<WindowDiag>) {
        val file = File(path)
        file.printWriter().use { out ->
            out.println("freq,psd")
            for (i in freqs.indices) out.println("${freqs[i]},${psd[i]}")
            out.println()
            out.println("windowIndex,pafHz,isClean,hfRatio,accMax")
            for ((idx, w) in windowDiags.withIndex()) {
                out.println("$idx,${w.pafHz},${w.isClean},${w.hfRatio},${w.accMax}")
            }
        }
    }

    // -------------------------
    // Convenience runner: full analysis pipeline
    // -------------------------
    data class FullAnalysisResult(
        val candidates: IafCandidates,
        val windowDiags: List<WindowDiag>,
        val freqs: DoubleArray,
        val psd: DoubleArray,
        val spectrogram: SpectrogramResult?
    )

    /**
     * analyzeFile: runs the full pipeline on a CSV file and returns results.
     * - computeWelch on the whole signal
     * - compute per-window PSDs (same windows used for Welch)
     * - compute IAF candidates
     * - compute spectrogram and ridge
     */
    fun analyzeFile(file: File): FullAnalysisResult {
        val csv = loadMuseCsv(file)
        val signal = csv.samples
        // trim edges
        val trimmed = if (signal.size > (sampleRate * 2).toInt()) signal.copyOfRange((sampleRate * 1).toInt(), signal.size - (sampleRate * 1).toInt()) else signal
        val (freqs, psd) = computeWelch(trimmed, sampleRate, 3.0, 0.5)

        // compute per-window PSDs for diagnostics
        val perWindow = ArrayList<Pair<DoubleArray, DoubleArray>>()
        val winN = (3.0 * sampleRate).toInt()
        val step = (winN * 0.5).toInt()
        var i = 0
        while (i + winN <= trimmed.size) {
            val w = trimmed.copyOfRange(i, i + winN)
            val (wf, wp) = psdFromWindow(w, sampleRate)
            perWindow.add(Pair(wf, wp))
            i += step
        }

        val (candidates, windowDiags) = computeIafCandidates(freqs, psd, perWindow)
        val spectrogram = try {
            computeSpectrogramAndRidge(trimmed, sampleRate, 2.0, 0.2)
        } catch (ex: Exception) {
            Log.w(TAG, "Spectrogram failed: ${ex.message}")
            null
        }

        return FullAnalysisResult(candidates, windowDiags, freqs, psd, spectrogram)
    }
}
