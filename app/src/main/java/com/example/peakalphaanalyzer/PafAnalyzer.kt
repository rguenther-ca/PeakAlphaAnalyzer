package com.example.peakalphaanalyzer

import com.opencsv.CSVReader
import org.apache.commons.math3.analysis.interpolation.SplineInterpolator
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

/**
 * PAFAnalyzer.kt
 *
 * Parses Muse-style CSV input, resamples posterior channels, computes Welch PSD,
 * extracts multiple IAF estimates (argmax, parabolic-refined, CoG, rapid-IAF),
 * computes confidence and returns diagnostics.
 *
 * Dependencies required in build.gradle:
 * implementation 'com.opencsv:opencsv:5.7.1'
 * implementation 'org.apache.commons:commons-math3:3.6.1'
 */
object PafAnalyzer {

    class CsvFormatException(msg: String) : Exception(msg)
    private val transformer = FastFourierTransformer(DftNormalization.STANDARD)

    // Tunable parameters (modifiable from UI)
    var welchWindowSec: Double = 6.0
    var welchSubWindowSec: Double = 3.0
    var welchOverlap: Double = 0.25

    // Artifact thresholds
    var accelThresholdG: Double = 0.6
    var hfRatioThreshold: Double = 0.6

    // Muse CSV column indices (adjust if your CSV differs)
    private const val IDX_RAW_TP9 = 21
    private const val IDX_RAW_TP10 = 24
    private const val IDX_HEADBANDON = 37
    private const val IDX_ACC_X = 27
    private const val IDX_ACC_Y = 28
    private const val IDX_ACC_Z = 29

    data class IafResult(
        val fs: Double,
        val duration: Double,
        val iafArgmaxHz: Double,
        val iafParabolicHz: Double,
        val iafCogHz: Double,
        val rapidIafHz: Double,
        val chosenIafHz: Double,
        val iafMethod: String,
        val confidence: Double,
        val peakPower: Double,
        val alphaMeanPower: Double,
        val peakWidthHz: Double,
        val pafStdAcrossWindows: Double,
        val rawPosterior: DoubleArray,
        val freqs: DoubleArray,
        val psd: DoubleArray
    ) {
        fun format(): String = buildString {
            append("fs=${"%.1f".format(fs)} Hz, dur=${"%.1f".format(duration)} s\n")
            append("Chosen IAF: ${"%.2f".format(chosenIafHz)} Hz (${iafMethod})\n")
            append("Confidence: ${"%.2f".format(confidence)} (0–1)\n")
            append("Peak power: ${"%.6f".format(peakPower)}, alpha mean: ${"%.6f".format(alphaMeanPower)}\n")
            append("Peak width: ${"%.2f".format(peakWidthHz)} Hz, PAF SD across windows: ${"%.3f".format(pafStdAcrossWindows)}\n")
        }
    }

