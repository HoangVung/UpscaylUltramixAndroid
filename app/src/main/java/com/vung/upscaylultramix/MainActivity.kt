package com.vung.upscaylultramix

import android.app.Activity
import android.content.Context
import android.content.Intent
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
    
    private lateinit var scaleRadioGroup: RadioGroup
    private lateinit var runtimeRadioGroup: RadioGroup

    private var lastSavedUri: Uri? = null
    private var inputFile: File? = null
    private var outputTreeUri: Uri? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    @Volatile private var isUpscaling = false
    @Volatile private var lastProgressText = ""
    private var upscaleStartedAt = 0L
    
    private lateinit var upscaleEngine: XlsrUpscaleEngine

    private val elapsedTicker = object : Runnable {
        override fun run() {
            if (!isUpscaling) return
            statusText.text = buildStatusWithElapsed(lastProgressText)
            uiHandler.postDelayed(this, 1000)
        }
    }

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
            
            // Check size limits
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

        upscaleEngine = XlsrUpscaleEngine(this)

        statusText = findViewById(R.id.statusText)
        previewImage = findViewById(R.id.previewImage)
        progressBar = findViewById(R.id.progressBar)
        selectButton = findViewById(R.id.selectButton)
        upscaleButton = findViewById(R.id.upscaleButton)
        outputDirButton = findViewById(R.id.outputDirButton)
        outputDirText = findViewById(R.id.outputDirText)
        openOutputDirButton = findViewById(R.id.openOutputDirButton)
        
        scaleRadioGroup = findViewById(R.id.scaleRadioGroup)
        runtimeRadioGroup = findViewById(R.id.runtimeRadioGroup)

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
                upscaleEngine.cancel()
                lastProgressText = "Đang hủy..."
                statusText.text = buildStatusWithElapsed(lastProgressText)
                upscaleButton.isEnabled = false
            } else {
                runUpscale()
            }
        }

        scaleRadioGroup.setOnCheckedChangeListener { _, _ ->
            val scaleText = "${getSelectedScale()}x"
            upscaleButton.text = "Bắt đầu Upscale $scaleText"
        }

        restoreSavedDirectory()
    }

    private fun getSelectedScale(): Int {
        return when (scaleRadioGroup.checkedRadioButtonId) {
            R.id.radio2x -> 2
            R.id.radio3x -> 3
            R.id.radio4x -> 4
            else -> 3
        }
    }

    private fun getPreferredRuntime(): String {
        return when (runtimeRadioGroup.checkedRadioButtonId) {
            R.id.radioNnapi -> "nnapi"
            R.id.radioGpu -> "gpu"
            R.id.radioCpu -> "cpu"
            else -> "nnapi"
        }
    }

    private fun getActualRuntimeName(fileName: String): String {
        return when {
            fileName.contains("nnapi_npu") -> "NNAPI/NPU"
            fileName.contains("gpu") -> "GPU"
            else -> "CPU fallback"
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
        scaleRadioGroup.isEnabled = !running
        runtimeRadioGroup.isEnabled = !running
        openOutputDirButton.isEnabled = !running && (outputTreeUri != null || lastSavedUri != null)
        upscaleButton.isEnabled = true
        
        val scaleText = "${getSelectedScale()}x"
        upscaleButton.text = if (running) "Hủy" else "Bắt đầu Upscale $scaleText"
        if (!running) {
            progressBar.visibility = View.GONE
            progressBar.isIndeterminate = false
            progressBar.progress = 0
            uiHandler.removeCallbacks(elapsedTicker)
        }
    }

    private fun runUpscale() {
        val input = inputFile ?: return
        val selectedScale = getSelectedScale()
        val preferredRuntime = getPreferredRuntime()

        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        progressBar.progress = 0
        upscaleStartedAt = System.currentTimeMillis()

        val pipelineLabel = when (selectedScale) {
            2 -> "2x = XLSR 3x + resize down"
            3 -> "XLSR 3x native"
            4 -> "4x = XLSR 3x + resize up"
            else -> "XLSR 3x native"
        }

        lastProgressText = "Đang khởi tạo $pipelineLabel..."
        statusText.text = buildStatusWithElapsed(lastProgressText)
        setRunningUi(true)
        uiHandler.post(elapsedTicker)

        thread {
            val tempDir = getExternalFilesDir(null) ?: filesDir
            upscaleEngine.execute(
                input.absolutePath,
                tempDir,
                selectedScale,
                preferredRuntime,
                object : XlsrUpscaleEngine.EngineCallback {
                    override fun onProgress(doneTiles: Int, totalTiles: Int, phase: String, runtimeLabel: String) {
                        runOnUiThread {
                            val progressText = "$phase $doneTiles/$totalTiles ($runtimeLabel)"
                            lastProgressText = progressText
                            val elapsedMs = System.currentTimeMillis() - upscaleStartedAt
                            statusText.text = "$progressText • ${formatElapsed(elapsedMs)}"
                            progressBar.visibility = View.VISIBLE
                            progressBar.isIndeterminate = false
                            progressBar.max = totalTiles
                            progressBar.progress = doneTiles
                        }
                    }

                    override fun onSuccess(outputFile: File, pipelineDescription: String) {
                        runOnUiThread {
                            setRunningUi(false)
                            val bmp = decodeSampledBitmap(outputFile.absolutePath, 1024, 1024)
                            if (bmp != null) {
                                statusText.text = "Đang lưu vào thư mục..."
                                val (displayPath, saveOk) = saveToUserDir(outputFile)
                                previewImage.setImageBitmap(bmp)
                                if (saveOk) {
                                    val runtimeName = getActualRuntimeName(outputFile.name)
                                    statusText.text = "Xong: $pipelineDescription · $runtimeName"
                                } else {
                                    statusText.text = "Upscale thành công nhưng lưu file lỗi: $displayPath"
                                }
                            } else {
                                outputFile.delete()
                                statusText.text = "Upscale thành công nhưng file output không decode được bitmap."
                            }
                        }
                    }

                    override fun onFailure(message: String) {
                        runOnUiThread {
                            setRunningUi(false)
                            statusText.text = "Lỗi: $message"
                        }
                    }
                }
            )
        }
    }
}
