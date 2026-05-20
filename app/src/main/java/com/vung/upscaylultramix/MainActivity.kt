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
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var previewImage: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var selectButton: Button
    private lateinit var upscaleButton: Button
    private lateinit var outputDirButton: Button
    private lateinit var outputDirText: TextView

    private var inputFile: File? = null
    private var outputTreeUri: Uri? = null

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

    private val pickDirectory =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
                outputTreeUri = uri
                val display = uri.lastPathSegment?.substringAfter(":") ?: uri.toString()
                outputDirText.text = "Thư mục: $display"
                statusText.text = "Đã chọn thư mục lưu ảnh."
            }
        }

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
        outputDirButton = findViewById(R.id.outputDirButton)
        outputDirText = findViewById(R.id.outputDirText)

        selectButton.setOnClickListener {
            pickImage.launch("image/*")
        }

        outputDirButton.setOnClickListener {
            pickDirectory.launch(null)
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

    private fun saveToUserDir(tempFile: File): String {
        val treeUri = outputTreeUri
        if (treeUri == null) return tempFile.absolutePath

        try {
            val docTree = DocumentFile.fromTreeUri(this, treeUri)
            val fileName = tempFile.name
            val targetFile = docTree?.createFile("image/png", fileName)
            if (targetFile != null) {
                contentResolver.openOutputStream(targetFile.uri, "w")?.use { out ->
                    tempFile.inputStream().use { inp ->
                        inp.copyTo(out)
                    }
                }
                return "Đã lưu vào thư mục đã chọn: $fileName"
            }
        } catch (e: Exception) {
            // fallback to temp path
        }
        return tempFile.absolutePath
    }

    private fun runUpscale() {
        val input = inputFile ?: return
        progressBar.visibility = View.VISIBLE
        upscaleButton.isEnabled = false
        selectButton.isEnabled = false
        outputDirButton.isEnabled = false
        statusText.text = "Đang upscale bằng Ultramix 4x..."

        thread {
            try {
                val param = copyAssetToFile("models/ultramix-balanced-4x.param", "ultramix-balanced-4x.param")
                val bin = copyAssetToFile("models/ultramix-balanced-4x.bin", "ultramix-balanced-4x.bin")
                val tempDir = getExternalFilesDir(null) ?: filesDir
                val tempOutput = File(tempDir, "ultramix_${System.currentTimeMillis()}.png")

                val result = upscaleNative(
                    input.absolutePath,
                    tempOutput.absolutePath,
                    param.absolutePath,
                    bin.absolutePath
                )

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    selectButton.isEnabled = true
                    upscaleButton.isEnabled = true
                    outputDirButton.isEnabled = true

                    if (result == 0 && tempOutput.exists()) {
                        val displayPath = saveToUserDir(tempOutput)
                        previewImage.setImageBitmap(BitmapFactory.decodeFile(tempOutput.absolutePath))
                        statusText.text = "Xong! $displayPath"
                    } else {
                        statusText.text = "Upscale lỗi. Mã lỗi: $result"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    selectButton.isEnabled = true
                    upscaleButton.isEnabled = true
                    outputDirButton.isEnabled = true
                    statusText.text = "Lỗi: ${e.message}"
                }
            }
        }
    }
}