    /**
     * Analyze a Muse CSV InputStream and return IAF result.
     */
    fun analyze(stream: java.io.InputStream): IafResult {
        val reader = CSVReader(InputStreamReader(stream))
        val all = reader.readAll()
        if (all.size <= 1) throw CsvFormatException("Empty CSV")

        // Keep only rows where HeadBandOn == 1
        val rows = all.drop(1).filter { it.size > IDX_HEADBANDON && it[IDX_HEADBANDON] == "1" }
        if (rows.size < 2) throw CsvFormatException("No valid segment.")

        // Parse timestamps (first column)
        val rawTs = rows.mapNotNull { it[0].takeIf { t -> t.isNotBlank() } }
        val timesAll = parseTimes(rawTs)

        // Fixed Muse RAW sampling rate
        val fs = 256.0

        // Trim 10 seconds at start/end to avoid movement
        val startIdx = timesAll.indexOfFirst { it >= timesAll.first() + 10.0 }
        val endIdx = timesAll.indexOfLast { it <= timesAll.last() - 10.0 }
        if (startIdx < 0 || endIdx <= startIdx) throw CsvFormatException("Invalid interval after trimming.")

        val tSeg = timesAll.subList(startIdx, endIdx)

        // Extract RAW posterior channels
        val tp9All = rows.map { it.getOrNull(IDX_RAW_TP9)?.toDoubleOrNull() ?: Double.NaN }
        val tp10All = rows.map { it.getOrNull(IDX_RAW_TP10)?.toDoubleOrNull() ?: Double.NaN }

        val posteriorSeg = tp9All.zip(tp10All) { a, b -> (a + b) / 2.0 }
            .subList(startIdx, endIdx)
            .toDoubleArray()

        // Extract accelerometer columns for artifact detection
        val accXAll = rows.map { it.getOrNull(IDX_ACC_X)?.toDoubleOrNull() ?: 0.0 }.subList(startIdx, endIdx)
        val accYAll = rows.map { it.getOrNull(IDX_ACC_Y)?.toDoubleOrNull() ?: 0.0 }.subList(startIdx, endIdx)
        val accZAll = rows.map { it.getOrNull(IDX_ACC_Z)?.toDoubleOrNull() ?: 0.0 }.subList(startIdx, endIdx)

        // Pair timestamps with posterior signal and remove duplicate timestamps
        val paired = tSeg.zip(posteriorSeg.toList())
            .distinctBy { it.first }
            .sortedBy { it.first }

        if (paired.size < 2) throw CsvFormatException("Not enough unique timestamps.")

        val tUnique = paired.map { it.first }.toDoubleArray()
        val yUnique = paired.map { it.second }.toDoubleArray()

        // Resample to uniform 256 Hz grid (spline interpolation)
        val spline = SplineInterpolator().interpolate(tUnique, yUnique)
        val t0 = tUnique.first()
        val t1 = tUnique.last()
        val nUniform = ((t1 - t0) * fs).toInt().coerceAtLeast(2)

        val y = DoubleArray(nUniform) { k ->
            val tk = t0 + k / fs
            spline.value(tk)
        }

        val duration = t1 - t0

        // Map accelerometer magnitude to resampled grid (nearest packet)
        val accMag = DoubleArray(nUniform) { 0.0 }
        val accTimes = tSeg.toDoubleArray()
        val accLen = accXAll.size
        for (k in 0 until nUniform) {
            val tk = t0 + k / fs
            val idx = accTimes.binarySearch(tk).let { if (it >= 0) it else max(0, -it - 2) }.coerceIn(0, accLen - 1)
            val ax = accXAll[idx]
            val ay = accYAll[idx]
            val az = accZAll[idx]
            accMag[k] = sqrt(ax * ax + ay * ay + az * az)
        }

        // Compute Welch PSD and per-window PAFs and flags
        val (freqs, psdAvg, windowPafs, windowFlags) = computeWelchAndWindowPafs(y, fs, accMag)

        // Recompute clean PSD average using only clean windows if available
        val cleanIndices = windowFlags.mapIndexedNotNull { idx, ok -> if (ok) idx else null }
        val psdClean = if (cleanIndices.isEmpty()) {
            psdAvg
        } else {
            averagePsdFromWindows(y, fs, cleanIndices)
        }

        // Smooth PSD slightly to stabilize peak detection
        val psdSmoothed = smooth(psdClean, 3)

        // Alpha band indices
        val nfft = nextPow2((fs * welchSubWindowSec).toInt().coerceAtLeast(1))
        val half = nfft / 2
        val freqsArray = DoubleArray(half) { i -> i * fs / nfft }
        val alphaIdxs = freqsArray.indices.filter { freqsArray[it] in 8.0..13.0 }
        if (alphaIdxs.isEmpty()) throw CsvFormatException("No alpha frequency bins available.")

        // Argmax (raw peak bin)
        val peakIdx = alphaIdxs.maxByOrNull { psdSmoothed[it] } ?: alphaIdxs.first()
        val argmaxHz = freqsArray[peakIdx]

        // Parabolic refinement around peak
        val parabolicHz = parabolicRefine(freqsArray, psdSmoothed, peakIdx)

        // Center of Gravity
        val cogHz = centerOfGravity(freqsArray, psdSmoothed, 8.0, 13.0)

        // Rapid IAF: median of per-window argmaxes (windowPafs)
        val rapidIaf = if (windowPafs.isNotEmpty()) {
            val sorted = windowPafs.sorted()
            sorted[sorted.size / 2]
        } else argmaxHz

        val peakPower = psdSmoothed[peakIdx]
        val neighbors = alphaIdxs.filter { it != peakIdx }.map { psdSmoothed[it] }
        val alphaMean = if (neighbors.isEmpty()) peakPower else neighbors.average()
        val snr = peakPower / (alphaMean + 1e-12)

        // Peak width (half-power)
        val halfPower = peakPower / 2.0
        var left = peakIdx
        while (left > alphaIdxs.first() && psdSmoothed[left] > halfPower) left--
        var right = peakIdx
        while (right < alphaIdxs.last() && psdSmoothed[right] > halfPower) right++
        val peakWidthHz = (right - left) * (fs / nfft)

        // Prominence (peak vs local ±1 Hz baseline)
        val hzWindow = 1.0
        val leftHz = (argmaxHz - hzWindow).coerceAtLeast(8.0)
        val rightHz = (argmaxHz + hzWindow).coerceAtMost(13.0)
        val localIdxs = freqsArray.indices.filter { freqsArray[it] in leftHz..rightHz && it != peakIdx }
        val localBaseline = if (localIdxs.isEmpty()) alphaMean else localIdxs.map { psdSmoothed[it] }.average()
        val prominence = (peakPower - localBaseline) / (localBaseline + 1e-12)

        // Stability across windows
        val cleanPafs = windowPafs.filterIndexed { idx, _ -> windowFlags[idx] }
        val pafStd = if (cleanPafs.isEmpty()) 0.0 else std(cleanPafs)

        // Decide which IAF to choose for output
        val parabolicValid = !parabolicHz.isNaN() && parabolicHz in 7.0..14.0
        val useParabolic = parabolicValid && prominence >= 0.3
        val useArgmax = !useParabolic && prominence >= 0.2 && peakWidthHz <= 3.0
        val chosenIaf = when {
            useParabolic -> parabolicHz
            useArgmax -> argmaxHz
            else -> cogHz
        }
        val iafMethod = when {
            useParabolic -> "parabolic"
            useArgmax -> "argmax"
            else -> "cog"
        }

        // Confidence scoring
        val snrScore = (snr / 6.0).coerceIn(0.0, 1.0)
        val promScore = (prominence / 3.0).coerceIn(0.0, 1.0)
        val widthScore = (1.0 - (peakWidthHz / 4.0)).coerceIn(0.0, 1.0)
        val stabilityScore = (1.0 - (pafStd / 1.0)).coerceIn(0.0, 1.0)
        val confidence = (0.35 * snrScore + 0.35 * promScore + 0.15 * widthScore + 0.15 * stabilityScore).coerceIn(0.0, 1.0)

        return IafResult(
            fs = fs,
            duration = duration,
            iafArgmaxHz = argmaxHz,
            iafParabolicHz = parabolicHz,
            iafCogHz = cogHz,
            rapidIafHz = rapidIaf,
            chosenIafHz = chosenIaf,
            iafMethod = iafMethod,
            confidence = confidence,
            peakPower = peakPower,
            alphaMeanPower = alphaMean,
            peakWidthHz = peakWidthHz,
            pafStdAcrossWindows = pafStd,
            rawPosterior = y,
            freqs = freqsArray,
            psd = psdSmoothed
        )
    }

