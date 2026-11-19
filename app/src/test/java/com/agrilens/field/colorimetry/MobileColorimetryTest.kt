package com.agrilens.field.colorimetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileColorimetryTest {

    private val config = ColorimetricConfig(
        version = "v1",
        updated_at = "2026-11-16T10:00:00+00:00",
        reaction_seconds = 480,
        ph_charts = mapOf(
            "CPR" to listOf(ChartPoint("#E8A040", 5.0), ChartPoint("#B05A3C", 5.4), ChartPoint("#7A2A36", 5.8), ChartPoint("#5A2E36", 6.0)),
            "BCG" to listOf(ChartPoint("#4E6B30", 4.0), ChartPoint("#2E4A3E", 4.4), ChartPoint("#2E7A6A", 4.8), ChartPoint("#3A4A8A", 5.2)),
            "BTB" to listOf(ChartPoint("#7A7A3A", 6.0), ChartPoint("#4E7A3A", 6.4), ChartPoint("#2E5A3E", 6.8), ChartPoint("#2E8A7A", 7.2), ChartPoint("#3A4E8A", 7.6)),
        ),
        npk_charts = mapOf(
            "N" to listOf(ChartPoint("#C83C28", 30.0), ChartPoint("#2E7A3A", 100.0), ChartPoint("#2E4E8A", 200.0)),
            "P" to listOf(ChartPoint("#8ACCE0", 8.0), ChartPoint("#2E6AB0", 22.0), ChartPoint("#1E2A3A", 40.0)),
            "K" to listOf(ChartPoint("#E07A3A", 10.0), ChartPoint("#C8603A", 30.0), ChartPoint("#B04A2A", 50.0)),
        ),
    )

    private fun color(hex: String): TubeColor {
        val rgb = ColorScience.hexToRgb(hex)
        return TubeColor(hex, rgb[0], rgb[1], rgb[2], "tube", 500, 40)
    }

    private fun newTest() = MobileTest("00000000-0000-4000-8000-000000000001", 1, "Farm", null, null, 0, "v1")

    private fun captureAll(test: MobileTest, key: String, hex: String) {
        repeat(3) {
            val photo = MobileColorimetry.views(test).first { it.key == key }.nextPhoto!!
            MobileColorimetry.addCapture(test, key, color(hex), photo, config)
        }
    }

    @Test
    fun acidicCprGoesToBcgThenCompletes() {
        val test = newTest()
        captureAll(test, "ph", "#E8A040") // CPR 5.0 → ≤ 5.4 → BCG
        assertEquals(PhProgress.STATE_STEP2, test.ph.state)
        assertEquals("BCG", test.ph.step2_solution)
        assertEquals(5.0, test.ph.step1_ph!!, 1e-9)
        assertEquals(listOf("ph-step1-1", "ph-step1-2", "ph-step1-3"), test.ph.step1.map { it.photo })

        captureAll(test, "ph", "#2E7A6A") // BCG 4.8
        assertEquals(PhProgress.STATE_COMPLETE, test.ph.state)
        assertEquals(4.8, test.ph.final_ph!!, 1e-9)
        assertEquals("ph-step2-3", test.ph.step2.last().photo)
    }

    @Test
    fun transitionalCprIsFinal() {
        val test = newTest()
        captureAll(test, "ph", "#7A2A36") // CPR 5.8 → 5.4 < pH ≤ 5.8 → CPR final
        assertEquals(PhProgress.STATE_COMPLETE, test.ph.state)
        assertNull(test.ph.step2_solution)
        assertEquals(5.8, test.ph.final_ph!!, 1e-9)
    }

    @Test
    fun syncReadyOnlyWhenEverythingIsCapturedAndResetClears() {
        val test = newTest()
        captureAll(test, "ph", "#7A2A36")
        captureAll(test, "nitrogen", "#2E7A3A")
        captureAll(test, "phosphorus", "#2E6AB0")
        assertFalse(MobileColorimetry.isComplete(test))
        captureAll(test, "potassium", "#C8603A")
        assertTrue(MobileColorimetry.isComplete(test))
        assertEquals(12, MobileColorimetry.capturesDone(test))

        val levels = MobileColorimetry.levels(test, config)
        assertEquals(5.8, levels.getValue("ph"), 1e-9)
        assertEquals(100.0, levels.getValue("nitrogen"), 1e-9)
        assertEquals(22.0, levels.getValue("phosphorus"), 1e-9)
        assertEquals(30.0, levels.getValue("potassium"), 1e-9)

        val removed = MobileColorimetry.reset(test, "nitrogen")
        assertEquals(listOf("nitrogen-1", "nitrogen-2", "nitrogen-3"), removed)
        assertFalse(MobileColorimetry.isComplete(test))
        assertEquals("nitrogen-1", MobileColorimetry.views(test).first { it.key == "nitrogen" }.nextPhoto)
    }

    @Test
    fun outOfRangeCprNeedsRetest() {
        assertEquals(PhLogic.RETEST, PhLogic.decideSolution(3.9))
        assertEquals(PhLogic.RETEST, PhLogic.decideSolution(7.9))
        assertEquals("BCG", PhLogic.decideSolution(5.4))
        assertEquals("CPR", PhLogic.decideSolution(5.6))
        assertEquals("BTB", PhLogic.decideSolution(5.81))
    }

    @Test
    fun missingChartsAreReported() {
        val partial = config.copy(npk_charts = config.npk_charts - "K")
        assertEquals(listOf("Potassium (K)"), partial.missingCharts())
        assertTrue(config.missingCharts().isEmpty())
    }
}
