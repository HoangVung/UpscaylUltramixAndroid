package com.vung.upscaylultramix

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
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
    private lateinit var openOutputDirButton: Button

    private var lastSavedUri: Uri? = null

    private var inputFile: File? = null
    private var outputTreeUri: Uri? = null
    private var currentTempOutput: File? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    @Volatile private var isUpscaling = false
    @Volatile private var lastProgressText = ""
    private var upscaleStartedAt = 0L

    private val elapsedTicker = object : Runnable {
        override fun run() {
            if (!isUpscaling) return
            statusText.text = buildStatusWithElapsed(lastProgressText)
            uiHandler.postDelayed(this, 1000)
        }
    }

    companion object {
        @Volatile private var nativeLoadAttempted = false
        @Volatile private var nativeAvailable = false
        @Volatile private var nativeLoadError: String? = null

        fun ensureNativeLoaded(): Boolean {
            if (nativeLoadAttempted) return nativeAvailable
            nativeLoadAttempted = true
            return try {
                System.loadLibrary("ultramix_jni")
                nativeAvailable = true
                nativeLoadError = null
                true
            } catch (e: UnsatisfiedLinkError) {
                nativeAvailable = false
                nativeLoadError = e.message ?: "Không tìm thấy thư viện native ultramix_jni."
                false
            } catch (e: Throwable) {
                nativeAvailable = false
                nativeLoadError = e.message ?: "Không tải được thư viện native."
                false
            }
        }

        fun getNativeLoadError(): String {
            return nativeLoadError ?: "Thiếu hoặc lỗi thư viện native libultramix_jni.so."
        }
    }

    private external fun upscaleNative(
        inputPath: String,
        outputPath: String,
        modelParamPath: String,
        modelBinPath: String,
        scale: Int,
        callbackTarget: MainActivity
    ): Int

    private external fun cancelNative()

    private val pickDirectory =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
                outputTreeUri = uri
                val display = uri.lastPathSegment?.substringAfter(":") ?: uri.toString()
                outputDirText.text = "Thư mục: $display"
                statusText.text = "Đã chọn thư mục lưu ảnh."
                openOutputDirButton.isEnabled = true
                saveDirectoryToPrefs(uri)
            }
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            val copied = copyUriToCache(uri)

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(copied.absolutePath, options)
            val w = options.outWidth
            val h = options.outHeight
            val pixels = w.toLong() * h
            val longEdge = if (w > h) w else h

            if (pixels > 6000000 || longEdge > 3000) {
                statusText.text = "Ảnh quá lớn (${w}x${h}). Giới hạn: tối đa 6M pixels và cạnh dài không quá 3000px."
                previewImage.setImageDrawable(null)
                upscaleButton.isEnabled = false
                inputFile = null
                return@registerForActivityResult
            }

            inputFile = copied
            val bmp = decodeSampledBitmap(copied.absolutePath, 1024, 1024)
            if (bmp != null) {
                previewImage.setImageBitmap(bmp)
            } else {
                previewImage.setImageURI(Uri.fromFile(copied))
            }
            statusText.text = "Đã chọn: ${copied.name} (${w}x${h})"
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
        openOutputDirButton = findViewById(R.id.openOutputDirButton)

        selectButton.setOnClickListener {
            pickImage.launch("image/*")
        }

        outputDirButton.setOnClickListener {
            pickDirectory.launch(null)
        }

        openOutputDirButton.setOnClickListener {
            openOutputFolder()
        }

        upscaleButton.setOnClickListener {
            if (isUpscaling) {
                if (ensureNativeLoaded()) {
                    try {
                        cancelNative()
                    } catch (e: UnsatisfiedLinkError) {
                        statusText.text = "Không hủy được vì thiếu thư viện native: ${getNativeLoadError()}"
                    }
                }
                lastProgressText = "Đang hủy sau tile hiện tại..."
                statusText.text = buildStatusWithElapsed(lastProgressText)
                upscaleButton.isEnabled = false
            } else {
                runUpscale()
            }
        }

        restoreSavedDirectory()

        val vulkanOk = packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
        if (!vulkanOk) {
            statusText.text = "Máy có thể không hỗ trợ Vulkan đầy đủ. App vẫn mở được nhưng upscale có thể lỗi."
        }

        if (!ensureNativeLoaded()) {
            statusText.text = "Chưa có thư viện NCNN/Vulkan native. App sẽ dùng chế độ Android fallback 4x."
        }
    }

    private fun restoreSavedDirectory() {
        val prefs = getSharedPreferences("upscayl_prefs", Context.MODE_PRIVATE)
        val uriStr = prefs.getString("output_tree_uri", null)
        if (uriStr != null) {
            try {
                val uri = Uri.parse(uriStr)
                var hasPermission = false
                val persistedPermissions = contentResolver.persistedUriPermissions
                for (permission in persistedPermissions) {
                    if (permission.uri == uri) {
                        hasPermission = true
                        break
                    }
                }
                if (hasPermission) {
                    outputTreeUri = uri
                    val display = uri.lastPathSegment?.substringAfter(":") ?: uri.toString()
                    outputDirText.text = "Thư mục: $display"
                    openOutputDirButton.isEnabled = true
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun saveDirectoryToPrefs(uri: Uri) {
        val prefs = getSharedPreferences("upscayl_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("output_tree_uri", uri.toString()).apply()
    }

    private fun openOutputFolder() {
        val treeUri = outputTreeUri
        if (treeUri != null) {
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                }
                startActivity(intent)
                return
            } catch (e: Exception) {
                // ignore
            }
        }

        val fileUri = lastSavedUri
        if (fileUri != null) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, "image/png")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
                return
            } catch (e: Exception) {
                // ignore
            }
        }

        Toast.makeText(this, "Hãy dùng ứng dụng Files để mở thư mục lưu", Toast.LENGTH_LONG).show()
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
            try {
                assets.open(assetPath).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: java.io.FileNotFoundException) {
                throw Exception("Thiếu model: $assetPath")
            }
        }
        return outFile
    }

    private fun saveToUserDir(tempFile: File): Pair<String, Boolean> {
        val treeUri = outputTreeUri ?: run {
            lastSavedUri = Uri.fromFile(tempFile)
            return Pair(tempFile.absolutePath, true)
        }

        try {
            val docTree = DocumentFile.fromTreeUri(this, treeUri)
            val fileName = tempFile.name
            val targetFile = docTree?.createFile("image/png", fileName)
                ?: return Pair("Không tạo được file trong thư mục đích.", false)
            val outStream = contentResolver.openOutputStream(targetFile.uri, "w")
                ?: return Pair("Không mở được output stream cho file đích.", false)
            outStream.use { out ->
                tempFile.inputStream().use { inp ->
                    inp.copyTo(out)
                }
            }
            lastSavedUri = targetFile.uri
            return Pair("Đã lưu vào thư mục đã chọn: $fileName", true)
        } catch (e: Exception) {
            return Pair("Lỗi khi copy vào thư mục đích: ${e.message}", false)
        }
    }

    private fun decodeSampledBitmap(path: String, maxW: Int, maxH: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        var sampleSize = 1
        while (opts.outWidth / sampleSize > maxW || opts.outHeight / sampleSize > maxH) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        return BitmapFactory.decodeFile(path, decodeOpts)
    }

    private fun upscaleWithAndroidFallback(inputPath: String, outputFile: File, scale: Int) {
        val source = BitmapFactory.decodeFile(inputPath)
            ?: throw Exception("Không đọc được ảnh đầu vào bằng Android Bitmap.")

        try {
            val targetWidth = source.width * scale
            val targetHeight = source.height * scale
            val outputPixels = targetWidth.toLong() * targetHeight.toLong()
            if (outputPixels > 36000000L) {
                throw Exception("Ảnh quá lớn cho chế độ fallback (${targetWidth}x${targetHeight}). Hãy dùng ảnh nhỏ hơn hoặc bổ sung native NCNN/Vulkan.")
            }

            runOnUiThread {
                lastProgressText = "Đang phóng to ảnh bằng Android fallback 4x..."
                statusText.text = buildStatusWithElapsed(lastProgressText)
            }

            val scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
            try {
                FileOutputStream(outputFile).use { out ->
                    if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw Exception("Không ghi được file PNG kết quả.")
                    }
                }
            } finally {
                if (scaled !== source) scaled.recycle()
            }
        } catch (e: OutOfMemoryError) {
            throw Exception("Không đủ RAM cho chế độ fallback. Hãy thử ảnh nhỏ hơn.")
        } finally {
            source.recycle()
        }
    }

    fun onNativeProgress(
        doneTiles: Int,
        totalTiles: Int,
        phase: String,
        elapsedMs: Long,
        modeLabel: String
    ) {
        runOnUiThread {
            val progressText = if (totalTiles > 0) {
                "$phase $doneTiles/$totalTiles tile ($modeLabel)"
            } else {
                "$phase ($modeLabel)"
            }
            lastProgressText = progressText
            statusText.text = "$progressText • ${formatElapsed(elapsedMs)}"

            progressBar.visibility = View.VISIBLE
            if (totalTiles > 0) {
                progressBar.isIndeterminate = false
                progressBar.max = totalTiles
                progressBar.progress = doneTiles.coerceIn(0, totalTiles)
            } else {
                progressBar.isIndeterminate = true
            }
        }
    }

    private fun buildStatusWithElapsed(baseText: String): String {
        val elapsedMs = if (upscaleStartedAt > 0L) {
            System.currentTimeMillis() - upscaleStartedAt
        } else {
            0L
        }
        return "$baseText • ${formatElapsed(elapsedMs)}"
    }

    private fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes} phút ${seconds}s" else "${seconds}s"
    }

    private fun setRunningUi(running: Boolean) {
        isUpscaling = running
        selectButton.isEnabled = !running
        outputDirButton.isEnabled = !running
        openOutputDirButton.isEnabled = !running && (outputTreeUri != null || lastSavedUri != null)
        upscaleButton.isEnabled = true
        upscaleButton.text = if (running) "Hủy" else "Bắt đầu Upscale 4x"
        if (!running) {
            progressBar.visibility = View.GONE
            progressBar.isIndeterminate = false
            progressBar.progress = 0
            uiHandler.removeCallbacks(elapsedTicker)
        }
    }

    private fun runUpscale() {
        val input = inputFile ?: return
        val selectedScale = 4
        val nativeReady = ensureNativeLoaded()

        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        progressBar.progress = 0
        upscaleStartedAt = System.currentTimeMillis()
        lastProgressText = if (nativeReady) {
            "Đang upscale bằng Ultramix NCNN/Vulkan 4x..."
        } else {
            "Đang upscale bằng Android fallback 4x..."
        }
        statusText.text = buildStatusWithElapsed(lastProgressText)
        setRunningUi(true)
        uiHandler.post(elapsedTicker)

        thread {
            var tempOutput: File? = null
            try {
                val tempDir = getExternalFilesDir(null) ?: filesDir
                tempOutput = File(tempDir, "ultramix_${System.currentTimeMillis()}.png")
                currentTempOutput = tempOutput

                val result = if (nativeReady) {
                    val param = copyAssetToFile("models/ultramix-balanced-4x.param", "ultramix-balanced-4x.param")
                    val bin = copyAssetToFile("models/ultramix-balanced-4x.bin", "ultramix-balanced-4x.bin")
                    upscaleNative(
                        input.absolutePath,
                        tempOutput.absolutePath,
                        param.absolutePath,
                        bin.absolutePath,
                        selectedScale,
                        this
                    )
                } else {
                    upscaleWithAndroidFallback(input.absolutePath, tempOutput, selectedScale)
                    0
                }

                runOnUiThread {
                    setRunningUi(false)
                    val outputFile = tempOutput
                    if (outputFile == null) {
                        statusText.text = "Upscale thất bại: không tìm thấy file tạm."
                        currentTempOutput = null
                        return@runOnUiThread
                    }

                    val minValidSize = 1024L
                    if (result == 0 && outputFile.exists() && outputFile.length() >= minValidSize) {
                        val bmp = decodeSampledBitmap(outputFile.absolutePath, 1024, 1024)
                        if (bmp != null) {
                            statusText.text = "Đang lưu vào thư mục..."
                            val (displayPath, saveOk) = saveToUserDir(outputFile)
                            previewImage.setImageBitmap(bmp)
                            if (saveOk) {
                                val mode = if (nativeReady) "NCNN/Vulkan" else "Android fallback"
                                statusText.text = "Xong bằng $mode! $displayPath"
                            } else {
                                statusText.text = "Upscale thành công nhưng lưu file lỗi: $displayPath"
                            }
                        } else {
                            outputFile.delete()
                            statusText.text = "Upscale thất bại: file output không decode được thành bitmap (có thể file hỏng). Mã: $result"
                        }
                    } else {
                        val reason = when (result) {
                            -1 -> "Không tải được mô hình AI (file .param hoặc .bin hỏng)"
                            -2 -> "Không đọc được định dạng ảnh đầu vào"
                            -3 -> "Lỗi ghi file kết quả đầu ra"
                            -4 -> "Lỗi nạp dữ liệu đầu vào (input tensor)"
                            -5 -> "GPU/CPU không xử lý được tile - thử chọn ảnh nhỏ hơn"
                            -7 -> "Kết quả xử lý rỗng hoặc kích thước không hợp lệ"
                            -8 -> "Không đủ bộ nhớ RAM để xử lý ảnh"
                            -9 -> "Ảnh vượt quá giới hạn kích thước (tối đa 6M pixels hoặc cạnh dài 3000px)"
                            -10 -> "Đã hủy xử lý theo yêu cầu"
                            -101 -> "Không thể khởi động mô hình AI"
                            else -> when {
                                !outputFile.exists() -> "file output không tồn tại"
                                outputFile.length() < minValidSize -> "file output quá nhỏ (${outputFile.length()} bytes, nghi ngờ file hỏng)"
                                else -> "mã lỗi native: $result"
                            }
                        }
                        if (outputFile.exists()) outputFile.delete()
                        statusText.text = "Upscale thất bại: $reason"
                    }
                    currentTempOutput = null
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setRunningUi(false)
                    tempOutput?.delete()
                    currentTempOutput = null
                    statusText.text = "Lỗi: ${e.message}"
                }
            } catch (e: UnsatisfiedLinkError) {
                runOnUiThread {
                    setRunningUi(false)
                    tempOutput?.delete()
                    currentTempOutput = null
                    statusText.text = "Lỗi native: ${e.message}"
                }
            }
        }
    }
}
