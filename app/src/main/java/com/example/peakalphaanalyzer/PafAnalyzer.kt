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
import kotlin.math.max
import kotlin.math.min

object PafAnalyzer {

    class CsvFormatException(msg: String) : Exception(msg)
    private val transformer = FastFourierTransformer(DftNormalization.STANDARD)

    var welchWindowSec: Double = 6.0
    var welchSubWindowSec: Double = 3.0
    var welchOverlap: Double = 0.25

    data class IafResult(
        val fs: Double,
        val duration: Double,
        val iafHz: Double,
        val confidence: Double,
        val rawPosterior: DoubleArray
    ) {
        fun format(): String = buildString {
            append("fs=${"%.1f".format(fs)} Hz, dur=${"%.1f".format(duration)}s\n")
            append("IAF (PAF): ${"%.2f".format(iafHz)} Hz\n")
            append("Confidence: ${"%.2f".format(confidence)} (0–1)\n")
        }
    }

    fun analyze(stream: java.io.InputStream): IafResult {

        val reader = CSVReader(InputStreamReader(stream))
        val all = reader.readAll()
        if (all.size <= 1) throw CsvFormatException("Empty CSV")

        val rows = all.drop(1).filter { it.size > 37 && it[37] == "1" }
        if (rows.size < 2) throw CsvFormatException("No valid segment.")

        val rawTs = rows.mapNotNull { it[0].takeIf { t -> t.isNotBlank() } }
        val timesAll = parseTimes(rawTs)

        val fs = 256.0

        val startIdx = timesAll.indexOfFirst { it >= timesAll.first() + 10 }
        val endIdx = timesAll.indexOfLast { it <= timesAll.last() - 10 }
        if (startIdx < 0 || endIdx <= startIdx) throw CsvFormatException("Invalid interval after trimming.")

        val tSeg = timesAll.subList(startIdx, endIdx)

        val tp9All = rows.map { it[21].toDouble() }
        val tp10All = rows.map { it[24].toDouble() }

        val posteriorSeg = tp9All.zip(tp10All) { a, b -> (a + b) / 2.0 }
            .subList(startIdx, endIdx)
            .toDoubleArray()

        val paired = tSeg.zip(posteriorSeg.toList())
            .distinctBy { it.first }
            .sortedBy { it.first }

        if (paired.size < 2) throw CsvFormatException("Not enough unique timestamps.")

        val tUnique = paired.map { it.first }.toDoubleArray()
        val yUnique = paired.map { it.second }.toDoubleArray()

        val spline = SplineInterpolator().interpolate(tUnique, yUnique)

        val t0 = tUnique.first()
        val t1 = tUnique.last()
        val nUniform = ((t1 - t0) * fs).toInt().coerceAtLeast(2)

        val y = DoubleArray(nUniform) { k ->
            val tk = t0 + k / fs
            spline.value(tk)
        }

        val duration = t1 - t0

        val (iaf, confidence) = computeWelchPAF(y, fs)

        return IafResult(
            fs = fs,
            duration = duration,
            iafHz = iaf,
            confidence = confidence,
            rawPosterior = y
        )
    }

    private fun computeWelchPAF(
        data: DoubleArray,
        fs: Double
    ): Pair<Double, Double> {

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

        if (count == 0) return Pair(Double.NaN, 0.0)

        val psd = psdAcc.map { it / count }

        val idxs = psd.indices.filter { i -> i * fs / nfft in 8.0..13.0 }
        val i0 = idxs.maxByOrNull { psd[it] } ?: return Pair(Double.NaN, 0.0)

        val iaf = i0 * fs / nfft

        val peakPower = psd[i0]
        val neighbors = idxs.filter { it != i0 }.map { psd[it] }
        val meanAlpha = neighbors.average()
        val snr = peakPower / (meanAlpha + 1e-9)

        val left = max(i0 - 2, idxs.first())
        val right = min(i0 + 2, idxs.last())
        val sharpness = peakPower / (psd[left] + psd[right] + 1e-9)

        val confidence = (
            0.5 * (snr / 5.0).coerceIn(0.0, 1.0) +
            0.5 * (sharpness / 5.0).coerceIn(0.0, 1.0)
        )

        return Pair(iaf, confidence)
    }

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
