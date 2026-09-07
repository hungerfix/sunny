package com.hazbu.xcam.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.hazbu.xcam.R
import com.hazbu.xcam.data.Constants.KEY_IS_MIRRORED
import com.hazbu.xcam.data.Constants.KEY_ROTATION_ANGLE
import com.hazbu.xcam.data.Constants.KEY_MEDIA_PATH
import com.hazbu.xcam.data.Constants.PREFS_NAME
import com.hazbu.xcam.data.isNetworkUrl
import java.io.File
import java.io.FileOutputStream
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), XposedServiceHelper.OnServiceListener {

    companion object {
        private const val VIRTUAL_PREFIX = "virtual."
        private const val DEFAULT_VIDEO_NAME = "virtual.mp4"
    }

    private lateinit var ivPreview: ImageView
    private lateinit var tvNoPreview: TextView
    private lateinit var btnDeleteMedia: MaterialButton
    private lateinit var btnMirror: MaterialButton
    private lateinit var btnRotateLeft: MaterialButton
    private lateinit var btnRotateRight: MaterialButton
    private lateinit var btnSelectVideo: MaterialButton
    private lateinit var btnEnterUrl: MaterialButton
    private lateinit var tvModuleStatus: TextView
    private lateinit var cardModuleStatus: com.google.android.material.card.MaterialCardView
    private lateinit var cardScopedApps: com.google.android.material.card.MaterialCardView
    private lateinit var layoutScopedApps: LinearLayout

    private var isMirrored = false
    private var rotationAngle = 0
    private var mXposedService: XposedService? = null

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data: Intent? = result.data
            data?.data?.let { uri ->
                copyMediaToInternal(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        XposedServiceHelper.registerListener(this)
        setupWindowInsets()
        setupUI()
        loadSettings()
        updateModuleStatusUI()
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        updateModuleStatusUI()
    }

    override fun onServiceBind(service: XposedService) {
        mXposedService = service
        runOnUiThread { updateModuleStatusUI() }
    }

    override fun onServiceDied(service: XposedService) {
        mXposedService = null
        runOnUiThread { updateModuleStatusUI() }
    }

    private fun updateModuleStatusUI() {
        val officialScope = getOfficialScope().filter { it != packageName }
        val isActive = (mXposedService != null && officialScope.isNotEmpty()) || checkSelfActive()

        if (isActive) {
            tvModuleStatus.text = getString(R.string.status_module_active)
            val activeColor = MaterialColors.getColor(tvModuleStatus, androidx.appcompat.R.attr.colorPrimary)
            tvModuleStatus.setTextColor(activeColor)
            cardModuleStatus.strokeColor = activeColor
            refreshLSPosedScope()
        } else {
            tvModuleStatus.text = getString(R.string.status_module_inactive)
            val inactiveColor = MaterialColors.getColor(tvModuleStatus, com.google.android.material.R.attr.colorOnSurfaceVariant)
            val strokeColor = MaterialColors.getColor(tvModuleStatus, com.google.android.material.R.attr.colorOutline)
            tvModuleStatus.setTextColor(inactiveColor)
            cardModuleStatus.strokeColor = strokeColor
            cardScopedApps.visibility = View.GONE
        }
    }

    private fun setupWindowInsets() {
        val mainView = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val basePadding = (24 * density).toInt()
            v.setPadding(
                systemBars.left + basePadding,
                systemBars.top + basePadding,
                systemBars.right + basePadding,
                systemBars.bottom + basePadding
            )
            insets
        }
    }

    private fun setupUI() {
        val tvTitle = findViewById<TextView>(R.id.tv_title)
        setupTitleSpannable(tvTitle)
        ivPreview = findViewById(R.id.iv_preview)
        tvNoPreview = findViewById(R.id.tv_no_preview)
        btnDeleteMedia = findViewById(R.id.btn_delete_media)
        btnMirror = findViewById(R.id.btn_mirror)
        btnRotateLeft = findViewById(R.id.btn_rotate_left)
        btnRotateRight = findViewById(R.id.btn_rotate_right)
        btnSelectVideo = findViewById(R.id.btn_select_video)
        btnEnterUrl = findViewById(R.id.btn_enter_url)
        tvModuleStatus = findViewById(R.id.tv_module_status)
        cardModuleStatus = findViewById(R.id.card_module_status)
        cardScopedApps = findViewById(R.id.card_scoped_apps)
        layoutScopedApps = findViewById(R.id.layout_scoped_apps)
        btnMirror.setOnClickListener {
            isMirrored = !isMirrored
            saveSettings()
            updatePreviewTransform()
        }
        btnRotateLeft.setOnClickListener {
            rotationAngle = (rotationAngle - 90 + 360) % 360
            saveSettings()
            updatePreviewTransform()
        }
        btnRotateRight.setOnClickListener {
            rotationAngle = (rotationAngle + 90) % 360
            saveSettings()
            updatePreviewTransform()
        }
        btnDeleteMedia.setOnClickListener { deleteMedia() }
        btnSelectVideo.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            }
            videoPickerLauncher.launch(intent)
        }
        btnEnterUrl.setOnClickListener { showStreamUrlDialog() }
    }

    // -------------------------------------------------------------------------
    // Stream URL dialog
    // -------------------------------------------------------------------------

    private fun showStreamUrlDialog() {
        val currentUrl = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_MEDIA_PATH, "") ?: ""

        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.hint_stream_url)
            setText(if (currentUrl.isNetworkUrl()) currentUrl else "")
            setSingleLine()
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_stream_url))
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = editText.text.toString().trim()
                if (url.isEmpty()) {
                    // Empty input → clear the current media
                    deleteMedia()
                    return@setPositiveButton
                }
                if (!url.isNetworkUrl()) {
                    Toast.makeText(this, getString(R.string.toast_invalid_url), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                saveStreamUrl(url)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveStreamUrl(url: String) {
        // Remove any previously cached local virtual.* files
        filesDir.listFiles { _, name -> name.startsWith(VIRTUAL_PREFIX) }?.forEach { it.delete() }

        isMirrored = false
        rotationAngle = 0
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(KEY_MEDIA_PATH, url)
            putBoolean(KEY_IS_MIRRORED, false)
            putInt(KEY_ROTATION_ANGLE, 0)
        }
        updatePreview(url)
        updatePreviewTransform()
        Toast.makeText(this, getString(R.string.toast_stream_url_saved), Toast.LENGTH_SHORT).show()
    }

    private fun copyMediaToInternal(uri: Uri) {
        try {
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val isImage = mimeType.startsWith("image/")
            val isVideo = mimeType.startsWith("video/")

            if (!isImage && !isVideo) {
                Toast.makeText(this, "Invalid file type. Only images and videos are supported.", Toast.LENGTH_LONG).show()
                return
            }
            
            filesDir.listFiles { _, name -> name.startsWith(VIRTUAL_PREFIX) }?.forEach { it.delete() }

            val extension = android.webkit.MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType) ?: "bin"

            if (isImage) {
                val internalImage = File(filesDir, VIRTUAL_PREFIX + extension)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(internalImage).use { output -> input.copyTo(output) }
                }
                
                val outputVideo = File(filesDir, DEFAULT_VIDEO_NAME)
                Toast.makeText(this, "Converting image to video...", Toast.LENGTH_SHORT).show()
                
                com.hazbu.xcam.utils.MediaConverter.convertImageToMp4(
                    this, Uri.fromFile(internalImage), outputVideo
                ) { success ->
                    runOnUiThread {
                        internalImage.delete()
                        if (success) {
                            saveMediaPath(outputVideo.absolutePath)
                            Toast.makeText(this, R.string.toast_media_imported, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Conversion failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                val internalFile = File(filesDir, VIRTUAL_PREFIX + extension)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(internalFile).use { output ->
                        input.copyTo(output)
                    }
                }
                saveMediaPath(internalFile.absolutePath)
                Toast.makeText(this, R.string.toast_media_imported, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            val errorMsg = getString(R.string.toast_media_import_failed, e.message)
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val mediaPath = prefs.getString(KEY_MEDIA_PATH, "") ?: ""
        isMirrored = prefs.getBoolean(KEY_IS_MIRRORED, false)
        rotationAngle = prefs.getInt(KEY_ROTATION_ANGLE, 0)
        updatePreview(mediaPath)
        updatePreviewTransform()
    }

    private fun updatePreviewTransform() {
        ivPreview.scaleX = if (isMirrored) -1f else 1f
        ivPreview.rotation = rotationAngle.toFloat()
        val activeColor = MaterialColors.getColor(btnMirror, androidx.appcompat.R.attr.colorPrimary)
        val activeIconColor = MaterialColors.getColor(btnMirror, com.google.android.material.R.attr.colorOnPrimary, android.graphics.Color.WHITE)
        val inactiveColor = MaterialColors.getColor(btnMirror, com.google.android.material.R.attr.colorSecondaryContainer, android.graphics.Color.LTGRAY)
        val inactiveIconColor = MaterialColors.getColor(btnMirror, com.google.android.material.R.attr.colorOnSecondaryContainer, android.graphics.Color.DKGRAY)
        btnMirror.setBackgroundColor(if (isMirrored) activeColor else inactiveColor)
        btnMirror.iconTint = android.content.res.ColorStateList.valueOf(if (isMirrored) activeIconColor else inactiveIconColor)
    }

    private fun updatePreview(path: String) {
        // ── Network URL (M3U8 / HLS / HTTP) ──────────────────────────────────
        if (path.isNetworkUrl()) {
            ivPreview.setImageBitmap(null)
            tvNoPreview.text = getString(R.string.label_stream_preview)
            tvNoPreview.visibility = View.VISIBLE
            btnDeleteMedia.visibility = View.VISIBLE
            // Attempt to grab a thumbnail on a background thread
            Executors.newSingleThreadExecutor().execute {
                val bmp = try {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(path, emptyMap<String, String>())
                    val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_NEXT_SYNC)
                    r.release()
                    frame
                } catch (_: Exception) { null }
                runOnUiThread {
                    if (bmp != null) {
                        ivPreview.setImageBitmap(bmp)
                        tvNoPreview.visibility = View.GONE
                    }
                    // If thumbnail failed the 'Live Stream' label stays visible
                }
            }
            return
        }

        // ── Empty / missing local file ────────────────────────────────────────
        if (path.isEmpty() || !File(path).exists()) {
            ivPreview.setImageBitmap(null)
            tvNoPreview.text = getString(R.string.label_none)
            tvNoPreview.visibility = View.VISIBLE
            btnDeleteMedia.visibility = View.GONE
            return
        }

        // ── Local file ────────────────────────────────────────────────────────
        tvNoPreview.text = getString(R.string.label_none)
        tvNoPreview.visibility = View.GONE
        btnDeleteMedia.visibility = View.VISIBLE
        try {
            if (path.endsWith(".mp4", ignoreCase = true) || path.endsWith(".m3u8", ignoreCase = true)) {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(path)
                val bitmap = retriever.getFrameAtTime(1000000)
                ivPreview.setImageBitmap(bitmap)
                retriever.release()
            } else {
                val bitmap = BitmapFactory.decodeFile(path)
                ivPreview.setImageBitmap(bitmap)
            }
        } catch (_: Exception) {
            ivPreview.setImageBitmap(null)
            tvNoPreview.visibility = View.VISIBLE
        }
    }

    private fun saveSettings() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(KEY_IS_MIRRORED, isMirrored)
            putInt(KEY_ROTATION_ANGLE, rotationAngle)
        }
    }

    private fun saveMediaPath(path: String) {
        isMirrored = false
        rotationAngle = 0
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(KEY_MEDIA_PATH, path)
            putBoolean(KEY_IS_MIRRORED, isMirrored)
            putInt(KEY_ROTATION_ANGLE, rotationAngle)
        }
        updatePreview(path)
        updatePreviewTransform()
    }

    private fun deleteMedia() {
        filesDir.listFiles { _, name -> name.startsWith(VIRTUAL_PREFIX) }?.forEach { it.delete() }
        saveMediaPath("")
        Toast.makeText(this, R.string.toast_media_removed, Toast.LENGTH_SHORT).show()
    }

    private fun setupTitleSpannable(tvTitle: TextView) {
        val titleText = tvTitle.text.toString()
        val spannable = SpannableStringBuilder(titleText)
        val primaryColor = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, "#FF6200EE".toColorInt())
        spannable.setSpan(ForegroundColorSpan(primaryColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvTitle.text = spannable
    }

    private fun checkSelfActive(): Boolean = false

    private fun getOfficialScope(): List<String> {
        return try {
            mXposedService?.scope ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun refreshLSPosedScope() {
        layoutScopedApps.removeAllViews()
        val officialScope = getOfficialScope().filter { it != packageName }
        if (officialScope.isNotEmpty()) {
            officialScope.forEach { addAppIconToLayout(it) }
            cardScopedApps.visibility = View.VISIBLE
        } else {
            cardScopedApps.visibility = View.GONE
        }
    }

    private fun addAppIconToLayout(pkgName: String) {
        try {
            val icon = packageManager.getApplicationIcon(pkgName)
            val size = (40 * resources.displayMetrics.density).toInt()
            val imageView = ImageView(this).apply {
                val params = LinearLayout.LayoutParams(size, size)
                params.setMargins(0, 0, 16, 0)
                layoutParams = params
                setImageDrawable(icon)
                contentDescription = pkgName
                setOnClickListener { Toast.makeText(this@MainActivity, pkgName, Toast.LENGTH_SHORT).show() }
            }
            layoutScopedApps.addView(imageView)
        } catch (_: Exception) {}
    }
}
