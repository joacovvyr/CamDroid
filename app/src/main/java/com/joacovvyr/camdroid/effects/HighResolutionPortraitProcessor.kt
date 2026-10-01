package com.joacovvyr.camdroid.effects

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.Closeable
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min

/**
 * Processes a still from CameraX ImageCapture. The sensor JPEG supplies every output pixel;
 * only the low-resolution portrait mask and blurred background are interpolated.
 */
class HighResolutionPortraitProcessor : Closeable {
    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask().build()
    )

    fun process(jpeg: ByteArray, rotation: Int, mirror: Boolean, intensity: Float,
                executor: Executor, complete: (Bitmap?, Exception?) -> Unit) {
        var source: Bitmap? = null
        var inference: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample * (bounds.outHeight / sample) > 20_000_000) sample *= 2
            source = BitmapFactory.decodeByteArray(
                jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            ) ?: error("No se pudo decodificar la foto")
            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
                if (mirror) postScale(-1f, 1f)
            }
            val upright = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            if (upright !== source) source.recycle()
            source = null
            val inferenceScale = min(1f, 640f / max(upright.width, upright.height))
            inference = Bitmap.createScaledBitmap(
                upright,
                max(1, (upright.width * inferenceScale).toInt()),
                max(1, (upright.height * inferenceScale).toInt()), true
            )
            val ownedInference = inference
            val ownedPhoto = upright
            segmenter.process(InputImage.fromBitmap(ownedInference, 0))
                .addOnSuccessListener(executor) { mask ->
                    try {
                        val floats = FloatArray(mask.width * mask.height)
                        val buffer = mask.buffer
                        buffer.rewind()
                        for (i in floats.indices) floats[i] = buffer.float.coerceIn(0f, 1f)
                        val result = compose(ownedPhoto, floats, mask.width, mask.height, intensity)
                        complete(result, null)
                    } catch (error: Exception) {
                        complete(null, error)
                    } finally {
                        ownedPhoto.recycle()
                        if (ownedInference !== ownedPhoto) ownedInference.recycle()
                    }
                }.addOnFailureListener(executor) { error ->
                    ownedPhoto.recycle()
                    if (ownedInference !== ownedPhoto) ownedInference.recycle()
                    complete(null, error)
                }
        } catch (error: Exception) {
            source?.recycle()
            inference?.recycle()
            complete(null, error)
        }
    }

    private fun compose(photo: Bitmap, mask: FloatArray, maskWidth: Int, maskHeight: Int,
                        strength: Float): Bitmap {
        val width = photo.width
        val height = photo.height
        if (strength <= 0f) return photo.copy(Bitmap.Config.ARGB_8888, false)
        val lowWidth = max(1, width / 8)
        val lowHeight = max(1, height / 8)
        val small = Bitmap.createScaledBitmap(photo, lowWidth, lowHeight, true)
        val background = IntArray(lowWidth * lowHeight)
        small.getPixels(background, 0, lowWidth, 0, 0, lowWidth, lowHeight)
        small.recycle()
        val radius = (2 + strength.coerceIn(0f, 1f) * 5).toInt()
        val blurred = blurBackground(background, lowWidth, lowHeight, radius, mask, maskWidth, maskHeight)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        val result = IntArray(width)
        for (y in 0 until height) {
            photo.getPixels(row, 0, width, 0, y, width, 1)
            val maskY = (y + 0.5f) * maskHeight / height - 0.5f
            val blurY = (y + 0.5f) * lowHeight / height - 0.5f
            for (x in 0 until width) {
                val maskX = (x + 0.5f) * maskWidth / width - 0.5f
                val confidence = sampleMask(mask, maskWidth, maskHeight, maskX, maskY)
                val person = smoothstep(0.12f, 0.82f, confidence)
                val blurX = (x + 0.5f) * lowWidth / width - 0.5f
                val bg = sampleColor(blurred, lowWidth, lowHeight, blurX, blurY)
                val fg = row[x]
                result[x] = android.graphics.Color.rgb(
                    blend(android.graphics.Color.red(bg), android.graphics.Color.red(fg), person),
                    blend(android.graphics.Color.green(bg), android.graphics.Color.green(fg), person),
                    blend(android.graphics.Color.blue(bg), android.graphics.Color.blue(fg), person)
                )
            }
            output.setPixels(result, 0, width, 0, y, width, 1)
        }
        return output
    }

    private fun blend(a: Int, b: Int, weight: Float) = (a * (1f - weight) + b * weight).toInt().coerceIn(0, 255)
    private fun smoothstep(low: Float, high: Float, x: Float): Float {
        val t = ((x - low) / (high - low)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    private fun sampleMask(values: FloatArray, width: Int, height: Int, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, width - 1)
        val y0 = y.toInt().coerceIn(0, height - 1)
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        val top = values[y0 * width + x0] * (1f - fx) + values[y0 * width + x1] * fx
        val bottom = values[y1 * width + x0] * (1f - fx) + values[y1 * width + x1] * fx
        return top * (1f - fy) + bottom * fy
    }
    private fun sampleColor(values: IntArray, width: Int, height: Int, x: Float, y: Float): Int {
        val x0 = x.toInt().coerceIn(0, width - 1)
        val y0 = y.toInt().coerceIn(0, height - 1)
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        val a = values[y0 * width + x0]
        val b = values[y0 * width + x1]
        val c = values[y1 * width + x0]
        val d = values[y1 * width + x1]
        fun channel(extract: (Int) -> Int): Int {
            val top = extract(a) * (1f - fx) + extract(b) * fx
            val bottom = extract(c) * (1f - fx) + extract(d) * fx
            return (top * (1f - fy) + bottom * fy).toInt().coerceIn(0, 255)
        }
        return android.graphics.Color.rgb(channel(android.graphics.Color::red),
            channel(android.graphics.Color::green), channel(android.graphics.Color::blue))
    }
    private fun blurBackground(source: IntArray, width: Int, height: Int, radius: Int,
                               mask: FloatArray, maskWidth: Int, maskHeight: Int): IntArray {
        val result = IntArray(source.size)
        for (y in 0 until height) for (x in 0 until width) {
            var r = 0f; var g = 0f; var b = 0f; var weightSum = 0f
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val ix = (x + dx).coerceIn(0, width - 1)
                val iy = (y + dy).coerceIn(0, height - 1)
                val person = sampleMask(mask, maskWidth, maskHeight,
                    (ix + 0.5f) * maskWidth / width - 0.5f,
                    (iy + 0.5f) * maskHeight / height - 0.5f)
                val weight = (1f - smoothstep(0.12f, 0.82f, person)).coerceAtLeast(0f)
                val color = source[iy * width + ix]
                r += android.graphics.Color.red(color) * weight
                g += android.graphics.Color.green(color) * weight
                b += android.graphics.Color.blue(color) * weight
                weightSum += weight
            }
            result[y * width + x] = if (weightSum > 0.001f)
                android.graphics.Color.rgb((r/weightSum).toInt().coerceIn(0,255),
                    (g/weightSum).toInt().coerceIn(0,255),
                    (b/weightSum).toInt().coerceIn(0,255))
                else source[y * width + x]
        }
        return result
    }
    override fun close() = segmenter.close()
}
