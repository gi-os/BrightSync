package com.gios.lightsync.qr

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * A QR code off the live stream, decoded on the camera's own thread.
 *
 * Ported from Roll's `qr/QrAnalyzer.kt` — including the row-stride fix, see [luminance]. Copied
 * rather than shared because the agent is the app that has to work when everything else on the
 * phone is broken, and a dependency on the camera app to read its own setup code would be a
 * strange circle. It is sixty lines and it has not changed in a year.
 *
 * **ZXing rather than ML Kit, and that is not a preference.** ML Kit's barcode scanner is the
 * obvious choice on any other Android phone and it is unavailable here: the unbundled model
 * arrives through Play Services, and LightOS ships without GMS, so it would bind and never
 * return a result. ZXing is pure Java, ships inside the APK, and needs nothing from the platform.
 */
class QrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {

    /**
     * QR only, and `TRY_HARDER`.
     *
     * Restricting the format list is most of the speed: [MultiFormatReader] with no hint runs
     * every one-dimensional reader over every row before it reaches the 2-D ones. `TRY_HARDER`
     * buys back the distance — a code on a laptop screen held at arm's length is a small target.
     */
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    /** Counts failures so a broken stream logs three lines rather than thirty a second. */
    private var complaints = 0

    override fun analyze(image: ImageProxy) {
        try {
            val source = luminance(image) ?: return
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            result?.text?.takeIf { it.isNotEmpty() }?.let(onResult)
        } catch (_: com.google.zxing.NotFoundException) {
            // No code in this frame: the overwhelmingly common case. ZXing reports "nothing
            // here" by throwing, so this catch is the normal path and has to stay silent.
        } catch (t: Throwable) {
            if (complaints++ < 3) Log.e(TAG, "decode failed", t)
        } finally {
            reader.reset()
            image.close()
        }
    }

    /**
     * The Y plane as a luminance source, honouring the row stride.
     *
     * A camera plane is padded to a hardware-friendly row length, so `rowStride` is routinely
     * larger than `width` and the buffer is bigger than the picture. Handing that straight to
     * ZXing either throws on the length check or decodes a sheared image where every row is
     * offset a little further than the one above — which reads as "the scanner just doesn't work
     * at some resolutions". So each row is copied out at its own offset.
     *
     * Rotation needs no handling: a QR code is found by its three finder squares, which ZXing
     * locates in two dimensions, so it decodes upside down and sideways alike.
     */
    private fun luminance(image: ImageProxy): PlanarYUVLuminanceSource? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        val buffer = plane.buffer.also { it.rewind() }
        val stride = plane.rowStride
        val pixels = ByteArray(width * height)
        if (stride == width) {
            buffer.get(pixels, 0, minOf(pixels.size, buffer.remaining()))
        } else {
            val row = ByteArray(stride)
            for (y in 0 until height) {
                if (buffer.remaining() < stride) break
                buffer.get(row, 0, stride)
                System.arraycopy(row, 0, pixels, y * width, width)
            }
        }

        return PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
    }

    private companion object {
        const val TAG = "QrAnalyzer"
    }
}
