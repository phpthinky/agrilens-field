package com.agrilens.field.colorimetry

import java.util.Locale

/** What the screen shows for one parameter. */
data class ParameterView(
    val key: String,
    val label: String,
    val reagent: String,
    val captures: Int,
    val required: Int,
    val complete: Boolean,
    val canCapture: Boolean,
    val canReset: Boolean,
    val averageHex: String?,
    val message: String?,
    val nextPhoto: String?,
)

/**
 * The BSWM colorimetric workflow, run on the phone against the downloaded
 * charts — the same steps the web capture pages and PhTestController take:
 * 3 captures per parameter; pH Step 1 with CPR, then BCG or BTB (or CPR is
 * final, or retest) decided from the Step 1 average.
 */
object MobileColorimetry {

    /** Record one photo's colour for [key]; returns a short message for the technician. */
    fun addCapture(
        test: MobileTest,
        key: String,
        color: TubeColor,
        photo: String,
        config: ColorimetricConfig,
        now: Long = System.currentTimeMillis(),
    ): String {
        check(!test.isSynced) { "This test was already synced." }

        if (key == "ph") return addPhCapture(test.ph, color, photo, config, now)

        val readings = test.nutrient(key)
        check(readings.size < REQUIRED_CAPTURES) { "${LABELS[key]} already has 3 captures." }
        val value = ColorScience.nutrientLevel(color.hex, config.npkChart(key))
        readings += CaptureReading(photo, color.hex, color.r, color.g, color.b, value, zone = color.method, captured_at = now)
        return "${LABELS[key]} capture ${readings.size}: ${format(value)} ppm (${color.hex})"
    }

    private fun addPhCapture(ph: PhProgress, color: TubeColor, photo: String, config: ColorimetricConfig, now: Long): String {
        return when (ph.state) {
            PhProgress.STATE_STEP1 -> {
                check(ph.step1.size < REQUIRED_CAPTURES) { "pH Step 1 already has 3 captures." }
                val chart = config.phChart("CPR")
                val match = ColorScience.indicatorPh(color.hex, chart)
                ph.step1 += CaptureReading(
                    photo, color.hex, color.r, color.g, color.b, match.ph, match.confidencePct,
                    PhLogic.snapToChartPh(match.ph, chart), color.method, now,
                )
                var message = "pH Step 1 capture ${ph.step1.size}: pH ${format(match.ph)} (${color.hex})"

                if (ph.step1.size == REQUIRED_CAPTURES) {
                    val stats = PhLogic.computeStats(ph.step1.map { it.chart_ph ?: it.computed_value })
                    ph.step1_ph = stats.average
                    ph.step1_confidence = stats.confidence
                    message = when (val next = PhLogic.decideSolution(stats.average)) {
                        PhLogic.RETEST -> {
                            ph.state = PhProgress.STATE_RETEST
                            "CPR pH ${format(stats.average)} is outside the charts (4.0–7.8). Reset pH and retest."
                        }
                        "CPR" -> {
                            ph.final_ph = stats.average
                            ph.state = PhProgress.STATE_COMPLETE
                            "CPR pH ${format(stats.average)} is final (5.4–5.8): no Step 2 needed."
                        }
                        else -> {
                            ph.step2_solution = next
                            ph.state = PhProgress.STATE_STEP2
                            "CPR pH ${format(stats.average)}. Step 2: test with ${PhLogic.solutionName(next)}."
                        }
                    }
                }
                message
            }
            PhProgress.STATE_STEP2 -> {
                check(ph.step2.size < REQUIRED_CAPTURES) { "pH Step 2 already has 3 captures." }
                val solution = ph.step2_solution ?: "CPR"
                val chart = config.phChart(solution)
                val match = ColorScience.indicatorPh(color.hex, chart)
                ph.step2 += CaptureReading(
                    photo, color.hex, color.r, color.g, color.b, match.ph, match.confidencePct,
                    PhLogic.snapToChartPh(match.ph, chart), color.method, now,
                )
                var message = "pH Step 2 ($solution) capture ${ph.step2.size}: pH ${format(match.ph)} (${color.hex})"

                if (ph.step2.size == REQUIRED_CAPTURES) {
                    val stats = PhLogic.computeStats(ph.step2.map { it.chart_ph ?: it.computed_value })
                    ph.step2_ph = stats.average
                    ph.final_ph = stats.average
                    ph.state = PhProgress.STATE_COMPLETE
                    message = "Final pH ${format(stats.average)} ($solution)."
                }
                message
            }
            else -> error("pH can't take more captures — reset it first.")
        }
    }

