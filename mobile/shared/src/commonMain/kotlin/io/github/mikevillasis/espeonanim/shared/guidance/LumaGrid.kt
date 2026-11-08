package io.github.mikevillasis.espeonanim.shared.guidance

import kotlin.math.max
import kotlin.math.min

/**
 * A small, upright, down-sampled luma image (e.g. 64x36) built by the platform camera layer
 * from the Y plane. Lets the shared code compute brightness and body/background contrast.
 */
class LumaGrid(val width: Int, val height: Int, private val data: ByteArray) {
    init {
        require(width > 0 && height > 0 && data.size == width * height) { "LumaGrid size mismatch" }
    }

    fun at(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF

    fun mean(): Float {
        var sum = 0L
        for (b in data) sum += b.toInt() and 0xFF
        return sum.toFloat() / data.size
    }

    private fun sample(nx: Float, ny: Float): Int {
        val x = (nx * width).toInt().coerceIn(0, width - 1)
        val y = (ny * height).toInt().coerceIn(0, height - 1)
        return at(x, y)
    }

    /**
     * Estimated clothing/background contrast: mean luma along the torso and legs (points sampled
     * on the skeleton) versus mean luma of strips just left and right of the body.
     * Returns |foreground - background| in 0..255, or null if the pose is too small or the
     * strips fall outside the frame.
     */
    fun bodyContrast(lm: List<Landmark>): Float? {
        if (lm.size < Lm.COUNT) return null
        val segments = listOf(
            Lm.LEFT_SHOULDER to Lm.LEFT_HIP, Lm.RIGHT_SHOULDER to Lm.RIGHT_HIP,
            Lm.LEFT_HIP to Lm.LEFT_KNEE, Lm.RIGHT_HIP to Lm.RIGHT_KNEE,
            Lm.LEFT_KNEE to Lm.LEFT_ANKLE, Lm.RIGHT_KNEE to Lm.RIGHT_ANKLE,
            Lm.LEFT_SHOULDER to Lm.RIGHT_HIP, Lm.RIGHT_SHOULDER to Lm.LEFT_HIP,
        )
        var fgSum = 0L
        var fgN = 0
        for ((a, b) in segments) {
            val p = lm[a]; val q = lm[b]
            if (p.visibility < 0.3f || q.visibility < 0.3f) continue
            for (i in 0..6) {
                val f = i / 6f
                val x = p.x + (q.x - p.x) * f
                val y = p.y + (q.y - p.y) * f
                if (x in 0f..1f && y in 0f..1f) { fgSum += sample(x, y); fgN++ }
            }
        }
        if (fgN < 8) return null

        var minX = 1f; var maxX = 0f; var minY = 1f; var maxY = 0f
        for (l in lm) {
            if (l.visibility < 0.3f) continue
            minX = min(minX, l.x); maxX = max(maxX, l.x)
            minY = min(minY, l.y); maxY = max(maxY, l.y)
        }
        minY = max(0f, minY); maxY = min(1f, maxY)
        if (maxY - minY < 0.1f) return null
        val strip = 0.12f
        val gap = 0.03f
        var bgSum = 0L
        var bgN = 0
        fun strip(x0: Float, x1: Float) {
            val xa = max(0f, min(x0, x1)); val xb = min(1f, max(x0, x1))
            if (xb - xa < 0.02f) return
            val steps = 6
            for (ix in 0 until steps) for (iy in 0 until 10) {
                val x = xa + (xb - xa) * (ix + 0.5f) / steps
                val y = minY + (maxY - minY) * (iy + 0.5f) / 10f
                bgSum += sample(x, y); bgN++
            }
        }
        strip(minX - gap - strip, minX - gap)
        strip(maxX + gap, maxX + gap + strip)
        if (bgN == 0) return null
        val fg = fgSum.toFloat() / fgN
        val bg = bgSum.toFloat() / bgN
        return kotlin.math.abs(fg - bg)
    }
}
