package com.vung.upscaylultramix

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
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var previewImage: ImageView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var selectButton: Button
    private lateinit var inputFolderButton: Button
    private lateinit var upscaleButton: Button
    private lateinit var outputDirButton: Button
    private lateinit var outputDirText: TextView
    private lateinit var openOutputDirButton: Button

    private lateinit var scaleRadioGroup: RadioGroup
    private lateinit var runtimeRadioGroup: RadioGroup

    private var lastSavedUri: Uri? = null
    private var inputFile: File? = null
    private val selectedInputFiles = mutableListOf<File>()
    private var outputTreeUri: Uri? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    @Volatile private var isUpscaling = false
    @Volatile private var isBatchCancelRequested = false
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

    private val pickOutputDirectory =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (_: Exception) {
                    // Some providers grant access only for the current session.
                }
                outputTreeUri = uri
                val display = uri.lastPathSegment?.substringAfter(":") ?: uri.toString()
                outputDirText.text = "Thư mục: $display"
                statusText.text = "Đã chọn thư mục lưu ảnh."
                openOutputDirButton.isEnabled = true
                saveDirectoryToPrefs(uri)
            }
        }

    private val pickInputDirectory =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // ignore
            }
            loadImagesFromFolder(uri)
        }

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
            if (uris.isEmpty()) return@registerForActivityResult
            prepareInputFilesFromUris(uris, "ảnh đã chọn")
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

        selectButton.text = "Chọn ảnh / nhiều ảnh"
        inputFolderButton = createInputFolderButton()
        insertInputFolderButton()

        selectButton.setOnClickListener {
            pickImages.launch("image/*")
        }

        inputFolderButton.setOnClickListener {
            pickInputDirectory.launch(null)
        }

        outputDirButton.setOnClickListener {
            pickOutputDirectory.launch(null)
        }

        openOutputDirButton.setOnClickListener {
            openOutputFolder()
        }

        upscaleButton.setOnClickListener {
            if (isUpscaling) {
                isBatchCancelRequested = true
                upscaleEngine.cancel()
                lastProgressText = "Đang hủy..."
                statusText.text = buildStatusWithElapsed(lastProgressText)
                upscaleButton.isEnabled = false
            } else {
                runUpscale()
            }
        }

        scaleRadioGroup.setOnCheckedChangeListener { _, _ ->
            updateUpscaleButtonText()
        }

        restoreSavedDirectory()
    }

    private fun createInputFolderButton(): Button {
        return Button(this).apply {
            text = "Chọn thư mục ảnh"
            isAllCaps = false
            textSize = 15f
        }
    }

    private fun insertInputFolderButton() {
        val parent = selectButton.parent as? LinearLayout ?: return
        val index = parent.indexOfChild(selectButton)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52)
        ).apply {
            topMargin = dp(8)
        }
        parent.addView(inputFolderButton, index + 1, params)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
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
            } catch (_: Exception) {
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
            } catch (_: Exception) {
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
            } catch (_: Exception) {
                // ignore
            }
        }

        Toast.makeText(this, "Hãy dùng ứng dụng Files để mở thư mục lưu", Toast.LENGTH_LONG).show()
    }

    private fun loadImagesFromFolder(treeUri: Uri) {
        statusText.text = "Đang quét thư mục ảnh..."
        upscaleButton.isEnabled = false
        thread {
            val root = DocumentFile.fromTreeUri(this, treeUri)
            val docs = mutableListOf<DocumentFile>()
            if (root != null) {
                collectImageDocuments(root, docs)
            }
            val uris = docs.map { it.uri }
            runOnUiThread {
                if (uris.isEmpty()) {
                    selectedInputFiles.clear()
                    inputFile = null
                    previewImage.setImageDrawable(null)
                    statusText.text = "Không tìm thấy ảnh trong thư mục đã chọn."
                    upscaleButton.isEnabled = false
                } else {
                    prepareInputFilesFromUris(uris, "ảnh trong thư mục")
                }
            }
        }
    }

    private fun collectImageDocuments(dir: DocumentFile, out: MutableList<DocumentFile>) {
        for (child in dir.listFiles()) {
            when {
                child.isDirectory -> collectImageDocuments(child, out)
                child.isFile && isImageDocument(child) -> out.add(child)
            }
        }
    }

    private fun isImageDocument(document: DocumentFile): Boolean {
        val mime = document.type ?: ""
        if (mime.startsWith("image/")) return true
        val name = document.name?.lowercase() ?: return false
        return name.endsWith(".png") ||
            name.endsWith(".jpg") ||
            name.endsWith(".jpeg") ||
            name.endsWith(".webp") ||
            name.endsWith(".bmp")
    }

    private fun prepareInputFilesFromUris(uris: List<Uri>, sourceLabel: String) {
        statusText.text = "Đang nạp ${uris.size} $sourceLabel..."
        upscaleButton.isEnabled = false
        thread {
            val prepared = mutableListOf<File>()
            var skipped = 0
            for ((index, uri) in uris.distinct().withIndex()) {
                try {
                    val copied = copyUriToCache(uri, index)
                    if (isImageWithinLimit(copied)) {
                        prepared.add(copied)
                    } else {
                        skipped++
                        copied.delete()
                    }
                } catch (_: Exception) {
                    skipped++
                }
            }

            runOnUiThread {
                selectedInputFiles.clear()
                selectedInputFiles.addAll(prepared)
                inputFile = prepared.firstOrNull()

                if (prepared.isNotEmpty()) {
                    showPreview(prepared.first())
                    val skippedText = if (skipped > 0) ", bỏ qua $skipped ảnh lỗi/quá lớn" else ""
                    statusText.text = "Đã chọn ${prepared.size} ảnh$skippedText."
                    upscaleButton.isEnabled = true
                } else {
                    previewImage.setImageDrawable(null)
                    statusText.text = "Không có ảnh hợp lệ. Giới hạn: tối đa 6M pixels và cạnh dài không quá 3000px."
                    upscaleButton.isEnabled = false
                }
                updateUpscaleButtonText()
            }
        }
    }

    private fun copyUriToCache(uri: Uri, index: Int = 0): File {
        val rawName = getDisplayName(uri) ?: "input_$index.png"
        val safeName = sanitizeFileName(rawName)
        val outFile = File(cacheDir, "input_${System.currentTimeMillis()}_${index}_$safeName")
        val input = contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Không mở được ảnh đầu vào")
        input.use { inp ->
            FileOutputStream(outFile).use { output ->
                inp.copyTo(output)
            }
        }
        return outFile
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun isImageWithinLimit(file: File): Boolean {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val w = options.outWidth
        val h = options.outHeight
        if (w <= 0 || h <= 0) return false
        val pixels = w.toLong() * h
        val longEdge = if (w > h) w else h
        return pixels <= 6_000_000L && longEdge <= 3000
    }

    private fun showPreview(file: File) {
        val bmp = decodeSampledBitmap(file.absolutePath, 1024, 1024)
        if (bmp != null) {
            previewImage.setImageBitmap(bmp)
        } else {
            previewImage.setImageURI(Uri.fromFile(file))
        }
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

    private fun updateUpscaleButtonText() {
        if (!::upscaleButton.isInitialized) return
        if (isUpscaling) {
            upscaleButton.text = "Hủy"
            return
        }
        val scaleText = "${getSelectedScale()}x"
        upscaleButton.text = if (selectedInputFiles.size > 1) {
            "Bắt đầu Upscale ${selectedInputFiles.size} ảnh ($scaleText)"
        } else {
            "Bắt đầu Upscale $scaleText"
        }
    }

    private fun setRunningUi(running: Boolean) {
        isUpscaling = running
        selectButton.isEnabled = !running
        inputFolderButton.isEnabled = !running
        outputDirButton.isEnabled = !running
        scaleRadioGroup.isEnabled = !running
        runtimeRadioGroup.isEnabled = !running
        openOutputDirButton.isEnabled = !running && (outputTreeUri != null || lastSavedUri != null)
        upscaleButton.isEnabled = if (running) true else selectedInputFiles.isNotEmpty()

        updateUpscaleButtonText()
        if (!running) {
            progressBar.visibility = View.GONE
            progressBar.isIndeterminate = false
            progressBar.progress = 0
            uiHandler.removeCallbacks(elapsedTicker)
        }
    }

    private fun runUpscale() {
        val inputs = selectedInputFiles.toList()
        if (inputs.isEmpty()) return

        val selectedScale = getSelectedScale()
        val preferredRuntime = getPreferredRuntime()
        val totalImages = inputs.size

        isBatchCancelRequested = false
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

        lastProgressText = "Đang khởi tạo $pipelineLabel cho $totalImages ảnh..."
        statusText.text = buildStatusWithElapsed(lastProgressText)
        setRunningUi(true)
        uiHandler.post(elapsedTicker)

        thread {
            val tempDir = getExternalFilesDir(null) ?: filesDir
            var completed = 0
            var failed = 0
            var cancelled = false

            for ((index, input) in inputs.withIndex()) {
                if (isBatchCancelRequested) {
                    cancelled = true
                    break
                }

                val imageNo = index + 1
                var itemSaved = false
                var itemCancelled = false

                upscaleEngine.execute(
                    input.absolutePath,
                    tempDir,
                    selectedScale,
                    preferredRuntime,
                    object : XlsrUpscaleEngine.EngineCallback {
                        override fun onProgress(doneTiles: Int, totalTiles: Int, phase: String, runtimeLabel: String) {
                            runOnUiThread {
                                val progressText = "Ảnh $imageNo/$totalImages · $phase $doneTiles/$totalTiles ($runtimeLabel)"
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
                            val (displayPath, saveOk) = saveToUserDir(outputFile)
                            itemSaved = saveOk
                            val bmp = decodeSampledBitmap(outputFile.absolutePath, 1024, 1024)
                            runOnUiThread {
                                if (bmp != null) {
                                    previewImage.setImageBitmap(bmp)
                                }
                                val runtimeName = getActualRuntimeName(outputFile.name)
                                statusText.text = if (saveOk) {
                                    "Xong ảnh $imageNo/$totalImages: $pipelineDescription · $runtimeName"
                                } else {
                                    "Upscale xong ảnh $imageNo/$totalImages nhưng lưu lỗi: $displayPath"
                                }
                            }
                        }

                        override fun onFailure(message: String) {
                            if (message.contains("hủy", ignoreCase = true)) {
                                itemCancelled = true
                                cancelled = true
                            }
                            runOnUiThread {
                                statusText.text = "Lỗi ảnh $imageNo/$totalImages: $message"
                            }
                        }
                    }
                )

                when {
                    itemCancelled || isBatchCancelRequested -> {
                        cancelled = true
                        break
                    }
                    itemSaved -> completed++
                    else -> failed++
                }
            }

            runOnUiThread {
                setRunningUi(false)
                val elapsed = formatElapsed(System.currentTimeMillis() - upscaleStartedAt)
                statusText.text = if (cancelled) {
                    "Đã hủy. Hoàn tất $completed/$totalImages ảnh, lỗi $failed ảnh • $elapsed"
                } else {
                    "Hoàn tất batch: $completed/$totalImages ảnh, lỗi $failed ảnh • $elapsed"
                }
            }
        }
    }
}