    /** Photo file names this reset discards, so the caller can delete them. */
    fun reset(test: MobileTest, key: String): List<String> {
        check(!test.isSynced) { "This test was already synced." }
        return if (key == "ph") {
            val photos = (test.ph.step1 + test.ph.step2).map { it.photo }
            test.ph = PhProgress()
            photos
        } else {
            val photos = test.nutrient(key).map { it.photo }
            test.nutrients[key] = mutableListOf()
            photos
        }
    }

    fun isComplete(test: MobileTest): Boolean =
        test.ph.state == PhProgress.STATE_COMPLETE && test.ph.final_ph != null &&
            NUTRIENTS.all { test.nutrient(it).size == REQUIRED_CAPTURES }

    fun capturesDone(test: MobileTest): Int =
        test.ph.step1.size + test.ph.step2.size + NUTRIENTS.sumOf { test.nutrient(it).size }

    /** Final levels, as the server's SampleController computes them: N/P/K from each average colour. */
    fun levels(test: MobileTest, config: ColorimetricConfig): Map<String, Double> {
        check(isComplete(test)) { "Finish all pH, N, P and K captures first." }
        return mapOf("ph" to test.ph.final_ph!!) + NUTRIENTS.associateWith {
            ColorScience.nutrientLevel(PhLogic.averageHex(test.nutrient(it)), config.npkChart(it))
        }
    }

    fun views(test: MobileTest): List<ParameterView> {
        val locked = test.isSynced
        val ph = test.ph
        val phView = when (ph.state) {
            PhProgress.STATE_STEP2 -> ParameterView(
                "ph", LABELS.getValue("ph"), "Step 2: ${ph.step2_solution} indicator", ph.step2.size, REQUIRED_CAPTURES,
                complete = false, canCapture = !locked && ph.step2.size < REQUIRED_CAPTURES, canReset = !locked,
                averageHex = null,
                message = "Step 1 (CPR) gave pH ${format(ph.step1_ph ?: 0.0)}. Step 2: test with ${ph.step2_solution}.",
                nextPhoto = "ph-step2-${ph.step2.size + 1}",
            )
            PhProgress.STATE_COMPLETE -> ParameterView(
                "ph", LABELS.getValue("ph"), "Step ${if (ph.step2.isEmpty()) 1 else 2}: ${ph.step2_solution ?: "CPR"} indicator",
                REQUIRED_CAPTURES, REQUIRED_CAPTURES, complete = true, canCapture = false, canReset = !locked,
                averageHex = PhLogic.averageHex(ph.step2.ifEmpty { ph.step1 }),
                message = "Final pH ${format(ph.final_ph ?: 0.0)}.", nextPhoto = null,
            )
            PhProgress.STATE_RETEST -> ParameterView(
                "ph", LABELS.getValue("ph"), "Step 1: CPR indicator", ph.step1.size, REQUIRED_CAPTURES,
                complete = false, canCapture = false, canReset = !locked, averageHex = null,
                message = "CPR pH ${format(ph.step1_ph ?: 0.0)} is outside the charts (4.0–7.8). Reset pH and retest.",
                nextPhoto = null,
            )
            else -> ParameterView(
                "ph", LABELS.getValue("ph"), "Step 1: CPR indicator", ph.step1.size, REQUIRED_CAPTURES,
                complete = false, canCapture = !locked && ph.step1.size < REQUIRED_CAPTURES,
                canReset = !locked && ph.step1.isNotEmpty(), averageHex = null, message = null,
                nextPhoto = "ph-step1-${ph.step1.size + 1}",
            )
        }

        return listOf(phView) + NUTRIENTS.map { key ->
            val readings = test.nutrient(key)
            val complete = readings.size == REQUIRED_CAPTURES
            ParameterView(
                key, LABELS.getValue(key), REAGENTS.getValue(key), readings.size, REQUIRED_CAPTURES,
                complete = complete, canCapture = !locked && !complete, canReset = !locked && readings.isNotEmpty(),
                averageHex = if (complete) PhLogic.averageHex(readings) else null, message = null,
                nextPhoto = "$key-${readings.size + 1}",
            )
        }
    }

    fun format(value: Double): String =
        if (value == Math.floor(value)) String.format(Locale.US, "%.1f", value)
        else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
}
