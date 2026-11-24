package io.github.mikevillasis.espeonanim.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import io.github.mikevillasis.espeonanim.shared.guidance.LumaGrid

private const val GRID_BASE = 48

/** The frame as an upright (rotated by [rotation] degrees) bitmap, as the MediaPipe landmarkers expect. */
internal fun ImageProxy.toUprightBitmap(rotation: Int): Bitmap {
    val bitmap = toBitmap()
    if (rotation == 0) return bitmap
    val m = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
}

/** Builds an upright, down-sampled luma image from the Y plane. */
internal fun ImageProxy.uprightLumaGrid(rotation: Int): LumaGrid {
    val image = this
    val plane = image.planes[0]
    val buf = plane.buffer
    val rowStride = plane.rowStride
    val pixStride = plane.pixelStride
    val w = image.width
    val h = image.height
    val uw = if (rotation == 90 || rotation == 270) h else w
    val uh = if (rotation == 90 || rotation == 270) w else h
    val gw = GRID_BASE
    val gh = (GRID_BASE.toFloat() * uh / uw).toInt().coerceAtLeast(8)
    val out = ByteArray(gw * gh)
    for (gy in 0 until gh) {
        val v = ((gy + 0.5f) / gh * uh).toInt().coerceIn(0, uh - 1)
        for (gx in 0 until gw) {
            val u = ((gx + 0.5f) / gw * uw).toInt().coerceIn(0, uw - 1)
            val sx: Int
            val sy: Int
            when (rotation) {
                90 -> { sx = v; sy = h - 1 - u }
                180 -> { sx = w - 1 - u; sy = h - 1 - v }
                270 -> { sx = w - 1 - v; sy = u }
                else -> { sx = u; sy = v }
            }
            out[gy * gw + gx] = buf.get(sy * rowStride + sx * pixStride)
        }
    }
    return LumaGrid(gw, gh, out)
}
