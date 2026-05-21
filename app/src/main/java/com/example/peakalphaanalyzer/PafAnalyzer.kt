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

    // ————— Parametri Welch modificabili —————
    /** Durata finestra in secondi */
    var welchWindowSec: Double = 6.0
    /** Ogni sotto-finestra di Welch durerà subWindowSec secondi */
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

        // --- Parse timestamps ---
        val rawTs = rows.mapNotNull { it[0].takeIf { t -> t.isNotBlank() } }
        val timesAll = parseTimes(rawTs)

        // Deduplicate & sort for fs estimation
        val timesUniq = timesAll.distinct().sorted()
        if (timesUniq.size < 2) throw CsvFormatException("Not enough unique timestamps.")

        // Estimate sampling interval from median of all positive deltas
        val deltas = timesUniq.zipWithNext()
            .map { it.second - it.first }
            .filter { it > 0.0 }
        if (deltas.isEmpty()) throw CsvFormatException("Cannot estimate sampling rate.")
        val sortedD = deltas.sorted()
        val dt = if (sortedD.size % 2 == 0) {
            val m = sortedD.size / 2
            (sortedD[m - 1] + sortedD[m]) / 2.0
        } else {
            sortedD[sortedD.size / 2]
        }
        val fs = 1.0 / dt

        // --- Trim 10s at start/end using original timesAll ---
        val startIdx = timesAll.indexOfFirst { it >= timesAll.first() + 10 }
        val endIdx = timesAll.indexOfLast  { it <= timesAll.last()  - 10 }
        if (startIdx < 0 || endIdx <= startIdx) throw CsvFormatException("Invalid interval after trimming.")

        val tSeg = timesAll.subList(startIdx, endIdx)
        //val leftSeg  = rows.map { it[22].toDouble() }.subList(startIdx, endIdx).toDoubleArray()
        //val rightSeg = rows.map { it[23].toDouble() }.subList(startIdx, endIdx).toDoubleArray()

        // 6) Compute left/right as mean of two channels:
        //    RAW_TP9 (idx 21) & RAW_AF7 (idx 22) → Left
        //    RAW_AF8 (idx 23) & RAW_TP10(idx 24) → Right
        val leftAll = rows.map { (it[21].toDouble() + it[22].toDouble()) / 2.0 }
        val rightAll= rows.map { (it[23].toDouble() + it[24].toDouble()) / 2.0 }

        val leftSeg  = leftAll.subList(startIdx, endIdx).toDoubleArray()
        val rightSeg = rightAll.subList(startIdx, endIdx).toDoubleArray()

        // --- Spline interpolation on de-duplicated segment ---
        val pairedL = tSeg.zip(leftSeg.toList())
            .distinctBy { it.first }
            .sortedBy    { it.first }
        val pairedR = tSeg.zip(rightSeg.toList())
            .distinctBy { it.first }
            .sortedBy    { it.first }
        if (pairedL.size < 2 || pairedR.size < 2) throw CsvFormatException("Not enough unique segment timestamps.")

        val tUnique  = pairedL.map { it.first }.toDoubleArray()
        val yLuni    = pairedL.map   { it.second }.toDoubleArray()
        val yRuni    = pairedR.map   { it.second }.toDoubleArray()

        val spline   = SplineInterpolator()
        val interpL  = spline.interpolate(tUnique, yLuni)
        val interpR  = spline.interpolate(tUnique, yRuni)
        val yL = DoubleArray(tUnique.size) { interpL.value(tUnique[it]) }
        val yR = DoubleArray(tUnique.size) { interpR.value(tUnique[it]) }

        val duration = tUnique.last() - tUnique.first()

        // --- FFT PAF (single-window) ---
        val (pL, tL) = fftPeak(yL, fs)
        val (pR, tR) = fftPeak(yR, fs)
        val meanF = (pL + pR) / 2.0
        val medF  = median(pL, pR)

        // --- Welch PAF (whole signal) ---
        val wL = computeWelchPeak(yL, fs)
        val wR = computeWelchPeak(yR, fs)
        val meanW = (wL + wR) / 2.0
        val medW  = median(wL, wR)

        return PafResult(
            fs = fs,
            duration = duration,
            pafL = pL, pafR = pR, pafMean = meanF, pafMedian = medF,
            timeL = tL, timeR = tR,
            welchL = wL, welchR = wR, welchMean = meanW, welchMedian = medW,
            rawLeft = yL, rawRight = yR
        )
    }

    fun analyze_old(stream: java.io.InputStream): PafResult {
        val reader = CSVReader(InputStreamReader(stream))
        val all = reader.readAll()
        if (all.size <= 1) throw CsvFormatException("Empty CSV")

        // Filter HeadBandOn=1
        val rows = all.drop(1).filter { it.size > 37 && it[37] == "1" }
        if (rows.size < 2) throw CsvFormatException("No valid segment.")

// 1) Parse times come prima
        val rawTs = rows.mapNotNull { it[0].takeIf { t -> t.isNotBlank() } }
        val timesAll = parseTimes(rawTs)

// 2) Rimuovo duplicati e ordino (serve per dt e per l’interpolazione)
        val times = timesAll
            .distinct()      // elimina i timestamp esatti ripetuti
            .sorted()

        if (times.size < 2) throw CsvFormatException("Not enough unique timestamps.")

// 3) Ricalcolo dt e fs sul primo intervallo valido
        val dt = times[1] - times[0]
        if (dt <= 0) throw CsvFormatException("Invalid sampling interval.")
        val fs = 1.0 / dt

// 4) Ora uso 'times' (deduplicati e ordinati) per tutti i passi successivi
//    incluso trimming, interpolazione e quant'altro.

        // Trim 10s at start/end
        val start = times.indexOfFirst { it >= times.first() + 10 }
        val end   = times.indexOfLast  { it <= times.last()  - 10 }
        if (start < 0 || end <= start) throw CsvFormatException("Invalid interval.")

        val tSeg     = times.subList(start, end)
        val leftRaw  = rows.map { it[22].toDouble() }.subList(start, end).toDoubleArray()
        val rightRaw = rows.map { it[23].toDouble() }.subList(start, end).toDoubleArray()

// remove exact duplicate timestamps, then sort strictly increasing
        val pairedL = tSeg.zip(leftRaw.toList())
            .distinctBy { it.first }
            .sortedBy    { it.first }

        val pairedR = tSeg.zip(rightRaw.toList())
            .distinctBy { it.first }
            .sortedBy    { it.first }

        // if after dedup we have <2 points, bail out early
        if (pairedL.size < 2 || pairedR.size < 2) {
            throw CsvFormatException("Not enough unique timestamps to interpolate.")
        }

// Separate back into arrays for interpolation
        val tUnique   = pairedL.map { it.first }.toDoubleArray()
        val yLunique  = pairedL.map { it.second }.toDoubleArray()
        val yRunique  = pairedR.map { it.second }.toDoubleArray()

// Now interpolate on strictly increasing tUnique
        val spline    = SplineInterpolator()
        val interpL   = spline.interpolate(tUnique, yLunique)
        val interpR   = spline.interpolate(tUnique, yRunique)
        val yL        = DoubleArray(tUnique.size) { interpL.value(tUnique[it]) }
        val yR        = DoubleArray(tUnique.size) { interpR.value(tUnique[it]) }
        val duration = tSeg.last() - tSeg.first()

        // FFT PAF (single-window)
        val (pL, tL) = fftPeak(yL, fs)
        val (pR, tR) = fftPeak(yR, fs)
        val meanF = (pL + pR) / 2.0
        val medF  = median(pL, pR)

        // Welch PAF (whole signal)
        val wL = computeWelchPeak(yL, fs)
        val wR = computeWelchPeak(yR, fs)
        val meanW = (wL + wR) / 2.0
        val medW  = median(wL, wR)

        return PafResult(fs, duration, pL, pR, meanF, medF, timeL = tL, timeR = tR, welchL = wL, welchR = wR, welchMean = meanW, welchMedian = medW, rawLeft = yL, rawRight = yR)
    }

    // --- helper per PAF FFT ---
    private fun fftPeak(data: DoubleArray, fs: Double): Pair<Double, Double> {
        val n = nextPow2(data.size)
        val spec = transformer.transform(data.copyOf(n), TransformType.FORWARD)
        val freqs = DoubleArray(n) { i -> i * fs / n }
        var max = 0.0; var f0 = 0.0; var idx = 0
        for (i in freqs.indices) {
            if (freqs[i] in 8.0..13.0) {
                val m = spec[i].abs()
                if (m > max) { max = m; f0 = freqs[i]; idx = i }
            }
        }
        return f0 to (idx / fs)
    }

    /**
     * Serie di picchi (time, dB) via FFT singolo (periodogramma non mediato)
     */
    fun slidingFftPeakSeries(
        data: DoubleArray,
        fs: Double,
        windowSec: Double = welchWindowSec,
        overlap: Double = welchOverlap
    ): List<PeakPoint> {
        val nPerSeg  = (fs * windowSec).toInt().coerceAtLeast(1)
        val nfft     = nextPow2(nPerSeg)
        val step     = (nPerSeg * (1 - overlap)).toInt().coerceAtLeast(1)
        val win      = DoubleArray(nPerSeg) { i -> 0.54 - 0.46 * cos(2 * PI * i / (nPerSeg - 1)) }
        val winPower = win.sumOf { w -> w*w }
        val half     = nfft / 2

        val series = mutableListOf<PeakPoint>()
        var st = 0
        while (st + nPerSeg <= data.size) {
            // window + padding
            val seg    = DoubleArray(nPerSeg) { j -> data[st + j] * win[j] }
            val padded = seg.copyOf(nfft)
            // FFT & PSD
            val spec = transformer.transform(padded, TransformType.FORWARD)
            val psd  = DoubleArray(half) { i ->
                val c = spec[i]
                (c.real*c.real + c.imaginary*c.imaginary) / (fs * winPower)
            }
            // peak 8–12 Hz
            val idxs   = psd.indices.filter { i -> i * fs / nfft in 8.0..12.0 }
            val i0     = idxs.maxByOrNull { psd[it] } ?: break
            val peakDb = 10 * log10(psd[i0])
            val time   = (st + nPerSeg/2).toDouble() / fs

            series += PeakPoint(time, peakDb)
            st += step
        }
        return series
    }

    /**
     * Serie di picchi (time, dB) calcolati con il vero Welch:
     * per ciascuna finestra sliding chiamo computeWelchPeak (che media già tutto)
     */
    fun welchPeakSeries(
        data: DoubleArray,
        fs: Double,
        windowSec: Double = welchWindowSec,
        overlap: Double = welchOverlap
    ): List<PeakPoint> {
        val nWindow = (fs * windowSec).toInt().coerceAtLeast(1)
        val step    = (nWindow * (1 - overlap)).toInt().coerceAtLeast(1)
        val series  = mutableListOf<PeakPoint>()
        var st      = 0

        while (st + nWindow <= data.size) {
            // estrai un blocco di durata windowSec
            val block  = data.copyOfRange(st, st + nWindow)
            // media vera di più sotto-finestre con Welch
            val peakDb = computeWelchPeakDb(block, fs, welchSubWindowSec, overlap)
            val time   = (st + nWindow/2).toDouble() / fs
            series += PeakPoint(time, peakDb)
            st += step
        }
        return series
    }

    /**
     * Restituisce la frequenza di picco α (in Hz) calcolata con Welch:
     * media dei periodogrammi di sotto‐finestre dentro il blocco `data`.
     */
    private fun computeWelchPeak(
        data: DoubleArray,
        fs: Double,
        windowSec: Double = welchWindowSec,
        overlap: Double = welchOverlap
    ): Double {
        // numero campioni per sotto‐finestra
        val nPerSeg  = (fs * windowSec).toInt().coerceAtLeast(1)
        val nfft     = nextPow2(nPerSeg)
        val stepSeg  = (nPerSeg * (1 - overlap)).toInt().coerceAtLeast(1)
        // Hamming
        val win      = DoubleArray(nPerSeg) { i -> 0.54 - 0.46 * cos(2 * PI * i / (nPerSeg - 1)) }
        val winPow   = win.sumOf { w -> w*w }
        val half     = nfft / 2

        // accumulo PSD
        val psdAcc   = DoubleArray(half) { 0.0 }
        var cnt      = 0
        var off      = 0

        while (off + nPerSeg <= data.size) {
            // finestra + padding
            val seg    = DoubleArray(nPerSeg) { j -> data[off + j] * win[j] }
            val padded = seg.copyOf(nfft)
            val spec   = transformer.transform(padded, TransformType.FORWARD)
            // sommo al cumulatore
            for (i in 0 until half) {
                val c = spec[i]
                psdAcc[i] += (c.real*c.real + c.imaginary*c.imaginary) / (fs * winPow)
            }
            cnt++
            off += stepSeg
        }
        if (cnt == 0) return Double.NaN

        // media dei PSD
        val psdMean = psdAcc.map { it / cnt }
        // cerco indice di picco fra 8 e 12 Hz
        val idxs = psdMean.indices.filter { i -> i * fs / nfft in 8.0..12.0 }
        val i0   = idxs.maxByOrNull { psdMean[it] } ?: return Double.NaN
        // converto l’indice in frequenza
        return i0 * fs / nfft
    }

    /**
     * Versione di computeWelchPeak che ritorna il picco in dB,
     * anziché la frequenza in Hz.
     */
    private fun computeWelchPeakDb(
        data: DoubleArray,
        fs: Double,
        subWindowSec: Double = welchSubWindowSec,
        overlap: Double = welchOverlap
    ): Double {
        val nSub     = (fs * subWindowSec).toInt().coerceAtLeast(1)
        val nfft     = nextPow2(nSub)
        val stepSub  = (nSub * (1 - overlap)).toInt().coerceAtLeast(1)
        val win      = DoubleArray(nSub) { i -> 0.54 - 0.46 * cos(2 * PI * i / (nSub - 1)) }
        val winPow   = win.sumOf { w -> w*w }
        val half     = nfft / 2
        val psdAcc   = DoubleArray(half) { 0.0 }
        var count    = 0
        var off      = 0

        while (off + nSub <= data.size) {
            // 1) prendi la sotto-finestra
            val seg    = DoubleArray(nSub) { j -> data[off + j] * win[j] }
            // 2) pad a potenza di 2
            val padded = seg.copyOf(nfft)
            // 3) FFT
            val spec   = transformer.transform(padded, TransformType.FORWARD)
            // 4) accumula PSD
            for (i in 0 until half) {
                val c = spec[i]
                psdAcc[i] += (c.real*c.real + c.imaginary*c.imaginary) / (fs * winPow)
            }
            count++
            off += stepSub
        }
        if (count == 0) return Double.NaN

        // 5) media dei PSD e picco in dB
        val psdMean = psdAcc.map { it / count }
        val idxs    = psdMean.indices.filter { i -> i * fs / nfft in 8.0..12.0 }
        val i0      = idxs.maxByOrNull { psdMean[it] } ?: return Double.NaN
        return 10 * log10(psdMean[i0])
    }



    private fun parseTimes(raw: List<String>): List<Double> {
        require(raw.size >= 2) { "Need at least two timestamps" }
        return if (raw.first().matches(Regex("[-\\d.]+"))) {
            val nums = raw.map(String::toDouble)
            // estimate dt0
            val dt0 = nums[1] - nums[0]
            if (dt0 in 0.0..0.01) {
                // values in ms → convert to seconds
                nums.map { it / 1000.0 }
            } else {
                // already in seconds
                nums
            }
        } else {
            // ISO timestamps
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            val inst = raw.map { ts ->
                LocalDateTime.parse(ts, fmt)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }
            val t0 = inst.first()
            inst.map { (it - t0) / 1000.0 }
        }
    }


    private fun parseTimes_old(raw: List<String>): List<Double> =
        if (raw.first().matches(Regex("[-\\d.]+")))
            raw.map(String::toDouble)
        else {
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            val inst = raw.map { ts ->
                LocalDateTime.parse(ts, fmt)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }
            val t0 = inst.first()
            inst.map { (it - t0)/1000.0 }
        }

    private fun nextPow2(n: Int): Int {
        var v = 1
        while (v < n) v = v shl 1
        return v
    }

    private fun median(a: Double, b: Double): Double = (a + b) / 2.0

    data class PeakPoint(val time: Double, val peakDb: Double)
}
