package com.agrilens.field.colorimetry

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The colour picked from one webcam photo. method = "tube" (liquid found) or "center" (fallback strip). */
data class TubeColor(
    val hex: String,
    val r: Int,
    val g: Int,
    val b: Int,
    val method: String,
    val pixels: Int,
    val bands: Int,
)

/**
 * Port of the server's SnapshotColorService (app/Services/SnapshotColorService.php),
 * itself the twin of the web colorimeter's public/js/colorimeter-sample.js — keep
 * all three in step.
 *
 * The photo is shrunk to 320×240 the way PHP-GD's imagecopyresampled does it,
 * the tube is found by colour saturation, the middle 40 % of its width is
 * averaged, and glints and specks are dropped. A colorless liquid falls back
 * to a narrow centre strip.
 *
 * Pure Kotlin on ARGB int arrays, so it also runs in JVM unit tests.
 */
object SnapshotColor {

    const val CANVAS_WIDTH = 320
    const val CANVAS_HEIGHT = 240

    private const val MARGIN = 0.10
    private const val BAND = 4
    private const val SMOOTH = 2
    private const val SAT_MIN = 40.0
    private const val SAT_ABOVE_MEDIAN = 30.0
    private const val MIN_TUBE = 0.05
    private const val MAX_OFFSET = 0.25
    private const val MIN_BANDS = 0.25
    private const val CORE = 0.20
    private const val STRIP = 30.0 / 320.0
    private const val DROP_DARK = 0.03
    private const val DROP_BRIGHT = 0.95
    private const val GLARE = 250
    private const val MIN_PIXELS = 50
    private const val DARK = 40
    private const val BAND_DIM = 0.6
    private const val BAND_PALE = 0.6

    /** Sample a full-size photo given as ARGB pixels (Bitmap.getPixels order). */
    fun sampleTube(argb: IntArray, width: Int, height: Int): TubeColor =
        sampleCanvas(resample(argb, width, height, CANVAS_WIDTH, CANVAS_HEIGHT))

    /** Sample an already-320×240 ARGB canvas. */
    fun sampleCanvas(canvas: IntArray): TubeColor {
        val w = CANVAS_WIDTH
        val h = CANVAS_HEIGHT
        require(canvas.size == w * h) { "Canvas must be ${w}×$h." }

        val rr = IntArray(w * h) { (canvas[it] shr 16) and 0xFF }
        val gg = IntArray(w * h) { (canvas[it] shr 8) and 0xFF }
        val bb = IntArray(w * h) { canvas[it] and 0xFF }

        val top = ColorScience.phpRoundInt(h * MARGIN)
        val bottom = h - top

        val bands = mutableListOf<IntArray>() // [y, x1, x2]
        var total = 0
        var y = top
        while (y + BAND <= bottom) {
            total++
            bandRun(rr, gg, bb, w, y, y + BAND)?.let { bands += intArrayOf(y, it.first, it.second) }
            y += BAND
        }

        var boxes = bands.map { (by, x1, x2) ->
            val c = Math.floorDiv(x1 + x2, 2)
            val half = max(2, floor((x2 - x1 + 1) * CORE).toInt())
            intArrayOf(c - half, c + half, by, by + BAND)
        }
        // Drop bands that don't look like the rest of the liquid: far darker
        // (cap or label edge, shadow at the surface) or far paler (glass rim,
        // the holder below the liquid, air above it).
        if (boxes.isNotEmpty()) {
            val stats = boxes.map { boxStats(rr, gg, bb, w, it) }
            val mv = median(stats.map { it.first })
            val ms = median(stats.map { it.second })
            boxes = boxes.filterIndexed { i, _ -> stats[i].first >= mv * BAND_DIM && stats[i].second >= ms * BAND_PALE }
        }

        val tube = boxes.size >= ceil(total * MIN_BANDS).toInt()
        if (!tube) {
            val sw = max(4, ColorScience.phpRoundInt(w * STRIP))
            val x1 = Math.floorDiv(w - sw, 2)
            boxes = listOf(intArrayOf(x1, x1 + sw, top, bottom))
        }

        val px = ArrayList<IntArray>() // [r, g, b, v]
        for ((x1, x2, y1, y2) in boxes) {
            for (yy in y1 until y2) {
                for (x in x1 until x2) {
                    val i = yy * w + x
                    px += intArrayOf(rr[i], gg[i], bb[i], max(rr[i], max(gg[i], bb[i])))
                }
            }
        }

        val vs = px.map { it[3] }.sorted()
        val n = vs.size
        val lo = vs[floor(n * DROP_DARK).toInt()]
        val hi = vs[floor(n * DROP_BRIGHT).toInt()]

        var keep = px.filter { it[3] in lo..hi && it[3] < GLARE }
        if (keep.size < MIN_PIXELS) keep = px.filter { it[3] < GLARE }
        if (keep.size < MIN_PIXELS) keep = px

        val k = keep.size
        val r = ColorScience.phpRoundInt(keep.sumOf { it[0] }.toDouble() / k)
        val g = ColorScience.phpRoundInt(keep.sumOf { it[1] }.toDouble() / k)
        val b = ColorScience.phpRoundInt(keep.sumOf { it[2] }.toDouble() / k)

        return TubeColor(
            hex = ColorScience.toHex(r, g, b),
            r = r,
            g = g,
            b = b,
            method = if (tube) "tube" else "center",
            pixels = k,
            bands = if (tube) boxes.size else 0,
        )
    }

