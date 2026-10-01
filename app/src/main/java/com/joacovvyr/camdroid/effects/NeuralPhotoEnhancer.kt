package com.joacovvyr.camdroid.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.ceil
import kotlin.math.floor
import java.util.concurrent.TimeUnit

/**
 * Offline, tiled x4 ESRGAN restoration with a deliberately bounded residual. Detected faces
 * retain only interpolation of the source pixels. The original JPEG is saved separately.
 */
class NeuralPhotoEnhancer(private val context: Context) {
    private val maxPixels = 7680L * 4320L
    // The original JPEG is preserved byte-for-byte. Keep the working bitmap smaller so the
    // 8K output, the model and CameraX can coexist inside the S22 app heap.
    private val maxWorkingPixels = 8_000_000L
    private val halo = 8

    private data class PixelRegion(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val left: Int,
        val top: Int
    )

    fun enhance(jpeg: ByteArray, rotation: Int, mirror: Boolean,
                progress: (Int, Int) -> Unit): Bitmap {
        var source = decodeBounded(jpeg)
        if (rotation != 0 || mirror) {
            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
                if (mirror) postScale(-1f, 1f)
            }
            val upright = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            if (upright !== source) {
                source.recycle()
                source = upright
            }
        }
        var modelInput: Bitmap? = null
        var output: Bitmap? = null
        var interpreter: Interpreter? = null
        try {
            val faceRegions = try {
                detectFaceRegions(source)
            } catch (_: Exception) {
                // If face protection is unavailable, do not run a generative model at all.
                return faithfulUpscale(source, progress)
            }
            val upScale = min(4.0,
                min(7680.0 / max(source.width, source.height),
                    sqrt(maxPixels.toDouble() / (source.width.toLong() * source.height))))
            val outputWidth = max(4, ((source.width * upScale).toInt() / 4) * 4)
            val outputHeight = max(4, ((source.height * upScale).toInt() / 4) * 4)
            require(outputWidth.toLong() * outputHeight <= maxPixels) { "La imagen supera el límite de 8K" }
            val inputWidth = outputWidth / 4
            val inputHeight = outputHeight / 4
            modelInput = Bitmap.createScaledBitmap(source, inputWidth, inputHeight, true)

            val model = context.assets.open("ESRGAN.tflite").use { it.readBytes() }
            val options = Interpreter.Options().setNumThreads(4)
            interpreter = Interpreter(ByteBuffer.allocateDirect(model.size).apply {
                put(model); rewind()
            }, options)
            var tile = 128
            try {
                interpreter.resizeInput(0, intArrayOf(1, tile, tile, 3))
                interpreter.allocateTensors()
            } catch (_: RuntimeException) {
                interpreter.close()
                interpreter = Interpreter(ByteBuffer.allocateDirect(model.size).apply {
                    put(model); rewind()
                }, options)
                val shape = interpreter.getInputTensor(0).shape()
                tile = shape[1]
                interpreter.allocateTensors()
            }
            require(tile >= 24) { "El modelo no permite procesar mosaicos" }
            val factor = 4
            val shape = interpreter.getOutputTensor(0).shape()
            require(interpreter.getInputTensor(0).dataType() == DataType.FLOAT32 &&
                interpreter.getOutputTensor(0).dataType() == DataType.FLOAT32 &&
                shape[1] == tile * factor && shape[2] == tile * factor && shape[3] == 3) {
                "Modelo ESRGAN incompatible con la salida esperada"
            }
            output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            val stride = tile - 2 * halo
            val cols = (inputWidth + stride - 1) / stride
            val rows = (inputHeight + stride - 1) / stride
            val total = cols * rows
            val input = ByteBuffer.allocateDirect(tile * tile * 3 * 4).order(ByteOrder.nativeOrder())
            val prediction = ByteBuffer.allocateDirect(shape[1] * shape[2] * 3 * 4)
                .order(ByteOrder.nativeOrder())
            val inputBitmap = requireNotNull(modelInput)
            val modelRow = IntArray(tile)
            val result = IntArray(stride * factor * stride * factor)
            var done = 0
            for (ty in 0 until rows) for (tx in 0 until cols) {
                val baseX = tx * stride
                val baseY = ty * stride
                input.clear()
                val readLeft = (baseX - halo).coerceIn(0, inputWidth - 1)
                val readRight = (baseX - halo + tile - 1).coerceIn(0, inputWidth - 1)
                val readWidth = readRight - readLeft + 1
                for (y in 0 until tile) for (x in 0 until tile) {
                    // Read one model row at a time instead of keeping a second full-resolution
                    // IntArray alive for the whole 8K operation.
                    if (x == 0) {
                        val sy = (baseY + y - halo).coerceIn(0, inputHeight - 1)
                        inputBitmap.getPixels(modelRow, 0, readWidth, readLeft, sy, readWidth, 1)
                    }
                    val sx = (baseX + x - halo).coerceIn(readLeft, readRight)
                    val pixel = tone(modelRow[sx - readLeft])
                    input.putFloat(Color.red(pixel).toFloat())
                    input.putFloat(Color.green(pixel).toFloat())
                    input.putFloat(Color.blue(pixel).toFloat())
                }
                input.rewind()
                prediction.clear()
                interpreter.run(input, prediction)
                val validWidth = min(stride, inputWidth - baseX) * factor
                val validHeight = min(stride, inputHeight - baseY) * factor
                val sourceRegion = loadSourceRegion(
                    source, baseX * factor, baseY * factor, validWidth, validHeight,
                    outputWidth, outputHeight
                )
                for (y in 0 until validHeight) for (x in 0 until validWidth) {
                    val xInModel = halo * factor + x
                    val yInModel = halo * factor + y
                    val offset = ((yInModel * tile * factor + xInModel) * 3) * 4
                    val neuralR = prediction.getFloat(offset).toInt().coerceIn(0, 255)
                    val neuralG = prediction.getFloat(offset + 4).toInt().coerceIn(0, 255)
                    val neuralB = prediction.getFloat(offset + 8).toInt().coerceIn(0, 255)
                    val worldX = baseX * factor + x
                    val worldY = baseY * factor + y
                    val sourceX = (worldX + 0.5f) * source.width / outputWidth - 0.5f
                    val sourceY = (worldY + 0.5f) * source.height / outputHeight - 0.5f
                    val localX = sourceX - sourceRegion.left
                    val localY = sourceY - sourceRegion.top
                    val original = bilinear(sourceRegion.pixels, sourceRegion.width,
                        sourceRegion.height, localX, localY)
                    val protected = faceProtection(faceRegions, sourceX, sourceY)
                    val enhanced = tone(original)
                    val detail = detailConfidence(sourceRegion.pixels, sourceRegion.width,
                        sourceRegion.height, localX, localY)
                    val amount = 0.32f * detail * (1f - protected)
                    result[y * validWidth + x] = Color.rgb(
                        guardedChannel(Color.red(original), Color.red(enhanced), neuralR, amount, protected),
                        guardedChannel(Color.green(original), Color.green(enhanced), neuralG, amount, protected),
                        guardedChannel(Color.blue(original), Color.blue(enhanced), neuralB, amount, protected))
                }
                output.setPixels(result, 0, validWidth, baseX * factor, baseY * factor,
                    validWidth, validHeight)
                done++
                if (done == 1 || done % 4 == 0 || done == total) progress(done, total)
            }
            val finished = requireNotNull(output)
            output = null
            return finished
        } catch (error: RuntimeException) {
            // A device-specific TFLite delegate or tensor allocation can reject a large photo.
            // Keep the capture usable: release the large intermediates and return a faithful
            // interpolated 8K result while the exact original is already safely stored.
            interpreter?.close()
            interpreter = null
            modelInput?.let { if (!it.isRecycled && it !== source) it.recycle() }
            modelInput = null
            output?.recycle()
            output = null
            return faithfulUpscale(source, progress)
        } catch (error: OutOfMemoryError) {
            interpreter?.close()
            interpreter = null
            modelInput?.let { if (!it.isRecycled && it !== source) it.recycle() }
            modelInput = null
            output?.recycle()
            output = null
            System.gc()
            return faithfulUpscale(source, progress)
        } finally {
            interpreter?.close()
            modelInput?.let { if (it !== source) it.recycle() }
            source.recycle()
            output?.recycle()
        }
    }

    private fun detectFaceRegions(source: Bitmap): List<RectF> {
        val scale = min(1f, 1280f / max(source.width, source.height))
        val preview = if (scale == 1f) source else Bitmap.createScaledBitmap(source,
            max(1, (source.width * scale).toInt()), max(1, (source.height * scale).toInt()), true)
        val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build())
        try {
            val faces = Tasks.await(detector.process(InputImage.fromBitmap(preview, 0)), 30, TimeUnit.SECONDS)
            return faces.map { face ->
                val box = face.boundingBox
                val padX = box.width() * 0.35f
                val padY = box.height() * 0.45f
                RectF((box.left - padX) / scale, (box.top - padY) / scale,
                    (box.right + padX) / scale, (box.bottom + padY) / scale)
            }
        } finally {
            detector.close()
            if (preview !== source) preview.recycle()
        }
    }

    private fun faceProtection(regions: List<RectF>, x: Float, y: Float): Float {
        var protection = 0f
        for (region in regions) {
            val fade = max(region.width(), region.height()) * 0.12f
            val dx = max(max(region.left - x, x - region.right), 0f)
            val dy = max(max(region.top - y, y - region.bottom), 0f)
            protection = max(protection, 1f - max(dx, dy) / fade)
        }
        return protection.coerceIn(0f, 1f)
    }

    private fun detailConfidence(pixels: IntArray, width: Int, height: Int, x: Float, y: Float): Float {
        val px = x.toInt().coerceIn(1, max(1, width - 2))
        val py = y.toInt().coerceIn(1, max(1, height - 2))
        val left = pixels[py * width + px - 1]
        val right = pixels[py * width + min(px + 1, width - 1)]
        val top = pixels[max(py - 1, 0) * width + px]
        val bottom = pixels[min(py + 1, height - 1) * width + px]
        val contrast = (kotlin.math.abs(Color.red(left) - Color.red(right)) +
            kotlin.math.abs(Color.green(left) - Color.green(right)) +
            kotlin.math.abs(Color.blue(left) - Color.blue(right)) +
            kotlin.math.abs(Color.red(top) - Color.red(bottom)) +
            kotlin.math.abs(Color.green(top) - Color.green(bottom)) +
            kotlin.math.abs(Color.blue(top) - Color.blue(bottom))) / 6f
        return ((contrast - 4f) / 18f).coerceIn(0f, 1f)
    }

    private fun guardedChannel(original: Int, toned: Int, neural: Int, amount: Float, protection: Float): Int {
        val anchored = toned + (neural - toned).coerceIn(-20, 20) * amount
        return mix(anchored.toInt(), original, protection)
    }

    private fun faithfulUpscale(source: Bitmap, progress: (Int, Int) -> Unit): Bitmap {
        progress(0, 1)
        val scale = min(4.0, min(7680.0 / max(source.width, source.height),
            sqrt(maxPixels.toDouble() / (source.width.toLong() * source.height))))
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        return if (width == source.width && height == source.height) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
            .also { progress(1, 1) }
    }

    private fun decodeBounded(jpeg: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "JPEG original inválido" }
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / (sample * sample) > maxWorkingPixels)
            sample *= 2
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("No se pudo abrir el JPEG")
    }

    private fun tone(color: Int): Int {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val light = 0.2126f*r + 0.7152f*g + 0.0722f*b
        val shadows = 0.72f * (1f - light) * (1f - light)
        fun curve(value: Float): Int {
            val raised = value + shadows * (sqrt(value) - value)
            return (raised * 255f).toInt().coerceIn(0, 255)
        }
        return Color.rgb(curve(r), curve(g), curve(b))
    }

    private fun bilinear(pixels: IntArray, width: Int, height: Int, x: Float, y: Float): Int {
        val x0 = x.toInt().coerceIn(0, width - 1)
        val y0 = y.toInt().coerceIn(0, height - 1)
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        val a = pixels[y0 * width + x0]
        val b = pixels[y0 * width + x1]
        val c = pixels[y1 * width + x0]
        val d = pixels[y1 * width + x1]
        fun channel(f: (Int) -> Int): Int {
            val top = f(a) * (1f - fx) + f(b) * fx
            val bottom = f(c) * (1f - fx) + f(d) * fx
            return (top * (1f - fy) + bottom * fy).toInt().coerceIn(0, 255)
        }
        return Color.rgb(channel(Color::red), channel(Color::green), channel(Color::blue))
    }

    private fun loadSourceRegion(source: Bitmap, outputX: Int, outputY: Int,
                                 outputWidth: Int, outputHeight: Int,
                                 finalWidth: Int, finalHeight: Int): PixelRegion {
        val left = floor((outputX - 2).toDouble() * source.width / finalWidth)
            .toInt().coerceIn(0, source.width - 1)
        val top = floor((outputY - 2).toDouble() * source.height / finalHeight)
            .toInt().coerceIn(0, source.height - 1)
        val right = ceil((outputX + outputWidth + 2).toDouble() * source.width / finalWidth)
            .toInt().plus(1).coerceIn(left + 1, source.width)
        val bottom = ceil((outputY + outputHeight + 2).toDouble() * source.height / finalHeight)
            .toInt().plus(1).coerceIn(top + 1, source.height)
        val width = right - left
        val height = bottom - top
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, left, top, width, height)
        return PixelRegion(pixels, width, height, left, top)
    }
    private fun mix(a: Int, b: Int, fraction: Float) =
        (a * (1f - fraction) + b * fraction).toInt().coerceIn(0, 255)
}
