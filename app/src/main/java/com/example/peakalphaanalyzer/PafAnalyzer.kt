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

        val rows = all.drop(1).filter { it.size > 37 && it[37] == "1" }
        if (rows.size < 2) throw CsvFormatException("No valid segment.")

        // --- Parse timestamps ---
        val rawTs = rows.mapNotNull { it[0].takeIf { t -> t.isNotBlank() } }
        val timesAll = parseTimes(rawTs)

        // --- Detect timestamp resolution ---
        val uniqueTimes = timesAll.distinct().sorted()
        val deltas = uniqueTimes.zipWithNext().map { it.second - it.first }.filter { it > 0 }
        if (deltas.isEmpty()) throw CsvFormatException("Cannot estimate timestamp resolution.")

        val dtMedian = deltas.sorted().let { d ->
            if (d.size % 2 == 0) (d[d.size/2] + d[d.size/2 - 1]) / 2 else d[d.size/2]
        }
        val timestampRate = 1.0 / dtMedian

        // --- Estimate fs from sample count ---
        val fsEstimated = rows.size / (timesAll.last() - timesAll.first())

        // --- Decide whether timestamps are too coarse ---
        val timestampsTooCoarse = timestampRate < fsEstimated / 10.0

        // --- Build corrected time array ---
        val timeCorrected = if (timestampsTooCoarse) {
            DoubleArray(rows.size) { i -> i.toDouble() / fsEstimated }
        } else {
            timesAll.toDoubleArray()
        }

        val fs = fsEstimated

        // --- Trim 10s at start/end ---
        val startIdx = timeCorrected.indexOfFirst { it >= timeCorrected.first() + 10 }
        val endIdx = timeCorrected.indexOfLast { it <= timeCorrected.last() - 10 }
        if (startIdx < 0 || endIdx <= startIdx) throw CsvFormatException("Invalid interval after trimming.")

        val tSeg = timeCorrected.copyOfRange(startIdx, endIdx)

        // --- Build left/right channels ---
        val leftAll = rows.map { (it[21].toDouble() + it[22].toDouble()) / 2.0 }
        val rightAll = rows.map { (it[23].toDouble() + it[24].toDouble()) / 2.0 }

        val leftSeg = leftAll.subList(startIdx, endIdx).toDoubleArray()
        val rightSeg = rightAll.subList(startIdx, endIdx).toDoubleArray()

        // --- Deduplicate timestamps for interpolation ---
        val pairedL = tSeg.zip(leftSeg.toList()).distinctBy { it.first }.sortedBy { it.first }
        val pairedR = tSeg.zip(rightSeg.toList()).distinctBy { it.first }.sortedBy { it.first }

        if (pairedL.size < 2 || pairedR.size < 2)
            throw CsvFormatException("Not enough unique timestamps for interpolation.")

        val tUnique = pairedL.map { it.first }.toDoubleArray()
        val yLuni = pairedL.map { it.second }.toDoubleArray()
        val yRuni = pairedR.map { it.second }.toDoubleArray()

        val spline = SplineInterpolator()
        val interpL = spline.interpolate(tUnique, yLuni)
        val interpR = spline.interpolate(tUnique, yRuni)

        val yL = DoubleArray(tUnique.size) { interpL.value(tUnique[it]) }
        val yR = DoubleArray(tUnique.size) { interpR.value(tUnique[it]) }

        val duration = tUnique.last() - tUnique.first()

        // --- FFT PAF ---
        val (pL, tL) = fftPeak(yL, fs)
        val (pR, tR) = fftPeak(yR, fs)
        val meanF = (pL + pR) / 2.0
        val medF = median(pL, pR)

        // --- Welch PAF ---
        val wL = computeWelchPeak(yL, fs)
        val wR = computeWelchPeak(yR, fs)
        val meanW = (wL + wR) / 2.0
        val medW = median(wL, wR)

        return PafResult(
            fs = fs,
            duration = duration,
            pafL = pL, pafR = pR, pafMean = meanF, pafMedian = medF,
            timeL = tL, timeR = tR,
            wel
