package dev.lifeos.app.ui.components

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Reads a QR code from grey pixels. No Android class is used, so it is unit-tested on the JVM. */
object QrDecoder {
    private val HINTS = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * [luminance] holds [height] rows of [stride] bytes of which the first [width] are pixels
     * (the last row may end after its pixels). A terminal shows the code dark-on-light or
     * light-on-dark, so both are tried.
     */
    fun decode(luminance: ByteArray, stride: Int, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || stride < width) return null
        if (luminance.size < stride * (height - 1) + width) return null
        // Give the reader a full rectangle.
        val full = if (luminance.size >= stride * height) luminance else luminance.copyOf(stride * height)
        val source = PlanarYUVLuminanceSource(full, stride, height, 0, 0, width, height, false)
        return read(source) ?: read(source.invert())
    }

    private fun read(source: LuminanceSource): String? = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), HINTS).text
    } catch (_: ReaderException) {
        null
    }
}
