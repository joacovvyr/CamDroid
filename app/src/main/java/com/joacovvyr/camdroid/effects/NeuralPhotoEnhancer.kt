package com.joacovvyr.camdroid.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Offline, tiled x4 ESRGAN restoration. The model works on small overlapping tiles, while
 * the saved original JPEG is never decoded or modified by this class.
 */
class NeuralPhotoEnhancer(private val context: Context) {
    private val maxPixels = 7680L * 4320L
    private val halo = 8

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
            val upScale = min(4.0,
                min(7680.0 / max(source.width, source.height),
                    sqrt(maxPixels.toDouble() / (source.width.toLong() * source.height))))
            val outputWidth = max(4, ((source.width * upScale).toInt() / 4) * 4)
            val outputHeight = max(4, ((source.height * upScale).toInt() / 4) * 4)
            require(outputWidth.toLong() * outputHeight <= maxPixels) { "La imagen supera el límite de 8K" }
            val inputWidth = outputWidth / 4
            val inputHeight = outputHeight / 4
            modelInput = Bitmap.createScaledBitmap(source, inputWidth, inputHeight, true)
            val modelPixels = IntArray(inputWidth * inputHeight)
            modelInput.getPixels(modelPixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
            if (modelInput !== source) modelInput.recycle()
            modelInput = null
            for (i in modelPixels.indices) modelPixels[i] = tone(modelPixels[i])

            val originalPixels = IntArray(source.width * source.height)
            source.getPixels(originalPixels, 0, source.width, 0, 0, source.width, source.height)
            for (i in originalPixels.indices) originalPixels[i] = tone(originalPixels[i])
            output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)

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
            }
            require(tile >= 24) { "El modelo no permite procesar mosaicos" }
            val factor = 4
            val shape = interpreter.getOutputTensor(0).shape()
            require(interpreter.getInputTensor(0).dataType() == DataType.FLOAT32 &&
                interpreter.getOutputTensor(0).dataType() == DataType.FLOAT32 &&
                shape[1] == tile * factor && shape[2] == tile * factor && shape[3] == 3) {
                "Modelo ESRGAN incompatible con la salida esperada"
            }
            val stride = tile - 2 * halo
            val cols = (inputWidth + stride - 1) / stride
            val rows = (inputHeight + stride - 1) / stride
            val total = cols * rows
            val input = ByteBuffer.allocateDirect(tile * tile * 3 * 4).order(ByteOrder.nativeOrder())
            val prediction = ByteBuffer.allocateDirect(shape[1] * shape[2] * 3 * 4)
                .order(ByteOrder.nativeOrder())
            var done = 0
            for (ty in 0 until rows) for (tx in 0 until cols) {
                val baseX = tx * stride
                val baseY = ty * stride
                input.clear()
                for (y in 0 until tile) for (x in 0 until tile) {
                    val sx = (baseX + x - halo).coerceIn(0, inputWidth - 1)
                    val sy = (baseY + y - halo).coerceIn(0, inputHeight - 1)
                    val pixel = modelPixels[sy * inputWidth + sx]
                    input.putFloat(Color.red(pixel).toFloat())
                    input.putFloat(Color.green(pixel).toFloat())
                    input.putFloat(Color.blue(pixel).toFloat())
                }
                input.rewind()
                prediction.clear()
                interpreter.run(input, prediction)
                val validWidth = min(stride, inputWidth - baseX) * factor
                val validHeight = min(stride, inputHeight - baseY) * factor
                val result = IntArray(validWidth * validHeight)
                for (y in 0 until validHeight) for (x in 0 until validWidth) {
                    val xInModel = halo * factor + x
                    val yInModel = halo * factor + y
                    val offset = ((yInModel * tile * factor + xInModel) * 3) * 4
                    val neuralR = prediction.getFloat(offset).toInt().coerceIn(0, 255)
                    val neuralG = prediction.getFloat(offset + 4).toInt().coerceIn(0, 255)
                    val neuralB = prediction.getFloat(offset + 8).toInt().coerceIn(0, 255)
                    val worldX = baseX * factor + x
                    val worldY = baseY * factor + y
                    val original = bilinear(originalPixels, source.width, source.height,
                        (worldX + 0.5f) * source.width / outputWidth - 0.5f,
                        (worldY + 0.5f) * source.height / outputHeight - 0.5f)
                    val light = (Color.red(original) + Color.green(original) + Color.blue(original)) / 765f
                    val amount = 0.36f + (1f - light) * 0.19f
                    result[y * validWidth + x] = Color.rgb(
                        mix(Color.red(original), neuralR, amount),
                        mix(Color.green(original), neuralG, amount),
                        mix(Color.blue(original), neuralB, amount))
                }
                output.setPixels(result, 0, validWidth, baseX * factor, baseY * factor,
                    validWidth, validHeight)
                done++
                if (done == 1 || done % 4 == 0 || done == total) progress(done, total)
            }
            val finished = requireNotNull(output)
            output = null
            return finished
        } finally {
            interpreter?.close()
            modelInput?.let { if (it !== source) it.recycle() }
            source.recycle()
            output?.recycle()
        }
    }

    private fun decodeBounded(jpeg: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "JPEG original inválido" }
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / (sample * sample) > 16_000_000)
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
    private fun mix(a: Int, b: Int, fraction: Float) =
        (a * (1f - fraction) + b * fraction).toInt().coerceIn(0, 255)
}