    /** [mean V, saturation of the mean colour] of a box [x1, x2) × [y1, y2). */
    private fun boxStats(rr: IntArray, gg: IntArray, bb: IntArray, w: Int, box: IntArray): Pair<Double, Double> {
        val (x1, x2, y1, y2) = box
        var v = 0L
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (y in y1 until y2) {
            for (x in x1 until x2) {
                val i = y * w + x
                v += max(rr[i], max(gg[i], bb[i]))
                r += rr[i]
                g += gg[i]
                b += bb[i]
                n++
            }
        }
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        return v.toDouble() / n to (if (mx == 0L) 0.0 else (mx - mn).toDouble() / mx)
    }

    private fun median(list: List<Double>): Double = list.sorted()[list.size / 2]

    /** Tube run [x1, x2] in rows [y0, y1), or null. */
    private fun bandRun(rr: IntArray, gg: IntArray, bb: IntArray, w: Int, y0: Int, y1: Int): Pair<Int, Int>? {
        val col = IntArray(w)
        for (y in y0 until y1) {
            for (x in 0 until w) {
                val i = y * w + x
                val mx = max(rr[i], max(gg[i], bb[i]))
                val mn = min(rr[i], min(gg[i], bb[i]))
                col[x] += if (mx < DARK) 0 else (mx - mn) * 255 / mx
            }
        }

        val rows = y1 - y0
        val s = DoubleArray(w)
        for (x in 0 until w) {
            var sum = 0.0
            var cnt = 0
            for (k in max(0, x - SMOOTH)..min(w - 1, x + SMOOTH)) {
                sum += col[k].toDouble() / rows
                cnt++
            }
            s[x] = sum / cnt
        }

        val sorted = s.sorted()
        val thr = max(SAT_MIN, sorted[w / 2] + SAT_ABOVE_MEDIAN)

        val mid = w / 2
        var best: Pair<Int, Int>? = null
        var bestDist = Int.MAX_VALUE
        var x = 0
        while (x < w) {
            if (s[x] <= thr) {
                x++
                continue
            }
            var x2 = x
            while (x2 + 1 < w && s[x2 + 1] > thr) x2++
            val dist = if (mid < x) x - mid else if (mid > x2) mid - x2 else 0
            if (x2 - x + 1 >= ColorScience.phpRoundInt(w * MIN_TUBE) && dist < bestDist) {
                best = x to x2
                bestDist = dist
            }
            x = x2 + 1
        }

        if (best == null || bestDist > ColorScience.phpRoundInt(w * MAX_OFFSET)) return null
        return best
    }

    /**
     * Shrink like PHP-GD 2.3's gdImageCopyResampled: each destination pixel is
     * the area-weighted average of the source pixels it covers, rounded.
     */
    fun resample(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        val out = IntArray(dw * dh)
        for (y in 0 until dh) {
            val sy1 = y.toDouble() * sh / dh
            val sy2 = (y + 1).toDouble() * sh / dh
            for (x in 0 until dw) {
                val sx1 = x.toDouble() * sw / dw
                val sx2 = (x + 1).toDouble() * sw / dw
                var red = 0.0
                var green = 0.0
                var blue = 0.0
                var spixels = 0.0

                var sy = sy1
                do {
                    val yportion: Double
                    if (floor(sy) == floor(sy1)) {
                        var p = 1.0 - (sy - floor(sy))
                        if (p > sy2 - sy1) p = sy2 - sy1
                        yportion = p
                        sy = floor(sy)
                    } else if (sy == floor(sy2)) {
                        yportion = sy2 - floor(sy2)
                    } else {
                        yportion = 1.0
                    }

                    var sx = sx1
                    do {
                        val xportion: Double
                        if (floor(sx) == floor(sx1)) {
                            var p = 1.0 - (sx - floor(sx))
                            if (p > sx2 - sx1) p = sx2 - sx1
                            xportion = p
                            sx = floor(sx)
                        } else if (sx == floor(sx2)) {
                            xportion = sx2 - floor(sx2)
                        } else {
                            xportion = 1.0
                        }

                        val contribution = xportion * yportion
                        val sxi = min(sw - 1, sx.toInt())
                        val syi = min(sh - 1, sy.toInt())
                        val p = src[syi * sw + sxi]
                        red += ((p shr 16) and 0xFF) * contribution
                        green += ((p shr 8) and 0xFF) * contribution
                        blue += (p and 0xFF) * contribution
                        spixels += contribution
                        sx += 1.0
                    } while (sx < sx2)
                    sy += 1.0
                } while (sy < sy2)

                fun channel(v: Double) = min(255, (v / spixels + 0.5).toInt())
                out[y * dw + x] = (0xFF shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
            }
        }
        return out
    }
}
