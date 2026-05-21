// PafAnalyzer.kt
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10

object PafAnalyzer {
    class CsvFormatException(msg: String): Exception(msg)
    private val transformer = FastFourierTransformer(DftNormalization.STANDARD)

    var welchWindowSec: Double = 6.0
    var welchSubWindowSec: Double = 3.0
    var welchOverlap: Double = 0.25

    data class PafResult(
        val fs: Double,
        val duration: Double,
        val pafL: Double,
        val pafR: Double,
        val pafMean: Double,
        val pafMedian: Double,
        val timeL: Double,
        val timeR: Double,
        val welchL: Double,
        val welchR: Double,
        val welchMean: Double,
        val welchMedian: Double,
        val rawLeft: DoubleArray,
        val rawRight: DoubleArray
    ) {
        fun format(): String = buildString {
            append("fs=${"%.1f".format(fs)} Hz, dur=${"%.1f".format(duration)}s\n")
            append("Welch PAF: L=${"%.1f".format(welchL)} R=${"%.1f".format(welchR)} mean=${"%.1f".format(welchMean)}\n")
            append("FFT PAF:   L=${"%.1f".format(pafL)} R=${"%.1f".format(pafR)} mean=${"%.1f".format(pafMean)}")
        }
    }

    fun analyze(stream: java.io.InputStream): PafResult {
        val reader = CSVReader(InputStreamReader(stream))
        val all = reader.readAll()
        if (all.size <= 1) throw CsvFormatException("Empty CSV")

        // Filter HeadBandOn=1
        val rows = all.drop(1).filter { it.size > 37 && it[37] == "1" }
        if (rows.size < 2) throw CsvFormatException("No valid segment.")

        // --- Parse timestamps as doubles (seconds) ---
        val rawTs = rows.mapNotNull { it[0].takeIf { t -> t.isNotBlank() } }
        val timesAll = parseTimes(rawTs)  // List<Double>, same length as rows

        if (timesAll.size < 2) throw CsvFormatException("Not enough timestamps.")

        // --- Estimate overall duration and fs from sample count ---
        val durationAll = timesAll.last() - timesAll.first()
        if (durationAll <= 0.0) throw CsvFormatException("Non-positive duration.")
        val fsEstimated = rows.size.toDouble() / durationAll

        // --- Detect timestamp resolution (how often timestamps change) ---
        val uniqueTimes = timesAll.distinct().sorted()
        if (uniqueTimes.size < 2) throw CsvFormatException("Not enough unique timestamps.")

        val deltas = uniqueTimes.zipWithNext()
            .map { (a, b) -> b - a }
            .filter { it > 0.0 }

        if (deltas.isEmpty()) throw CsvFormatException("Cannot estimate timestamp resolution.")

        val sortedD = deltas.sorted()
        val dtMedian = if (sortedD.size % 2 == 0) {
            val m = sortedD.size / 2
            (sortedD[m - 1] + sortedD[m]) / 2.0
        } else {
            sortedD[sortedD.size / 2]
        }

        val timestampRate = if (dtMedian > 0.0) 1.0 / dtMedian else 0.0

        // If timestamps update much slower than fs, they are too coarse for windowing
        val timestampsTooCoarse = timestampRate < fsEstimated / 10.0

        // --- Build corrected time array ---
        val timeCorrected: DoubleArray =
            if (timestampsTooCoarse) {
                // Use sample index as time: t = i / fsEstimated
                DoubleArray(rows.size) { i -> i.toDouble() / fsEstimated }
            } else {
                // Use parsed timestamps directly
                DoubleArray(timesAll.size) { i -> timesAll[i] }
            }

        val fs = fsEstimated

        // --- Trim 10s at start/end using corrected time ---
        val startIdx = timeCorrected.indexOfFirst { it >= timeCorrected.first() + 10.0 }
        val endIdx = timeCorrected.indexOfLast { it <= timeCorrected.last() - 10.0 }
        if (startIdx < 0 || endIdx <= startIdx) throw CsvFormatException("Invalid interval after trimming.")

        val tSeg = timeCorrected.copyOfRange(startIdx, endIdx)

        // --- Build left/right channels (mean of two sensors each side) ---
        val leftAll = rows.map { (it[21].toDouble() + it[22].toDouble()) / 2.0 }
        val rightAll = rows.map { (it[23].toDouble() + it[24].toDouble()) / 2.0 }

        val leftSeg = leftAll.subList(startIdx, endIdx).toDoubleArray()
        val rightSeg = rightAll.subList(startIdx, endIdx).toDoubleArray()

        // --- Deduplicate timestamps for interpolation ---
        val pairedL = tSeg.zip(leftSeg.toList())
            .distinctBy { it.first }
            .sortedBy { it.first }
        val pairedR = tSeg.zip(rightSeg.toList())
            .distinctBy { it.first }
            .sortedBy { it.first }

        if (pairedL.size < 2 || pairedR.size < 2)
            throw CsvFormatException("Not enough unique timestamps for interpolation.")

        val tUnique = pairedL.map { it.first }.toDoubleArray()
        val yLuni = pairedL.map { it.second }.toDoubleArray()
        val yRuni = pairedR.map { it.second }.toDoubleArray()

        val spline = SplineInterpolator()
        val interpL = spline.interpolate(tUnique, yLuni)
        val interpR = spline.interpolate(tUnique, yRuni)

        val yL = DoubleArray(tUnique.size) { i -> interpL.value(tUnique[i]) }
        val yR = DoubleArray(tUnique.size) { i -> interpR.value(tUnique[i]) }

        val duration = tUnique.last() - tUnique.first()

        // --- FFT PAF (single-window) ---
        val (pL, tL) = fftPeak(yL, fs)
        val (pR, tR) = fftPeak(yR, fs)
        val meanF = (pL + pR) / 2.0
        val medF = median(pL, pR)

        // --- Welch PAF (whole signal) ---
        val wL = computeWelchPeak(yL, fs)
        val wR = computeWelchPeak(yR, fs)
        val meanW = (wL + wR) / 2.0
        val medW = median(wL, wR)

        return PafResult(
            fs = fs,
            duration = duration,
            pafL = pL, pafR = pR, pafMean = meanF, pafMedian = medF,
            timeL = tL, timeR = tR,
            welchL = wL, welchR = wR, welchMean = meanW, welchMedian = medW,
            rawLeft = yL, rawRight = yR
        )
    }

