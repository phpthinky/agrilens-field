package com.agrilens.field.colorimetry

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One reference colour on a downloaded chart: its hex and the pH / ppm it stands for. */
data class ChartPoint(val hex: String, val value: Double)

data class IndicatorMatch(val ph: Double, val confidencePct: Int)

/**
 * Port of the server's ColorScienceService (app/Services/ColorScienceService.php):
 * CIE L*a*b* + CIEDE2000, matched against the downloaded colour charts.
 * Keep the two in step — the website shows the values this computes.
 */
object ColorScience {

    /** pH from an indicator chart (CPR, BCG or BTB), clamped to the chart's own range. */
    fun indicatorPh(hex: String, chart: List<ChartPoint>): IndicatorMatch {
        val points = asPhpMap(chart)
        require(points.isNotEmpty()) { "The pH colour chart is empty." }
        val (value, minDeltaE) = matchWithDeltaE(hex, points)
        val values = points.map { it.value }
        val ph = phpRound(min(values.max(), max(values.min(), value)), 1)
        val confidence = (100 - minDeltaE * 3).let { phpRound(it, 0).toInt() }.coerceIn(0, 100)
        return IndicatorMatch(ph, confidence)
    }

    /** ppm from a nutrient chart (N, P or K), clamped to the chart's own range. */
    fun nutrientLevel(hex: String, chart: List<ChartPoint>): Double {
        val points = asPhpMap(chart)
        require(points.isNotEmpty()) { "The nutrient colour chart is empty." }
        val values = points.map { it.value }
        return phpRound(min(values.max(), max(values.min(), matchWithDeltaE(hex, points).first)), 2)
    }

    /**
     * The server reads a chart as a PHP [hex => value] array ordered by value,
     * so a hex listed twice keeps its last (highest) value, in its first slot.
     */
    private fun asPhpMap(chart: List<ChartPoint>): List<ChartPoint> {
        val map = LinkedHashMap<String, Double>()
        for (point in chart) map[point.hex] = point.value
        return map.map { (hex, value) -> ChartPoint(hex, value) }
    }

    /** [value, minDeltaE]: an exact hit (ΔE < 0.5) or the inverse-ΔE blend of the 3 nearest colours. */
    private fun matchWithDeltaE(capturedHex: String, chart: List<ChartPoint>): Pair<Double, Double> {
        val lab = hexToLab(capturedHex)
        val distances = chart.map { it.value to deltaE2000(lab, hexToLab(it.hex)) }.sortedBy { it.second }

        val minDeltaE = distances.first().second
        if (minDeltaE < 0.5) return distances.first().first to minDeltaE

        var num = 0.0
        var denom = 0.0
        for ((value, de) in distances.take(3)) {
            val w = 1.0 / max(de, 0.001)
            num += w * value
            denom += w
        }
        return phpRound(num / denom, 2) to minDeltaE
    }

    fun hexToRgb(hex: String): IntArray {
        val h = hex.trimStart('#')
        return intArrayOf(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
    }

    fun hexToLab(hex: String): DoubleArray = hexToRgb(hex).let { rgbToLab(it[0], it[1], it[2]) }

    fun rgbToLab(r8: Int, g8: Int, b8: Int): DoubleArray {
        fun lin(c: Double) = if (c > 0.04045) ((c + 0.055) / 1.055).pow(2.4) else c / 12.92
        val r = lin(r8 / 255.0)
        val g = lin(g8 / 255.0)
        val b = lin(b8 / 255.0)

        val x = (r * 0.4124564 + g * 0.3575761 + b * 0.1804375) / 0.95047
        val y = (r * 0.2126729 + g * 0.7151522 + b * 0.0721750) / 1.00000
        val z = (r * 0.0193339 + g * 0.1191920 + b * 0.9503041) / 1.08883

        fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3.0) else 7.787 * t + 16.0 / 116.0
        return doubleArrayOf(
            phpRound(116.0 * f(y) - 16.0, 4),
            phpRound(500.0 * (f(x) - f(y)), 4),
            phpRound(200.0 * (f(y) - f(z)), 4),
        )
    }

