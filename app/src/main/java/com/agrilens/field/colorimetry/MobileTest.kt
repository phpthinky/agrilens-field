package com.agrilens.field.colorimetry

/** Colour charts downloaded from GET /api/colorimetric/config. */
data class ColorimetricConfig(
    val version: String,
    val updated_at: String?,
    val reaction_seconds: Int,
    val ph_charts: Map<String, List<ChartPoint>>,
    val npk_charts: Map<String, List<ChartPoint>>,
) {
    fun phChart(indicator: String): List<ChartPoint> = ph_charts[indicator].orEmpty()

    fun npkChart(parameter: String): List<ChartPoint> = npk_charts[NUTRIENT_CODES[parameter] ?: parameter].orEmpty()

    /** Names of charts with no active colours (readings for them can't be made). */
    fun missingCharts(): List<String> =
        listOf("CPR", "BCG", "BTB").filter { phChart(it).isEmpty() }.map { "$it pH" } +
            NUTRIENT_CODES.filter { npkChart(it.key).isEmpty() }.map { LABELS.getValue(it.key) }
}

/** What's saved on the phone: the config plus when it was downloaded. */
data class StoredConfig(val config: ColorimetricConfig, val downloaded_at: Long)

/** One photo's reading. [photo] is the file name (without .jpg) in the test's folder. */
data class CaptureReading(
    val photo: String,
    val hex: String,
    val r: Int,
    val g: Int,
    val b: Int,
    val computed_value: Double,
    val confidence_pct: Int? = null,
    val chart_ph: Double? = null,
    val zone: String,
    val captured_at: Long,
)

/** The BSWM two-step pH test. state: step1 → step2 | complete | retest. */
data class PhProgress(
    var state: String = STATE_STEP1,
    var step1: MutableList<CaptureReading> = mutableListOf(),
    var step1_ph: Double? = null,
    var step1_confidence: String? = null,
    var step2_solution: String? = null,
    var step2: MutableList<CaptureReading> = mutableListOf(),
    var step2_ph: Double? = null,
    var final_ph: Double? = null,
) {
    companion object {
        const val STATE_STEP1 = "step1"
        const val STATE_STEP2 = "step2"
        const val STATE_COMPLETE = "complete"
        const val STATE_RETEST = "retest"
    }
}

/**
 * A mobile colorimetric test kept on the phone until it's synced. [uuid] is
 * sent as client_uuid, so a retried upload never creates a second sample.
 */
data class MobileTest(
    val uuid: String,
    val farm_id: Long,
    val farm_name: String?,
    val farm_meta: String?,
    val sample_name: String?,
    val created_at: Long,
    var config_version: String?,
    var ph: PhProgress = PhProgress(),
    var nutrients: MutableMap<String, MutableList<CaptureReading>> = mutableMapOf(),
    var synced_sample_id: Long? = null,
    var synced_at: Long? = null,
    var sync_error: String? = null,
) {
    val isSynced: Boolean get() = synced_sample_id != null

    fun nutrient(key: String): MutableList<CaptureReading> =
        nutrients.getOrPut(key) { mutableListOf() }
}

const val REQUIRED_CAPTURES = 3
val NUTRIENTS = listOf("nitrogen", "phosphorus", "potassium")
val PARAMETERS = listOf("ph") + NUTRIENTS
val NUTRIENT_CODES = mapOf("nitrogen" to "N", "phosphorus" to "P", "potassium" to "K")
val LABELS = mapOf(
    "ph" to "pH",
    "nitrogen" to "Nitrogen (N)",
    "phosphorus" to "Phosphorus (P)",
    "potassium" to "Potassium (K)",
)
val REAGENTS = mapOf(
    "nitrogen" to "Nitrogen Reagent (N-Reagent)",
    "phosphorus" to "Phosphorus Reagent (P-Reagent)",
    "potassium" to "Potassium Reagent (K-Reagent)",
)
