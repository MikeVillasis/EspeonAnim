package io.github.mikevillasis.espeonanim.transport

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Looks for a QR code in CameraX frames using ZXing (pure Java, Apache-2.0; no Play Services).
 * Only the luminance plane is used. QR detection does not depend on image rotation.
 */
class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val buf = plane.buffer
            val data = ByteArray(rowStride * image.height)
            buf.rewind()
            buf.get(data, 0, minOf(buf.remaining(), data.size))
            val source = PlanarYUVLuminanceSource(data, rowStride, image.height, 0, 0, image.width, image.height, false)
            val text = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (_: NotFoundException) {
                null
            } finally {
                reader.reset()
            }
            if (text != null) onText(text)
        } catch (_: Exception) {
            // A bad frame is not worth surfacing; the next one will do.
        } finally {
            image.close()
        }
    }
}
