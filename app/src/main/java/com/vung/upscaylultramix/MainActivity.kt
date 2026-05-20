package com.vung.upscaylultramix

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var previewImage: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var selectButton: Button
    private lateinit var upscaleButton: Button

    private var inputFile: File? = null

    companion object {
        init {
            System.loadLibrary("ultramix_jni")
        }
    }

    private external fun upscaleNative(
        inputPath: String,
        outputPath: String,
        modelParamPath: String,
        modelBinPath: String
    ): Int

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            val copied = copyUriToCache(uri)
            inputFile = copied
            previewImage.setImageURI(Uri.fromFile(copied))
            statusText.text = "Đã chọn: ${copied.name}"
            upscaleButton.isEnabled = true
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        previewImage = findViewById(R.id.previewImage)
        progressBar = findViewById(R.id.progressBar)
        selectButton = findViewById(R.id.selectButton)
        upscaleButton = findViewById(R.id.upscaleButton)

        selectButton.setOnClickListener {
            pickImage.launch("image/*")
        }

        upscaleButton.setOnClickListener {
            runUpscale()
        }

        val vulkanOk = packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
        if (!vulkanOk) {
            statusText.text = "Máy có thể không hỗ trợ Vulkan đầy đủ. App vẫn mở được nhưng upscale có thể lỗi."
        }
    }

    private fun copyUriToCache(uri: Uri): File {
        val name = getDisplayName(uri) ?: "input.png"
        val outFile = File(cacheDir, "input_${System.currentTimeMillis()}_$name")
        contentResolver.openInputStream(uri).use { input ->
            FileOutputStream(outFile).use { output ->
                input?.copyTo(output)
            }
        }
        return outFile
    }

    private fun getDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        }
    }

    private fun copyAssetToFile(assetPath: String, outName: String): File {
        val outFile = File(filesDir, outName)
        if (!outFile.exists() || outFile.length() == 0L) {
            assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return outFile
    }

    private fun runUpscale() {
        val input = inputFile ?: return
        progressBar.visibility = View.VISIBLE
        upscaleButton.isEnabled = false
        selectButton.isEnabled = false
        statusText.text = "Đang upscale bằng Ultramix 4x..."

        thread {
            try {
                val param = copyAssetToFile("models/ultramix-balanced-4x.param", "ultramix-balanced-4x.param")
                val bin = copyAssetToFile("models/ultramix-balanced-4x.bin", "ultramix-balanced-4x.bin")
                val output = File(
                    getExternalFilesDir(null),
                    "ultramix_${System.currentTimeMillis()}.png"
                )

                val result = upscaleNative(
                    input.absolutePath,
                    output.absolutePath,
                    param.absolutePath,
                    bin.absolutePath
                )

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    selectButton.isEnabled = true
                    upscaleButton.isEnabled = true

                    if (result == 0 && output.exists()) {
                        previewImage.setImageBitmap(BitmapFactory.decodeFile(output.absolutePath))
                        statusText.text = "Xong. File đã lưu tại:\n${output.absolutePath}"
                    } else {
                        statusText.text = "Upscale lỗi. Mã lỗi: $result"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    selectButton.isEnabled = true
                    upscaleButton.isEnabled = true
                    statusText.text = "Lỗi: ${e.message}"
                }
            }
        }
    }
}