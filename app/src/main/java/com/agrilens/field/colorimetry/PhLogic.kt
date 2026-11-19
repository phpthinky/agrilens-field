package com.agrilens.field.colorimetry

/**
 * Port of the parts of the server's PhTestService (app/Services/PhTestService.php)
 * the BSWM two-step pH test needs. Keep the two in step.
 */
object PhLogic {

    /** Readings within 0.3 pH units (variance < 0.09) count as high confidence. */
    private const val VARIANCE_THRESHOLD = 0.09

    data class Stats(val average: Double, val variance: Double, val confidence: String)

    /** Nearest card point at or above [ph] (ceiling snap); the highest point if [ph] is above them all. */
    fun snapToChartPh(ph: Double, chart: List<ChartPoint>): Double {
        val points = chart.map { it.value }.distinct().sorted()
        require(points.isNotEmpty()) { "The pH colour chart is empty." }
        return points.firstOrNull { it >= ph - 0.00001 } ?: points.last()
    }

    /**
     * BSWM protocol after Step 1 (CPR):
     *   pH ≤ 5.4 → BCG, pH > 5.8 → BTB, 5.4–5.8 → the CPR reading is final,
     *   below 4.0 or above 7.8 → outside the charts, retest.
     */
    fun decideSolution(ph1: Double): String = when {
        ph1 < 4.0 || ph1 > 7.8 -> RETEST
        ph1 <= 5.4 -> "BCG"
        ph1 > 5.8 -> "BTB"
        else -> "CPR"
    }

    fun computeStats(values: List<Double>): Stats {
        if (values.isEmpty()) return Stats(0.0, 0.0, "Low")
        val avg = values.sum() / values.size
        val variance = if (values.size > 1) values.sumOf { (it - avg) * (it - avg) } / values.size else 0.0
        return Stats(
            average = ColorScience.phpRound(avg, 2),
            variance = ColorScience.phpRound(variance, 4),
            confidence = if (variance < VARIANCE_THRESHOLD) "High" else "Low",
        )
    }

    fun averageHex(readings: List<CaptureReading>): String {
        if (readings.isEmpty()) return "#000000"
        val n = readings.size.toDouble()
        return ColorScience.toHex(
            ColorScience.phpRoundInt(readings.sumOf { it.r } / n),
            ColorScience.phpRoundInt(readings.sumOf { it.g } / n),
            ColorScience.phpRoundInt(readings.sumOf { it.b } / n),
        )
    }

    fun solutionName(solution: String): String = when (solution) {
        "BCG" -> "BCG (Bromocresol Green)"
        "BTB" -> "BTB (Bromothymol Blue)"
        else -> "CPR (Chlorophenol Red)"
    }

    const val RETEST = "RETEST"
}