    fun deltaE2000(lab1: DoubleArray, lab2: DoubleArray): Double {
        val (l1, a1, b1) = Triple(lab1[0], lab1[1], lab1[2])
        val (l2, a2, b2) = Triple(lab2[0], lab2[1], lab2[2])

        val c1ab = sqrt(a1 * a1 + b1 * b1)
        val c2ab = sqrt(a2 * a2 + b2 * b2)
        val cab7 = ((c1ab + c2ab) / 2.0).pow(7)
        val g = 0.5 * (1.0 - sqrt(cab7 / (cab7 + 25.0.pow(7))))

        val a1p = a1 * (1.0 + g)
        val a2p = a2 * (1.0 + g)
        val c1p = sqrt(a1p * a1p + b1 * b1)
        val c2p = sqrt(a2p * a2p + b2 * b2)

        var h1p = if (b1 == 0.0 && a1p == 0.0) 0.0 else Math.toDegrees(atan2(b1, a1p))
        if (h1p < 0) h1p += 360.0
        var h2p = if (b2 == 0.0 && a2p == 0.0) 0.0 else Math.toDegrees(atan2(b2, a2p))
        if (h2p < 0) h2p += 360.0

        val dLp = l2 - l1
        val dCp = c2p - c1p
        val dhp = when {
            c1p * c2p == 0.0 -> 0.0
            abs(h2p - h1p) <= 180.0 -> h2p - h1p
            h2p - h1p > 180.0 -> h2p - h1p - 360.0
            else -> h2p - h1p + 360.0
        }
        val dHp = 2.0 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2.0))

        val lbp = (l1 + l2) / 2.0
        val cbp = (c1p + c2p) / 2.0
        val hbp = when {
            c1p * c2p == 0.0 -> h1p + h2p
            abs(h1p - h2p) <= 180.0 -> (h1p + h2p) / 2.0
            h1p + h2p < 360.0 -> (h1p + h2p + 360.0) / 2.0
            else -> (h1p + h2p - 360.0) / 2.0
        }

        val t = 1.0 - 0.17 * cos(Math.toRadians(hbp - 30.0)) +
            0.24 * cos(Math.toRadians(2.0 * hbp)) +
            0.32 * cos(Math.toRadians(3.0 * hbp + 6.0)) -
            0.20 * cos(Math.toRadians(4.0 * hbp - 63.0))

        val sl = 1.0 + 0.015 * (lbp - 50.0).pow(2) / sqrt(20.0 + (lbp - 50.0).pow(2))
        val sc = 1.0 + 0.045 * cbp
        val sh = 1.0 + 0.015 * cbp * t

        val cbp7 = cbp.pow(7)
        val rc = 2.0 * sqrt(cbp7 / (cbp7 + 25.0.pow(7)))
        val dTheta = 30.0 * exp(-((hbp - 275.0) / 25.0).pow(2))
        val rt = -rc * sin(Math.toRadians(2.0 * dTheta))

        return sqrt(
            (dLp / sl).pow(2) + (dCp / sc).pow(2) + (dHp / sh).pow(2) + rt * (dCp / sc) * (dHp / sh)
        )
    }

    fun toHex(r: Int, g: Int, b: Int): String = String.format(Locale.US, "#%02X%02X%02X", r, g, b)

    /** PHP round(): half away from zero. */
    fun phpRound(value: Double, digits: Int): Double {
        val factor = 10.0.pow(digits)
        val scaled = abs(value) * factor
        // PHP pre-rounds to 15 significant digits so 1.005 * 100 rounds as 100.5.
        val pre = String.format(Locale.US, "%.15g", scaled).toDouble()
        return Math.copySign(floor(pre + 0.5) / factor, value)
    }

    fun phpRoundInt(value: Double): Int = phpRound(value, 0).roundToInt()
}