    // Parabolic interpolation around a peak index to refine sub-bin frequency
    private fun parabolicRefine(freqs: DoubleArray, psd: DoubleArray, peakIdx: Int): Double {
        val i = peakIdx
        if (i <= 0 || i >= psd.size - 1) return Double.NaN
        val y0 = psd[i - 1]; val y1 = psd[i]; val y2 = psd[i + 1]
        val denom = (y0 - 2.0 * y1 + y2)
        if (abs(denom) < 1e-12) return Double.NaN
        val delta = 0.5 * (y0 - y2) / denom
        val df = freqs[1] - freqs[0]
        return freqs[i] + delta * df
    }

    // Center of Gravity in alpha band
    private fun centerOfGravity(freqs: DoubleArray, psd: DoubleArray, lowHz: Double = 8.0, highHz: Double = 13.0): Double {
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

    // Compute Welch PSD and per-window PAFs and flags (true = clean)
    private fun computeWelchAndWindowPafs(
        data: DoubleArray,
        fs: Double,
        accMag: DoubleArray
    ): Quadruple<DoubleArray, DoubleArray, List<Double>, List<Boolean>> {

        val nPerSeg = (fs * welchSubWindowSec).toInt().coerceAtLeast(1)
        val nfft = nextPow2(nPerSeg)
        val step = (nPerSeg * (1 - welchOverlap)).toInt().coerceAtLeast(1)

        val win = DoubleArray(nPerSeg) { i -> 0.54 - 0.46 * cos(2 * Math.PI * i / (nPerSeg - 1)) }
        val winPow = win.sumOf { it * it }
        val half = nfft / 2

        val psdAcc = DoubleArray(half) { 0.0 }
        val windowPafs = mutableListOf<Double>()
        val windowFlags = mutableListOf<Boolean>()
        var count = 0
        var off = 0

        while (off + nPerSeg <= data.size) {
            val seg = DoubleArray(nPerSeg) { j -> data[off + j] * win[j] }
            val padded = seg.copyOf(nfft)
            val spec = transformer.transform(padded, TransformType.FORWARD)

            val psdWindow = DoubleArray(half) { i ->
                val c = spec[i]
                (c.real * c.real + c.imaginary * c.imaginary) / (fs * winPow)
            }

            val freqs = DoubleArray(half) { i -> i * fs / nfft }
            val hfIdxs = freqs.indices.filter { freqs[it] in 30.0..80.0 }
            val hfPower = if (hfIdxs.isEmpty()) 0.0 else hfIdxs.sumOf { psdWindow[it] }
            val totalPower = psdWindow.sum()
            val hfRatio = if (totalPower <= 0.0) 0.0 else hfPower / totalPower

            val accWindow = accMag.slice(off until (off + nPerSeg))
            val accMax = accWindow.maxOrNull() ?: 0.0
            val accFlag = accMax < accelThresholdG

            val isClean = accFlag && (hfRatio < hfRatioThreshold)

            for (i in 0 until half) psdAcc[i] += psdWindow[i]

            val alphaIdxs = freqs.indices.filter { freqs[it] in 8.0..13.0 }
            val wPeakIdx = alphaIdxs.maxByOrNull { psdWindow[it] } ?: alphaIdxs.first()
            val wPeakFreq = freqs[wPeakIdx]
            windowPafs.add(wPeakFreq)
            windowFlags.add(isClean)

            count++
            off += step
        }

        if (count == 0) throw CsvFormatException("Not enough data for Welch windows.")

        val psdAvg = psdAcc.map { it / count }.toDoubleArray()
        val freqs = DoubleArray(half) { i -> i * fs / nfft }

        return Quadruple(freqs, psdAvg, windowPafs, windowFlags)
    }

    // Recompute average PSD using only selected clean window indices
    private fun averagePsdFromWindows(data: DoubleArray, fs: Double, cleanWindowIndices: List<Int>): DoubleArray {
        val nPerSeg = (fs * welchSubWindowSec).toInt().coerceAtLeast(1)
        val nfft = nextPow2(nPerSeg)
        val step = (nPerSeg * (1 - welchOverlap)).toInt().coerceAtLeast(1)
        val win = DoubleArray(nPerSeg) { i -> 0.54 - 0.46 * cos(2 * Math.PI * i / (nPerSeg - 1)) }
        val winPow = win.sumOf { it * it }
        val half = nfft / 2

        val psdAcc = DoubleArray(half) { 0.0 }
        var count = 0
        var off = 0
        var wIdx = 0
        while (off + nPerSeg <= data.size) {
            if (cleanWindowIndices.contains(wIdx)) {
                val seg = DoubleArray(nPerSeg) { j -> data[off + j] * win[j] }
                val padded = seg.copyOf(nfft)
                val spec = transformer.transform(padded, TransformType.FORWARD)
                for (i in 0 until half) {
                    val c = spec[i]
                    psdAcc[i] += (c.real * c.real + c.imaginary * c.imaginary) / (fs * winPow)
                }
                count++
            }
            off += step
            wIdx++
        }
        if (count == 0) return DoubleArray(half) { 0.0 }
        return psdAcc.map { it / count }.toDoubleArray()
    }

    // Simple moving average smoother
    private fun smooth(x: DoubleArray, window: Int): DoubleArray {
        if (window <= 1) return x.copyOf()
        val out = DoubleArray(x.size)
        val half = window / 2
        for (i in x.indices) {
            var sum = 0.0
            var cnt = 0
            for (j in (i - half)..(i + half)) {
                if (j in x.indices) {
                    sum += x[j]; cnt++
                }
            }
            out[i] = if (cnt > 0) sum / cnt else x[i]
        }
        return out
    }

    // Helpers
    private fun parseTimes(raw: List<String>): List<Double> {
        return if (raw.first().matches(Regex("[-\\d.]+"))) {
            val nums = raw.map(String::toDouble)
            val dt0 = nums[1] - nums[0]
            if (dt0 in 0.0..0.01) nums.map { it / 1000.0 } else nums
        } else {
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            val inst = raw.map {
                LocalDateTime.parse(it, fmt)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }
            val t0 = inst.first()
            inst.map { (it - t0) / 1000.0 }
        }
    }

    private fun nextPow2(n: Int): Int {
        var v = 1
        while (v < n) v = v shl 1
        return v
    }

    private fun std(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val mean = xs.average()
        val sumsq = xs.sumOf { (it - mean) * (it - mean) }
        return sqrt(sumsq / xs.size)
    }

    private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
