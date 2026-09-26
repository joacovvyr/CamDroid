package com.joacovvyr.camdroid.effects

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.Closeable
import java.util.concurrent.Executor

/** Input is an upright bitmap. Output mask has the same orientation and normalized coordinates. */
interface PersonSegmenter : Closeable {
    fun process(frame: Bitmap, executor: Executor, result: (Bitmap?, Exception?) -> Unit)
}

/** Bundled model: no model download, API key, account or network connection. */
class LocalPortraitEngine : PersonSegmenter {
    private val client = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .enableRawSizeMask().build()
    )
    override fun process(frame: Bitmap, executor: Executor, result: (Bitmap?, Exception?) -> Unit) {
        client.process(InputImage.fromBitmap(frame, 0))
            .addOnSuccessListener(executor) { mask ->
                val buffer = mask.buffer
                buffer.rewind()
                val pixels = IntArray(mask.width * mask.height)
                for (i in pixels.indices) {
                    val confidence = buffer.float.coerceIn(0f, 1f)
                    val value = (confidence * 255).toInt()
                    pixels[i] = android.graphics.Color.rgb(value, value, value)
                }
                result(Bitmap.createBitmap(pixels, mask.width, mask.height, Bitmap.Config.ARGB_8888), null)
            }.addOnFailureListener(executor) { result(null, it) }
    }
    override fun close() = client.close()
}
