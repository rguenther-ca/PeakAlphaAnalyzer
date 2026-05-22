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

    // Welch parameters
    var welchWindowSec: Double = 6.0
    var welchSubWindowSec: Double = 3.0
    var welchOverlap: Double = 0.25

    data class IafResult(
        val fs: Double,
        val duration: Double,
        val iafHz: Double,
        val welchPaf: Double,
        val rawPosterior: DoubleArray
    ) {
        fun format(): String = buildString {
            append("fs=${"%.1f".format(fs)} Hz, dur=${"%.1f".format(duration)}s\n")
            append("IAF (PAF): ${"%.2f".format(iafHz)} Hz\n")
        }
    }

    // --- Sampling rate detection (unchanged ingestion) ---
       // --- MAIN ANALYSIS: compute IAF ---
    fun analyze(stream: java.io.InputStream): IafResult {

        val reader = CSVReader(InputStreamReader(stream))
        val all = reader.readAll()
        if (all.size <= 1) throw CsvFormatException("Empty CSV")

        // Filter HeadBandOn=1
        val rows = all.drop(1).filter { it.size > 37 && it[37] == "1" }
        if (rows.size < 2) throw CsvFormatException("No valid segment.")

        // Parse timestamps
        val rawTs = rows.mapNotNull { it[0].takeIf { t -> t.isNotBlank() } }
        val timesAll = parseTimes(rawTs)

        // Detect fs
//        val fs = detectFs(rows)
// FIXED SAMPLING RATE FOR MUSE RAW EEG
val fs = 256.0
        // Trim 10 seconds at start/end
        val startIdx = timesAll.indexOfFirst { it >= timesAll.first() + 10 }
        val endIdx   = timesAll.indexOfLast  { it <= timesAll.last()  - 10 }
        if (startIdx < 0 || endIdx <= startIdx) throw CsvFormatException("Invalid interval after trimming.")

        val tSeg = timesAll.subList(startIdx, endIdx)

        // POSTERIOR CHANNELS ONLY (TP9 idx 21, TP10 idx 24)
        val tp9All = rows.map { it[21].toDouble() }
        val tp10All = rows.map { it[24].toDouble() }

        val posteriorSeg = tp9All.zip(tp10All) { a, b -> (a + b) / 2.0 }
            .subList(startIdx, endIdx)
            .toDoubleArray()

        // Interpolate to uniform grid
        val paired = tSeg.zip(posteriorSeg.toList())
            .distinctBy { it.first }
            .sortedBy { it.first }

        if (paired.size < 2) throw CsvFormatException("Not enough unique timestamps.")

        val tUnique = paired.map { it.first }.toDoubleArray()
        val yUnique = paired.map { it.second }.toDoubleArray()

        val spline = SplineInterpolator()
        val interp = spline.interpolate(tUnique, yUnique)
        val y = DoubleArray(tUnique.size) { interp.value(tUnique[it]) }

        val duration = tUnique.last() - tUnique.first()

        // Compute Welch PSD PAF (IAF)
        val iaf = computeWelchPAF(y, fs)

        return IafResult(
            fs = fs,
            duration = duration,
            iafHz = iaf,
            welchPaf = iaf,
            rawPosterior = y
        )
    }

    // --- Welch PAF (IAF) ---
    private fun computeWelchPAF(
        data: DoubleArray,
        fs: Double
    ): Double {

        val nPerSeg = (fs * welchSubWindowSec).toInt().coerceAtLeast(1)
        val nfft = nextPow2(nPerSeg)
        val step = (nPerSeg * (1 - welchOverlap)).toInt().coerceAtLeast(1)

        val win = DoubleArray(nPerSeg) { i -> 0.54 - 0.46 * cos(2 * PI * i / (nPerSeg - 1)) }
        val winPow = win.sumOf { it * it }
        val half = nfft / 2

        val psdAcc = DoubleArray(half) { 0.0 }
        var count = 0
        var off = 0

        while (off + nPerSeg <= data.size) {
            val seg = DoubleArray(nPerSeg) { j -> data[off + j] * win[j] }
            val padded = seg.copyOf(nfft)
            val spec = transformer.transform(padded, TransformType.FORWARD)

            for (i in 0 until half) {
                val c = spec[i]
                psdAcc[i] += (c.real * c.real + c.imaginary * c.imaginary) / (fs * winPow)
            }

            count++
            off += step
        }

        if (count == 0) return Double.NaN

        val psd = psdAcc.map { it / count }

        val idxs = psd.indices.filter { i -> i * fs / nfft in 8.0..13.0 }
        val i0 = idxs.maxByOrNull { psd[it] } ?: return Double.NaN

        return i0 * fs / nfft
    }

    // --- Helpers ---
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
}
