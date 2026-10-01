package com.joacovvyr.camdroid.effects

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.Closeable
import java.nio.ByteOrder
import java.util.concurrent.Executor

/** Input is an upright bitmap. Output mask has the same orientation and normalized coordinates. */
interface PersonSegmenter : Closeable {
    fun process(frame: Bitmap, executor: Executor, result: (Bitmap?, Exception?) -> Unit)
}

/** Bundled model: no model download, API key, account or network connection. */
class LocalPortraitEngine : PersonSegmenter {
    // Keep the live model input small enough for the S22 to sustain it while
    // preserving the full-resolution camera frame for preview and capture.
    private val maxInferenceDimension = 480
    private val client = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .enableRawSizeMask().build()
    )
    override fun process(frame: Bitmap, executor: Executor, result: (Bitmap?, Exception?) -> Unit) {
        val inferenceFrame = if (maxOf(frame.width, frame.height) > maxInferenceDimension) {
            val scale = maxInferenceDimension.toFloat() / maxOf(frame.width, frame.height).toFloat()
            Bitmap.createScaledBitmap(
                frame,
                (frame.width * scale).toInt().coerceAtLeast(1),
                (frame.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            frame
        }
        client.process(InputImage.fromBitmap(inferenceFrame, 0))
            .addOnSuccessListener(executor) { mask ->
                var rawMask: Bitmap? = null
                try {
                    val width = mask.width
                    val height = mask.height
                    if (width <= 0 || height <= 0) {
                        throw IllegalStateException("ML Kit devolvió una máscara vacía")
                    }
                    val buffer = mask.buffer
                    val requiredBytes = width.toLong() * height.toLong() * Float.SIZE_BYTES
                    val availableBytes = buffer.limit()
                    if (requiredBytes > Int.MAX_VALUE || availableBytes < requiredBytes.toInt()) {
                        throw IllegalStateException(
                            "Máscara IA incompleta (${availableBytes} bytes para ${width}×${height})"
                        )
                    }
                    buffer.rewind()
                    buffer.order(ByteOrder.nativeOrder())
                    val pixels = IntArray(width * height)
                    for (i in pixels.indices) {
                        val confidence = buffer.float.coerceIn(0f, 1f)
                        val value = (confidence * 255).toInt()
                        pixels[i] = android.graphics.Color.rgb(value, value, value)
                    }
                    val raw = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                    rawMask = raw
                    val output = if (raw.width == frame.width && raw.height == frame.height) {
                        rawMask = null
                        raw
                    } else {
                        Bitmap.createScaledBitmap(raw, frame.width, frame.height, true).also {
                            raw.recycle()
                            rawMask = null
                        }
                    }
                    result(output, null)
                } catch (error: Exception) {
                    rawMask?.recycle()
                    result(null, error)
                } finally {
                    if (inferenceFrame !== frame) inferenceFrame.recycle()
                }
            }.addOnFailureListener(executor) {
                if (inferenceFrame !== frame) inferenceFrame.recycle()
                result(null, it)
            }
    }
    override fun close() = client.close()
}
