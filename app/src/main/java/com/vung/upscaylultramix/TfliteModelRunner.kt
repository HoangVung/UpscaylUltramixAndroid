package com.vung.upscaylultramix

import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TfliteModelRunner(modelFile: File, preferredRuntime: String) : AutoCloseable {

    private val wrapper: RuntimeDelegateFactory.InterpreterWrapper =
        RuntimeDelegateFactory.createInterpreter(modelFile, preferredRuntime)

    val interpreter: Interpreter
        get() = wrapper.interpreter

    val runtimeLabel: String
        get() = wrapper.runtimeLabel

    val inputHeight: Int
    val inputWidth: Int
    val inputChannels: Int
    val outputHeight: Int
    val outputWidth: Int
    val outputScale: Int

    private val inputDataType: DataType
    private val outputDataType: DataType

    init {
        val inputShape = interpreter.getInputTensor(0).shape()
        val outputShape = interpreter.getOutputTensor(0).shape()

        inputHeight = inputShape[1]
        inputWidth = inputShape[2]
        inputChannels = inputShape[3]

        outputHeight = outputShape[1]
        outputWidth = outputShape[2]
        outputScale = outputWidth / inputWidth

        inputDataType = interpreter.getInputTensor(0).dataType()
        outputDataType = interpreter.getOutputTensor(0).dataType()
    }

    fun runInference(tileBitmap: Bitmap): Bitmap {
        val inH = inputHeight
        val inW = inputWidth
        val pixels = IntArray(inH * inW)
        tileBitmap.getPixels(pixels, 0, inW, 0, 0, inW, inH)

        val inputBuffer = when (inputDataType) {
            DataType.FLOAT32 -> {
                val buf = ByteBuffer.allocateDirect(inH * inW * 3 * 4).apply {
                    order(ByteOrder.nativeOrder())
                }
                for (pixel in pixels) {
                    buf.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
                    buf.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
                    buf.putFloat((pixel and 0xFF) / 255.0f)
                }
                buf.rewind()
                buf
            }
            DataType.INT8 -> {
                val buf = ByteBuffer.allocateDirect(inH * inW * 3).apply {
                    order(ByteOrder.nativeOrder())
                }
                val params = interpreter.getInputTensor(0).quantizationParams()
                val scale = params.scale
                val zeroPoint = params.zeroPoint
                for (pixel in pixels) {
                    val r = ((((pixel shr 16) and 0xFF) / 255.0f) / scale + zeroPoint).toInt().coerceIn(-128, 127).toByte()
                    val g = ((((pixel shr 8) and 0xFF) / 255.0f) / scale + zeroPoint).toInt().coerceIn(-128, 127).toByte()
                    val b = (((pixel and 0xFF) / 255.0f) / scale + zeroPoint).toInt().coerceIn(-128, 127).toByte()
                    buf.put(r)
                    buf.put(g)
                    buf.put(b)
                }
                buf.rewind()
                buf
            }
            DataType.UINT8 -> {
                val buf = ByteBuffer.allocateDirect(inH * inW * 3).apply {
                    order(ByteOrder.nativeOrder())
                }
                val params = interpreter.getInputTensor(0).quantizationParams()
                val scale = params.scale
                val zeroPoint = params.zeroPoint
                for (pixel in pixels) {
                    val r = ((((pixel shr 16) and 0xFF) / 255.0f) / scale + zeroPoint).toInt().coerceIn(0, 255).toByte()
                    val g = ((((pixel shr 8) and 0xFF) / 255.0f) / scale + zeroPoint).toInt().coerceIn(0, 255).toByte()
                    val b = (((pixel and 0xFF) / 255.0f) / scale + zeroPoint).toInt().coerceIn(0, 255).toByte()
                    buf.put(r)
                    buf.put(g)
                    buf.put(b)
                }
                buf.rewind()
                buf
            }
            else -> throw IllegalArgumentException("Unsupported input tensor data type: $inputDataType")
        }

        val outH = outputHeight
        val outW = outputWidth
        val outPixels = IntArray(outH * outW)

        when (outputDataType) {
            DataType.FLOAT32 -> {
                val outputBuffer = ByteBuffer.allocateDirect(outH * outW * 3 * 4).apply {
                    order(ByteOrder.nativeOrder())
                }
                interpreter.run(inputBuffer, outputBuffer)
                outputBuffer.rewind()

                for (i in 0 until outH * outW) {
                    val r = (outputBuffer.getFloat().coerceIn(0.0f, 1.0f) * 255.0f).toInt()
                    val g = (outputBuffer.getFloat().coerceIn(0.0f, 1.0f) * 255.0f).toInt()
                    val b = (outputBuffer.getFloat().coerceIn(0.0f, 1.0f) * 255.0f).toInt()
                    outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            DataType.INT8 -> {
                val outputBuffer = ByteBuffer.allocateDirect(outH * outW * 3).apply {
                    order(ByteOrder.nativeOrder())
                }
                interpreter.run(inputBuffer, outputBuffer)
                outputBuffer.rewind()

                val params = interpreter.getOutputTensor(0).quantizationParams()
                val scale = params.scale
                val zeroPoint = params.zeroPoint

                for (i in 0 until outH * outW) {
                    val qr = outputBuffer.get().toInt()
                    val qg = outputBuffer.get().toInt()
                    val qb = outputBuffer.get().toInt()
                    val r = (((qr - zeroPoint) * scale).coerceIn(0.0f, 1.0f) * 255.0f).toInt()
                    val g = (((qg - zeroPoint) * scale).coerceIn(0.0f, 1.0f) * 255.0f).toInt()
                    val b = (((qb - zeroPoint) * scale).coerceIn(0.0f, 1.0f) * 255.0f).toInt()
                    outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            DataType.UINT8 -> {
                val outputBuffer = ByteBuffer.allocateDirect(outH * outW * 3).apply {
                    order(ByteOrder.nativeOrder())
                }
                interpreter.run(inputBuffer, outputBuffer)
                outputBuffer.rewind()

                val params = interpreter.getOutputTensor(0).quantizationParams()
                val scale = params.scale
                val zeroPoint = params.zeroPoint

                for (i in 0 until outH * outW) {
                    val qr = outputBuffer.get().toInt() and 0xFF
                    val qg = outputBuffer.get().toInt() and 0xFF
                    val qb = outputBuffer.get().toInt() and 0xFF
                    val r = (((qr - zeroPoint) * scale).coerceIn(0.0f, 1.0f) * 255.0f).toInt()
                    val g = (((qg - zeroPoint) * scale).coerceIn(0.0f, 1.0f) * 255.0f).toInt()
                    val b = (((qb - zeroPoint) * scale).coerceIn(0.0f, 1.0f) * 255.0f).toInt()
                    outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            else -> throw IllegalArgumentException("Unsupported output tensor data type: $outputDataType")
        }

        val resultBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        resultBitmap.setPixels(outPixels, 0, outW, 0, 0, outW, outH)
        return resultBitmap
    }

    override fun close() {
        wrapper.close()
    }
}