    // --- FFT peak detection ---
    private fun fftPeak(data: DoubleArray, fs: Double): Pair<Double, Double> {
        val n = nextPow2(data.size)
        val spec = transformer.transform(data.copyOf(n), TransformType.FORWARD)
        val freqs = DoubleArray(n) { i -> i * fs / n }
        var max = 0.0
        var f0 = 0.0
        var idx = 0
        for (i in freqs.indices) {
            if (freqs[i] in 8.0..13.0) {
                val m = spec[i].abs()
                if (m > max) {
                    max = m
                    f0 = freqs[i]
                    idx = i
                }
            }
        }
        return f0 to (idx / fs)
    }

    fun slidingFftPeakSeries(
        data: DoubleArray,
        fs: Double,
        windowSec: Double = welchWindowSec,
        overlap: Double = welchOverlap
    ): List<PeakPoint> {
        val nPerSeg = (fs * windowSec).toInt().coerceAtLeast(1)
        val nfft = nextPow2(nPerSeg)
        val step = (nPerSeg * (1 - overlap)).toInt().coerceAtLeast(1)
        val win = DoubleArray(nPerSeg) { i -> 0.54 - 0.46 * cos(2 * PI * i / (nPerSeg - 1)) }
        val winPower = win.sumOf { w -> w * w }
        val half = nfft / 2

        val series = mutableListOf<PeakPoint>()
        var st = 0
        while (st + nPerSeg <= data.size) {
            val seg = DoubleArray(nPerSeg) { j -> data[st + j] * win[j] }
            val padded = seg.copyOf(nfft)
            val spec = transformer.transform(padded, TransformType.FORWARD)
            val psd = DoubleArray(half) { i ->
                val c = spec[i]
                (c.real * c.real + c.imaginary * c.imaginary) / (fs * winPower)
            }
            val idxs = psd.indices.filter { i -> i * fs / nfft in 8.0..12.0 }
            val i0 = idxs.maxByOrNull { psd[it] } ?: break
            val peakDb = 10 * log10(psd[i0])
            val time = (st + nPerSeg / 2).toDouble() / fs

            series += PeakPoint(time, peakDb)
            st += step
        }
        return series
    }

    fun welchPeakSeries(
        data: DoubleArray,
        fs: Double,
        windowSec: Double = welchWindowSec,
        overlap: Double = welchOverlap
    ): List<PeakPoint> {
        val nWindow = (fs * windowSec).toInt().coerceAtLeast(1)
        val step = (nWindow * (1 - overlap)).toInt().coerceAtLeast(1)
        val series = mutableListOf<PeakPoint>()
        var st = 0

        while (st + nWindow <= data.size) {
            val block = data.copyOfRange(st, st + nWindow)
            val peakDb = computeWelchPeakDb(block, fs, welchSubWindowSec, overlap)
            val time = (st + nWindow / 2).toDouble() / fs
            series += PeakPoint(time, peakDb)
            st += step
        }
        return series
    }

    private fun computeWelchPeak(
        data: DoubleArray,
        fs: Double,
        windowSec: Double = welchWindowSec,
        overlap: Double = welchOverlap
    ): Double {
        val nPerSeg = (fs * windowSec).toInt().coerceAtLeast(1)
        val nfft = nextPow2(nPerSeg)
        val stepSeg = (nPerSeg * (1 - overlap)).toInt().coerceAtLeast(1)
        val win = DoubleArray(nPerSeg) { i -> 0.54 - 0.46 * cos(2 * PI * i / (nPerSeg - 1)) }
        val winPow = win.sumOf { w -> w * w }
        val half = nfft / 2

        val psdAcc = DoubleArray(half) { 0.0 }
        var cnt = 0
        var off = 0

        while (off + nPerSeg <= data.size) {
            val seg = DoubleArray(nPerSeg) { j -> data[off + j] * win[j] }
            val padded = seg.copyOf(nfft)
            val spec = transformer.transform(padded, TransformType.FORWARD)
            for (i in 0 until half) {
                val c = spec[i]
                psdAcc[i] += (c.real * c.real + c.imaginary * c.imaginary) / (fs * winPow)
            }
            cnt++
            off += stepSeg
        }
        if (cnt == 0) return Double.NaN

        val psdMean = psdAcc.map { it / cnt }
        val idxs = psdMean.indices.filter { i -> i * fs / nfft in 8.0..12.0 }
        val i0 = idxs.maxByOrNull { psdMean[it] } ?: return Double.NaN
        return i0 * fs / nfft
    }

    private fun computeWelchPeakDb(
        data: DoubleArray,
        fs: Double,
        subWindowSec: Double = welchSubWindowSec,
        overlap: Double = welchOverlap
    ): Double {
        val nSub = (fs * subWindowSec).toInt().coerce
