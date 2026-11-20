package dev.lifeos.app.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.lifeos.app.crypto.Vectors
import dev.lifeos.app.pairing.PairingText
import dev.lifeos.app.ui.components.QrDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The pairing code as the camera would see it: a frame of grey pixels with the code somewhere in it. */
class QrDecoderTest {
    // As long as a real pairing text (the relay address and key are longer than the synthetic ones).
    private val text = Vectors.pairingText + "A".repeat(60)

    private fun frame(width: Int, height: Int, stride: Int, codeSize: Int, inverted: Boolean, cutLastRow: Boolean = false): ByteArray {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, codeSize, codeSize, hints)
        val dark = if (inverted) 0xE0 else 0x18
        val light = if (inverted) 0x18 else 0xE0
        val pixels = ByteArray(stride * height) { 0x70 } // a grey room around the screen
        val left = (width - codeSize) / 2
        val top = (height - codeSize) / 2
        for (y in 0 until codeSize) {
            for (x in 0 until codeSize) {
                pixels[(top + y) * stride + left + x] = (if (matrix.get(x, y)) dark else light).toByte()
            }
        }
        return if (cutLastRow) pixels.copyOf(stride * (height - 1) + width) else pixels
    }

    @Test
    fun `a pairing code in a full-HD frame is read`() {
        assertEquals(text, QrDecoder.decode(frame(1920, 1080, 1920, 700, inverted = false), 1920, 1920, 1080))
    }

    @Test
    fun `a light-on-dark code, as in a dark terminal, is read`() {
        assertEquals(text, QrDecoder.decode(frame(1920, 1080, 1920, 700, inverted = true), 1920, 1920, 1080))
    }

    @Test
    fun `rows padded beyond the picture width and a short last row are handled`() {
        assertEquals(text, QrDecoder.decode(frame(1280, 720, 1344, 500, inverted = false), 1344, 1280, 720))
        assertEquals(text, QrDecoder.decode(frame(1280, 720, 1344, 500, inverted = true, cutLastRow = true), 1344, 1280, 720))
    }

    @Test
    fun `a small code in the frame is still read`() {
        assertEquals(text, QrDecoder.decode(frame(1920, 1080, 1920, 330, inverted = false), 1920, 1920, 1080))
    }

    @Test
    fun `what is read is a valid pairing text`() {
        val plain = Vectors.pairingText
        val hints = mapOf(EncodeHintType.MARGIN to 2)
        val matrix = QRCodeWriter().encode(plain, BarcodeFormat.QR_CODE, 400, 400, hints)
        val pixels = ByteArray(400 * 400) { if (matrix.get(it % 400, it / 400)) 0 else -1 }
        assertNotNull(PairingText.parse(QrDecoder.decode(pixels, 400, 400, 400)!!))
    }

    @Test
    fun `frames without a code or with impossible sizes give nothing`() {
        assertNull(QrDecoder.decode(ByteArray(640 * 480) { 0x70 }, 640, 640, 480))
        assertNull(QrDecoder.decode(ByteArray(10), 640, 640, 480))
        assertNull(QrDecoder.decode(ByteArray(100), 5, 10, 10))
        assertNull(QrDecoder.decode(ByteArray(0), 0, 0, 0))
    }
}
