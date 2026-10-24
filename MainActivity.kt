package com.masscut.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.Locale

private data class ClipItem(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    var startMs: Long = 0L,
    var endMs: Long = durationMs
)

@UnstableApi
class MainActivity : ComponentActivity() {
    private val clips = mutableListOf<ClipItem>()
    private lateinit var clipList: LinearLayout
    private lateinit var status: TextView
    private lateinit var exportButton: Button
    private lateinit var addButton: Button
    private lateinit var styleSpinner: Spinner
    private lateinit var durationSpinner: Spinner
    private lateinit var formatSpinner: Spinner
    private lateinit var titleInput: EditText
    private var transformer: Transformer? = null
    private var busy = false
    private var pendingExportFile: File? = null

    private val pickVideos = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val found = linkedSetOf<Uri>()
        data.clipData?.let { clipData ->
            for (i in 0 until clipData.itemCount) found.add(clipData.getItemAt(i).uri)
        }
        data.data?.let { found.add(it) }
        var added = 0
        found.forEach { uri ->
            if (clips.none { it.uri == uri }) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { }
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, uri)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    if (duration > 0) {
                        val name = queryDisplayName(uri) ?: "Video ${clips.size + 1}"
                        clips.add(ClipItem(uri, name, duration))
                        added++
                    }
                } catch (_: Exception) {
                    Toast.makeText(this, "Couldn't read one of the selected videos", Toast.LENGTH_SHORT).show()
                } finally { try { retriever.release() } catch (_: Exception) {} }
            }
        }
        status.text = if (added > 0) "$added clip(s) added. Everything stays on your phone. Export merges/trims; music, effects and crop presets are future upgrades." else "No new videos added."
        renderClips()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFFF7F7F8.toInt()
        window.navigationBarColor = 0xFFF7F7F8.toInt()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(20))
            setBackgroundColor(0xFFF7F7F8.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val brand = TextView(this).apply {
            text = "M  MassCut AI"
            textSize = 23f; setTextColor(0xFF17181B.toInt()); setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(10))
        }
        content.addView(brand)
        content.addView(text("Your clips. Your mass entry.", 28f, 0xFF17181B.toInt(), true))
        content.addView(text("Trim, arrange and export a reel directly on your Android phone.", 14f, 0xFF686B73.toInt(), false).apply { setPadding(0, dp(5), 0, dp(16)) })

        addButton = button("＋  Choose videos", true) { chooseVideos() }
        content.addView(addButton, matchWrap())
        content.addView(text("SELECTED CLIPS", 12f, 0xFF777B84.toInt(), true).apply { setPadding(0, dp(18), 0, dp(8)) })
        clipList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(clipList)

        content.addView(text("EDIT STYLE", 12f, 0xFF777B84.toInt(), true).apply { setPadding(0, dp(18), 0, dp(7)) })
        styleSpinner = spinner(listOf("Tamil Mass", "Bike Delivery", "Dark Cinematic", "Love / Feel", "Hype Reel"))
        content.addView(styleSpinner, matchWrap())
        content.addView(text("TARGET LENGTH", 12f, 0xFF777B84.toInt(), true).apply { setPadding(0, dp(14), 0, dp(7)) })
        durationSpinner = spinner(listOf("15 seconds", "20 seconds", "30 seconds", "45 seconds"))
        content.addView(durationSpinner, matchWrap())
        content.addView(text("VIDEO FORMAT", 12f, 0xFF777B84.toInt(), true).apply { setPadding(0, dp(14), 0, dp(7)) })
        formatSpinner = spinner(listOf("Keep original dimensions", "Vertical reel (9:16) — coming next", "Square (1:1) — coming next"))
        content.addView(formatSpinner, matchWrap())
        content.addView(text("OPENING TITLE (OPTIONAL)", 12f, 0xFF777B84.toInt(), true).apply { setPadding(0, dp(14), 0, dp(7)) })
        titleInput = EditText(this).apply {
            hint = "e.g. THE WAIT IS OVER"; textSize = 14f; singleLine = true
            setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(0xFFFFFFFF.toInt())
        }
        content.addView(titleInput, matchWrap())

        exportButton = button("Export MP4", true) { startExport() }
        exportButton.isEnabled = false
        val footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        status = text("Choose two or more clips to get started. Export runs on-device.", 13f, 0xFF656A73.toInt(), false)
        footer.addView(status)
        root.addView(footer, matchWrap())
        root.addView(exportButton, matchWrap())
        setContentView(root)
        renderClips()
    }

    private fun chooseVideos() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        pickVideos.launch(intent)
    }

    private fun renderClips() {
        clipList.removeAllViews()
        if (clips.isEmpty()) {
            clipList.addView(text("No clips yet. Select videos from your gallery.", 14f, 0xFF777B84.toInt(), false).apply { setPadding(dp(12), dp(18), dp(12), dp(18)) })
        }
        clips.forEachIndexed { index, clip ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(0xFFFFFFFF.toInt())
            }
            val name = text("${index + 1}. ${clip.name}", 14f, 0xFF222328.toInt(), true)
            card.addView(name)
            card.addView(text("${formatTime(clip.startMs)} – ${formatTime(clip.endMs)}  ·  ${formatTime(clip.endMs - clip.startMs)} selected", 12f, 0xFF777B84.toInt(), false).apply { setPadding(0, dp(3), 0, dp(8)) })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            actions.addView(button("Trim", false) { showTrimDialog(index) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(5) })
            actions.addView(button("↑", false) { if (index > 0) { val item = clips.removeAt(index); clips.add(index - 1, item); renderClips() } }, LinearLayout.LayoutParams(dp(48), dp(40)).apply { marginEnd = dp(5) })
            actions.addView(button("↓", false) { if (index < clips.lastIndex) { val item = clips.removeAt(index); clips.add(index + 1, item); renderClips() } }, LinearLayout.LayoutParams(dp(48), dp(40)).apply { marginEnd = dp(5) })
            actions.addView(button("Remove", false) { clips.removeAt(index); renderClips() }, LinearLayout.LayoutParams(-2, dp(40)))
            card.addView(actions)
            clipList.addView(card, matchWrap().apply { bottomMargin = dp(8) })
        }
        exportButton.isEnabled = clips.isNotEmpty() && !busy
        addButton.isEnabled = !busy
    }

    private fun showTrimDialog(index: Int) {
        val clip = clips[index]
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val start = EditText(this).apply { hint = "Start seconds (e.g. 0)"; inputType = 8194; setText(String.format(Locale.US, "%.2f", clip.startMs / 1000.0)) }
        val end = EditText(this).apply { hint = "End seconds (e.g. 8.5)"; inputType = 8194; setText(String.format(Locale.US, "%.2f", clip.endMs / 1000.0)) }
        box.addView(text("Trim ${clip.name}", 16f, 0xFF222328.toInt(), true))
        box.addView(text("Enter start and end times in seconds. At least 0.1 seconds must remain.", 13f, 0xFF777B84.toInt(), false))
        box.addView(start); box.addView(end)
        AlertDialog.Builder(this).setView(box).setNegativeButton("Cancel", null).setPositiveButton("Apply") { _, _ ->
            val s = ((start.text.toString().toDoubleOrNull() ?: 0.0) * 1000).toLong().coerceIn(0, clip.durationMs - 100)
            val e = ((end.text.toString().toDoubleOrNull() ?: clip.durationMs / 1000.0) * 1000).toLong().coerceIn(s + 100, clip.durationMs)
            clip.startMs = s; clip.endMs = e; renderClips()
        }.show()
    }

    private fun startExport() {
        if (busy || clips.isEmpty()) return
        val selected = clips.toList()
        val totalSelectedMs = selected.sumOf { it.endMs - it.startMs }
        if (totalSelectedMs < 100) { status.text = "Trim range is too short."; return }
        val targetMs = when (durationSpinner.selectedItemPosition) { 0 -> 15_000L; 1 -> 20_000L; 2 -> 30_000L; else -> 45_000L }
        // Keep the original order and trim ranges; target length is a preset goal, not yet an automatic speed/crop operation.
        var remainingMs = targetMs
        val selectedTotal = selected.sumOf { it.endMs - it.startMs }.coerceAtLeast(1L)
        val sequenceItems = selected.mapIndexed { index, clip ->
            val sourceLength = clip.endMs - clip.startMs
            val remainingClips = selected.size - index
            // If the chosen footage is longer than the target, proportionally shorten each clip while preserving order.
            val allottedLength = if (selectedTotal > targetMs) {
                minOf(sourceLength, (sourceLength.toDouble() / selectedTotal * targetMs).toLong().coerceAtLeast(100L))
            } else sourceLength
            val actualLength = minOf(allottedLength, remainingMs).coerceAtLeast(100L)
            remainingMs = (remainingMs - actualLength).coerceAtLeast(0L)
            val endMs = minOf(clip.endMs, clip.startMs + actualLength)
            val mediaItem = MediaItem.Builder().setUri(clip.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clip.startMs).setEndPositionMs(endMs).build()
            ).build()
            EditedMediaItem.Builder(mediaItem).build()
        }
        val outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: cacheDir
        if (!outputDir.exists()) outputDir.mkdirs()
        val output = File(outputDir, "masscut_${System.currentTimeMillis()}.mp4")
        pendingExportFile = output
        busy = true; renderClips()
        status.text = "Exporting ${selected.size} clips… Keep MassCut AI open."
        exportButton.text = "Exporting…"
        val sequence = EditedMediaItemSequence(sequenceItems)
        val composition = Composition.Builder(sequence).build()
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                busy = false; renderClips(); exportButton.text = "Export MP4"
                try {
                    val saved = copyToMovies(output)
                    status.text = "Export complete · Saved to Movies/MassCut AI/${saved.second}"
                    showExportDone(saved.first, saved.second)
                } catch (e: Exception) {
                    status.text = "Export finished but couldn't copy to Gallery: ${e.localizedMessage ?: "unknown error"}. File: ${output.absolutePath}"
                }
            }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                busy = false; renderClips(); exportButton.text = "Export MP4"
                status.text = "Export failed: ${exportException.localizedMessage ?: "unsupported clip format"}. Try fewer clips or standard MP4/H.264 videos."
            }
        }
        transformer = Transformer.Builder(this).addListener(listener).build()
        try { transformer?.start(composition, output.absolutePath) }
        catch (e: Exception) {
            busy = false; renderClips(); exportButton.text = "Export MP4"
            status.text = "Could not start export: ${e.localizedMessage ?: "unknown error"}"
        }
    }

    private fun copyToMovies(source: File): Pair<Uri, String> {
        val folder = "Movies/MassCut AI"
        val name = "MassCut_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, folder)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
        try {
            contentResolver.openOutputStream(uri).use { output ->
                requireNotNull(output) { "Couldn't open output file" }
                FileInputStream(source).use { input -> input.copyTo(output) }
            }
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
            return uri to name
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun showExportDone(uri: Uri, name: String) {
        AlertDialog.Builder(this).setTitle("MP4 ready")
            .setMessage("Saved $name to your phone's Movies/MassCut AI folder.")
            .setNegativeButton("Done", null)
            .setPositiveButton("Share") { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply { type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                startActivity(Intent.createChooser(share, "Share your MassCut edit"))
            }.show()
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setBackgroundColor(0xFFFFFFFF.toInt())
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
    }
    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; setOnClickListener { onClick() }
        if (primary) { setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF6A35.toInt())); setTextColor(0xFF191919.toInt()) }
        else { setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE8E9ED.toInt())); setTextColor(0xFF272930.toInt()) }
    }
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun formatTime(ms: Long): String = String.format(Locale.US, "%.1fs", ms.coerceAtLeast(0) / 1000.0)
}
