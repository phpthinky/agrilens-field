package com.agrilens.field.colorimetry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Checks the Kotlin port against the server's own PHP output on real
 * colorimeter photos. Fixtures (photos, GD's 320×240 canvases, and
 * fixtures.json with the server's colours and values) are produced on the
 * server by a helper script; point AGRILENS_PARITY_DIR at them. Skipped when unset.
 */
class ServerParityTest {

    private lateinit var dir: File
    private lateinit var fixtures: JsonObject
    private lateinit var config: ColorimetricConfig

    @Before
    fun load() {
        val path = System.getenv("AGRILENS_PARITY_DIR")
        assumeTrue("AGRILENS_PARITY_DIR not set", !path.isNullOrBlank())
        dir = File(path!!)
        fixtures = JsonParser.parseString(File(dir, "fixtures.json").readText()).asJsonObject

        val charts = fixtures.getAsJsonObject("charts")
        fun chart(obj: JsonObject) = obj.entrySet().associate { (key, list) ->
            key to list.asJsonArray.map { ChartPoint(it.asJsonObject["hex"].asString, it.asJsonObject["value"].asDouble) }
        }
        config = ColorimetricConfig("test", null, 480, chart(charts.getAsJsonObject("ph")), chart(charts.getAsJsonObject("npk")))
    }

    /** Decode with the desktop JVM's ImageIO (by reflection: it isn't on Android's compile classpath). */
    private fun pixels(file: File): Triple<IntArray, Int, Int> {
        val image = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, file)
        val cls = image.javaClass
        val w = cls.getMethod("getWidth").invoke(image) as Int
        val h = cls.getMethod("getHeight").invoke(image) as Int
        val getRgb = cls.getMethod(
            "getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            IntArray::class.java, Int::class.java, Int::class.java,
        )
        return Triple(getRgb.invoke(image, 0, 0, w, h, null, 0, w) as IntArray, w, h)
    }

    @Test
    fun tubeColourFromGdCanvasMatchesExactly() {
        for (case in fixtures.getAsJsonArray("cases").map { it.asJsonObject }) {
            val name = case["name"].asString
            val (canvas, _, _) = pixels(File(dir, "$name-canvas.png"))
            val got = SnapshotColor.sampleCanvas(canvas)
            val want = case.getAsJsonObject("color")
            assertEquals("$name hex", want["hex"].asString, got.hex)
            assertEquals("$name method", want["method"].asString, got.method)
            assertEquals("$name pixels", want["pixels"].asInt, got.pixels)
            assertEquals("$name bands", want["bands"].asInt, got.bands)
        }
    }

    /** Full photo → our resample → pick. Only the JPEG decoder differs from the server, so allow ±2 per channel. */
    @Test
    fun tubeColourFromFullPhotoIsWithinJpegDecoderNoise() {
        for (case in fixtures.getAsJsonArray("cases").map { it.asJsonObject }) {
            val name = case["name"].asString
            val (photo, w, h) = pixels(File(dir, "$name.jpg"))
            val got = SnapshotColor.sampleTube(photo, w, h)
            val want = case.getAsJsonObject("color")
            assertEquals("$name method", want["method"].asString, got.method)
            for ((channel, value) in listOf("r" to got.r, "g" to got.g, "b" to got.b)) {
                assertTrue("$name $channel ${want[channel].asInt} vs $value", abs(want[channel].asInt - value) <= 2)
            }
        }
    }

    @Test
    fun resampleMatchesGd() {
        for (case in fixtures.getAsJsonArray("cases").map { it.asJsonObject }) {
            val name = case["name"].asString
            val (photo, w, h) = pixels(File(dir, "$name.jpg"))
            if (w == SnapshotColor.CANVAS_WIDTH && h == SnapshotColor.CANVAS_HEIGHT) continue
            val (gd, _, _) = pixels(File(dir, "$name-canvas.png"))
            val ours = SnapshotColor.resample(photo, w, h, SnapshotColor.CANVAS_WIDTH, SnapshotColor.CANVAS_HEIGHT)
            var worst = 0
            for (i in gd.indices) {
                for (shift in intArrayOf(16, 8, 0)) {
                    worst = maxOf(worst, abs(((gd[i] shr shift) and 0xFF) - ((ours[i] shr shift) and 0xFF)))
                }
            }
            assertTrue("$name worst channel difference $worst", worst <= 3)
        }
    }

    @Test
    fun chartMatchingMatchesExactly() {
        val rows = fixtures.getAsJsonArray("cases").map {
            val c = it.asJsonObject
            Triple(c["name"].asString, c.getAsJsonObject("color")["hex"].asString, c)
        } + fixtures.getAsJsonArray("colors").map {
            val c = it.asJsonObject
            Triple(c["hex"].asString, c["hex"].asString, c)
        }

        for ((name, hex, row) in rows) {
            for ((indicator, want) in row.getAsJsonObject("ph").entrySet()) {
                val got = ColorScience.indicatorPh(hex, config.phChart(indicator))
                val w = want.asJsonObject
                assertEquals("$name $indicator ph", w["ph"].asDouble, got.ph, 1e-9)
                assertEquals("$name $indicator confidence", w["confidence_pct"].asInt, got.confidencePct)
                w["chart_ph"]?.let {
                    assertEquals("$name $indicator chart_ph", it.asDouble, PhLogic.snapToChartPh(got.ph, config.phChart(indicator)), 1e-9)
                }
            }
            for ((code, want) in row.getAsJsonObject("npk").entrySet()) {
                val got = ColorScience.nutrientLevel(hex, config.npk_charts.getValue(code))
                assertEquals("$name $code ppm", want.asDouble, got, 1e-9)
            }
        }
    }

    @Test
    fun phStatsAndDecisionMatch() {
        for (row in fixtures.getAsJsonArray("stats").map { it.asJsonObject }) {
            val values = row.getAsJsonArray("values").map { it.asDouble }
            val got = PhLogic.computeStats(values)
            val want = row.getAsJsonObject("stats")
            assertEquals("$values average", want["average"].asDouble, got.average, 1e-9)
            assertEquals("$values variance", want["variance"].asDouble, got.variance, 1e-9)
            assertEquals("$values confidence", want["confidence"].asString, got.confidence)
            assertEquals("$values decision", row["decide"].asString, PhLogic.decideSolution(got.average))
        }
    }
}
