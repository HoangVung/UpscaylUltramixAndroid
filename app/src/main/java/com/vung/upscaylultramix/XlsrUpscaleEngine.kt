package com.vung.upscaylultramix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class XlsrUpscaleEngine(private val context: Context) {
    @Volatile var isCancelled = false

    interface EngineCallback {
        fun onProgress(doneTiles: Int, totalTiles: Int, phase: String, runtimeLabel: String)
        fun onSuccess(outputFile: File, pipelineDescription: String)
        fun onFailure(message: String)
    }

    private fun copyAssetToFile(assetPath: String, outName: String): File {
        val outFile = File(context.cacheDir, outName)
        context.assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }

    private fun checkAssetExists(assetPath: String): Boolean {
        return try {
            context.assets.open(assetPath).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun cancel() {
        isCancelled = true
    }

    fun execute(
        inputFilePath: String,
        tempOutputDir: File,
        targetScale: Int,
        preferredRuntime: String,
        callback: EngineCallback
    ) {
        isCancelled = false
        
        try {
            var modelAssetPath = ""
            var actualNativeScale = 3
            var useFallbackResize = false
            var pipelineDesc = ""

            when (targetScale) {
                2 -> {
                    if (checkAssetExists("models/xlsr_2x.tflite")) {
                        modelAssetPath = "models/xlsr_2x.tflite"
                        actualNativeScale = 2
                        pipelineDesc = "XLSR 2x native"
                    } else {
                        actualNativeScale = 3
                        useFallbackResize = true
                        pipelineDesc = "2x = XLSR 3x + resize down"
                    }
                }
                3 -> {
                    actualNativeScale = 3
                    pipelineDesc = "XLSR 3x native"
                }
                4 -> {
                    if (checkAssetExists("models/xlsr_4x.tflite")) {
                        modelAssetPath = "models/xlsr_4x.tflite"
                        actualNativeScale = 4
                        pipelineDesc = "XLSR 4x native"
                    } else {
                        actualNativeScale = 3
                        useFallbackResize = true
                        pipelineDesc = "4x = XLSR 3x + resize up"
                    }
                }
                else -> throw IllegalArgumentException("Scale không hợp lệ: $targetScale")
            }

            if (modelAssetPath.isEmpty()) {
                if (checkAssetExists("models/xlsr_3x_w8a8.tflite")) {
                    modelAssetPath = "models/xlsr_3x_w8a8.tflite"
                } else if (checkAssetExists("models/xlsr_3x_float.tflite")) {
                    modelAssetPath = "models/xlsr_3x_float.tflite"
                } else {
                    throw Exception("Thiếu model XLSR: xlsr_3x_w8a8.tflite")
                }
            }

            val modelName = modelAssetPath.substringAfterLast("/")
            val modelFile = copyAssetToFile(modelAssetPath, modelName)

            val runner = TfliteModelRunner(modelFile, preferredRuntime)
            val runtimeLabel = runner.runtimeLabel

            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val inputBitmap = BitmapFactory.decodeFile(inputFilePath, options)
                ?: throw Exception("Không đọc được định dạng ảnh đầu vào")

            val phase = "Đang xử lý tile"

            val outputNativeBitmap = TileProcessor.processImage(
                inputBitmap,
                runner,
                object : TileProcessor.ProgressListener {
                    override fun onProgress(done: Int, total: Int) {
                        callback.onProgress(done, total, phase, "$pipelineDesc · $runtimeLabel")
                    }
                    override fun isCancelled(): Boolean {
                        return isCancelled
                    }
                }
            )

            inputBitmap.recycle()
            runner.close()

            if (outputNativeBitmap == null) {
                if (isCancelled) {
                    callback.onFailure("Đã hủy xử lý theo yêu cầu")
                } else {
                    callback.onFailure("Lỗi xử lý tile")
                }
                return
            }

            val finalBitmap = if (useFallbackResize) {
                val finalW = (outputNativeBitmap.width / actualNativeScale) * targetScale
                val finalH = (outputNativeBitmap.height / actualNativeScale) * targetScale
                val resized = Bitmap.createScaledBitmap(outputNativeBitmap, finalW, finalH, true)
                outputNativeBitmap.recycle()
                resized
            } else {
                outputNativeBitmap
            }

            val scaleLabel = "${targetScale}x"
            val runtimeFileLabel = runtimeLabel.replace("/", "_").lowercase()
            val finalFileDesc = if (useFallbackResize) "${scaleLabel}_from3x" else scaleLabel
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outputFileName = "xlsr_${finalFileDesc}_${runtimeFileLabel}_$timestamp.png"
            val outputFile = File(tempOutputDir, outputFileName)

            FileOutputStream(outputFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            finalBitmap.recycle()

            callback.onSuccess(outputFile, pipelineDesc)
        } catch (e: Exception) {
            callback.onFailure(e.message ?: e.toString())
        }
    }
}
