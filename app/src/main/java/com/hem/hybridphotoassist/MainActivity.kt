package com.hem.hybridphotoassist

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), EditorView.Listener {

    private lateinit var editor: EditorView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var emptyHint: TextView
    private lateinit var brushRow: View
    private lateinit var moveRow: View
    private lateinit var toolScroll: View
    private lateinit var toolButtons: Map<EditorView.Tool, MaterialButton>
    private lateinit var actionButtons: List<View>

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false

    // Editing state (touched only on the worker thread or while not busy).
    private var image: Pixels? = null
    private var bitmap: Bitmap? = null
    private var selection: Mask? = null
    private val history = History()
    private var selector: Selector? = null
    private var selectorDirty = true
    private var inpainter: Inpainter? = null
    private var lama: LamaModel? = null
    private val downloader by lazy { ModelDownloader(this) }

    // Move session.
    private var moveBefore: Pixels? = null
    private var moveBase: Pixels? = null
    private var moveCut: Bitmap? = null
    private var moveChanged: Box? = null

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) openPhoto(uri)
    }
    private val pickBackground = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) applyBackgroundPhoto(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        editor = findViewById(R.id.editor)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        emptyHint = findViewById(R.id.emptyHint)
        brushRow = findViewById(R.id.brushRow)
        moveRow = findViewById(R.id.moveRow)
        toolScroll = findViewById(R.id.toolScroll)
        editor.listener = this

        toolButtons = mapOf(
            EditorView.Tool.SELECT to findViewById(R.id.toolSelect),
            EditorView.Tool.BRUSH_ADD to findViewById(R.id.toolAdd),
            EditorView.Tool.BRUSH_REMOVE to findViewById(R.id.toolRemove)
        )
        toolButtons.forEach { (tool, b) -> b.setOnClickListener { setTool(tool) } }

        findViewById<View>(R.id.btnOpen).setOnClickListener {
            if (!busy) pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<View>(R.id.btnUndo).setOnClickListener { undoRedo(true) }
        findViewById<View>(R.id.btnRedo).setOnClickListener { undoRedo(false) }
        findViewById<View>(R.id.btnSave).setOnClickListener { save() }
        findViewById<View>(R.id.toolClear).setOnClickListener { clearSelection() }
        findViewById<View>(R.id.toolErase).setOnClickListener { withFillModel { erase() } }
        findViewById<View>(R.id.toolMove).setOnClickListener { withFillModel { startMove() } }
        findViewById<View>(R.id.toolBackground).setOnClickListener { chooseBackground() }
        findViewById<View>(R.id.btnMoveApply).setOnClickListener { applyMove() }
        findViewById<View>(R.id.btnMoveCancel).setOnClickListener { cancelMove() }
        actionButtons = listOf<View>(
            findViewById(R.id.btnOpen), findViewById(R.id.btnUndo), findViewById(R.id.btnRedo),
            findViewById(R.id.btnSave), findViewById(R.id.toolClear), findViewById(R.id.toolErase),
            findViewById(R.id.toolMove), findViewById(R.id.toolBackground)
        ) + toolButtons.values

        val seek = findViewById<SeekBar>(R.id.brushSize)
        val density = resources.displayMetrics.density
        editor.brushRadiusPx = (8 + seek.progress) * density
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { editor.brushRadiusPx = (8 + p) * density }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        setTool(EditorView.Tool.SELECT)
        setStatus("Open a photo to start. Everything runs on this phone.")
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.execute {
            runCatching { selector?.close() }
            runCatching { lama?.close() }
        }
        worker.shutdown()
    }

    private fun handleIncoming(i: Intent?) {
        val uri: Uri? = when (i?.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                                  else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_EDIT -> i.data
            else -> null
        }
        if (uri != null) openPhoto(uri)
    }

    // ------------------------------------------------------------------ helpers

    private fun setStatus(text: String) { status.text = text }

    private fun setTool(tool: EditorView.Tool) {
        editor.tool = tool
        val lime = getColor(R.color.hem_lime); val dim = getColor(R.color.hem_dim)
        toolButtons.forEach { (t, b) -> b.setTextColor(if (t == tool) lime else dim); b.strokeWidth = if (t == tool) 4 else 1 }
        brushRow.visibility = if (tool == EditorView.Tool.BRUSH_ADD || tool == EditorView.Tool.BRUSH_REMOVE) View.VISIBLE else View.GONE
        if ((tool == EditorView.Tool.BRUSH_ADD || tool == EditorView.Tool.BRUSH_REMOVE) && selection == null) {
            image?.let { selection = Mask(it.width, it.height); editor.setSelection(selection) }
        }
        when (tool) {
            EditorView.Tool.SELECT -> if (image != null) setStatus("Tap a person or object to select it. Tap more to add. Pinch to zoom.")
            EditorView.Tool.BRUSH_ADD -> setStatus("Paint to add to the selection.")
            EditorView.Tool.BRUSH_REMOVE -> setStatus("Paint to remove from the selection.")
            else -> {}
        }
    }

    private fun setBusy(b: Boolean, text: String? = null) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        actionButtons.forEach { it.isEnabled = !b }
        editor.isEnabled = !b
        if (text != null) setStatus(text)
    }

    /** Runs [work] on the worker thread with the busy UI, then [done] on the main thread. */
    private fun <T> runBusy(text: String, work: () -> T, done: (T) -> Unit) {
        if (busy) return
        setBusy(true, text)
        worker.execute {
            val r = runCatching(work)
            runOnUiThread {
                setBusy(false)
                r.onSuccess(done).onFailure { e ->
                    setStatus("Something went wrong: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun pushImageToBitmap() {
        val img = image ?: return
        val bmp = bitmap ?: return
        bmp.setPixels(img.data, 0, img.width, 0, 0, img.width, img.height)
    }

    private fun needImage(): Pixels? {
        val img = image
        if (img == null) setStatus("Open a photo first.")
        return img
    }

    private fun needSelection(): Mask? {
        val m = selection
        if (m == null || m.isEmpty()) { setStatus("Select something first: tap it with Select, or paint with Brush +."); return null }
        return m
    }

    // ------------------------------------------------------------------ open

    private fun openPhoto(uri: Uri) {
        if (moveBefore != null) cancelMove()
        runBusy("Opening photo…", {
            val src = ImageDecoder.createSource(contentResolver, uri)
            var decoded = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                val w = info.size.width; val h = info.size.height
                val long = max(w, h)
                if (long > MAX_SIDE) {
                    val s = MAX_SIDE.toFloat() / long
                    decoder.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
                }
            }
            if (decoded.config != Bitmap.Config.ARGB_8888 || !decoded.isMutable) {
                val c = decoded.copy(Bitmap.Config.ARGB_8888, true)
                decoded.recycle()
                decoded = c
            }
            val px = IntArray(decoded.width * decoded.height)
            decoded.getPixels(px, 0, decoded.width, 0, 0, decoded.width, decoded.height)
            Pair(decoded, Pixels(decoded.width, decoded.height, px))
        }) { (bmp, px) ->
            bitmap?.recycle()
            bitmap = bmp
            image = px
            selection = null
            history.clear()
            selectorDirty = true
            emptyHint.visibility = View.GONE
            editor.setSelection(null)
            editor.setImage(bmp, resetView = true)
            setTool(EditorView.Tool.SELECT)
            setStatus("${px.width} × ${px.height}. Tap a person or object to select it.")
        }
    }

    // ------------------------------------------------------------------ select

    override fun onTapImage(x: Float, y: Float) {
        val img = image ?: return
        val bmp = bitmap ?: return
        runBusy("Finding the object…", {
            val sel = selector ?: Selector(this).also { selector = it }
            if (selectorDirty) {
                val long = max(img.width, img.height)
                val work = if (long > Selector.WORK_SIZE) {
                    val s = Selector.WORK_SIZE.toFloat() / long
                    Bitmap.createScaledBitmap(bmp, max(1, (img.width * s).roundToInt()), max(1, (img.height * s).roundToInt()), true)
                } else bmp
                sel.setImage(work)
                selectorDirty = false
            }
            val small = sel.select(listOf(Pair(x / img.width, y / img.height)))
            val full = if (small.width == img.width && small.height == img.height) small else small.resized(img.width, img.height).binarized()
            val current = selection
            if (current == null || current.width != img.width || current.height != img.height) full
            else {
                for (i in current.data.indices) if (full[i] > 0) current.data[i] = 255.toByte()
                current
            }
        }) { m ->
            selection = m
            editor.setSelection(m)
            setStatus(if (m.isEmpty()) "Nothing found there. Try tapping the middle of it." else "Selected. Erase it, Move it, or change the Background. Brush +/− to fix edges.")
        }
    }

    override fun onBrushChanged() {
        val m = selection ?: return
        setStatus(if (m.isEmpty()) "Selection is empty." else "Selection updated.")
    }

    private fun clearSelection() {
        if (busy) return
        selection = null
        editor.setSelection(null)
        setTool(EditorView.Tool.SELECT)
    }

    // ------------------------------------------------------------------ fill model

    /** Makes sure the erase-and-fill model is downloaded and loaded, then runs [then]. */
    private fun withFillModel(then: () -> Unit) {
        if (busy) return
        if (needImage() == null || needSelection() == null) return
        if (inpainter != null) { then(); return }
        if (downloader.isReady()) { loadFillModel(then); return }
        AlertDialog.Builder(this)
            .setTitle("One-time download")
            .setMessage("Erase and Move need the fill AI (about 183 MB). It downloads once and then works offline. Your photos never leave the phone. Wi-Fi recommended.\n\nDownload now?")
            .setPositiveButton("Download") { _, _ -> downloadFillModel(then) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun downloadFillModel(then: () -> Unit) {
        if (busy) return
        setBusy(true, "Downloading fill AI…")
        worker.execute {
            val r = runCatching {
                downloader.download { done, total ->
                    val text = if (total > 0) "Downloading fill AI… ${done * 100 / total}% (${done / 1_048_576} of ${total / 1_048_576} MB)"
                               else "Downloading fill AI… ${done / 1_048_576} MB"
                    runOnUiThread { setStatus(text) }
                }
            }
            runOnUiThread {
                setBusy(false)
                r.onSuccess { loadFillModel(then) }
                    .onFailure { setStatus("Download failed: ${it.message}. Check your connection and try again.") }
            }
        }
    }

    private fun loadFillModel(then: () -> Unit) {
        runBusy("Loading fill AI…", {
            val model = LamaModel(downloader.file, threads = 4)
            lama = model
            Inpainter(model)
        }) { ip ->
            inpainter = ip
            then()
        }
    }

    // ------------------------------------------------------------------ erase

    private fun erase() {
        val img = needImage() ?: return
        val sel = needSelection() ?: return
        val ip = inpainter ?: return
        runBusy("Erasing and filling…", { ip.inpaint(img, sel) }) { r ->
            if (r == null) { setStatus("Nothing to erase."); return@runBusy }
            history.record(img, r.image, r.changed)
            image = r.image
            pushImageToBitmap()
            selection = null
            selectorDirty = true
            editor.setSelection(null)
            editor.refreshImage()
            setTool(EditorView.Tool.SELECT)
            setStatus("Erased. Undo if you don't like it.")
        }
    }

    // ------------------------------------------------------------------ move

    private fun startMove() {
        val img = needImage() ?: return
        val sel = needSelection() ?: return
        val ip = inpainter ?: return
        val bounds = sel.bounds() ?: return
        runBusy("Lifting it out and filling the gap…", {
            val soft = sel.feathered(2).crop(bounds)
            val cutPx = img.crop(bounds)
            val argb = IntArray(cutPx.data.size) { i -> (soft[i] shl 24) or (cutPx.data[i] and 0x00FFFFFF) }
            val cut = Bitmap.createBitmap(argb, cutPx.width, cutPx.height, Bitmap.Config.ARGB_8888)
            val filled = ip.inpaint(img, sel)
            Triple(cut, filled, bounds)
        }) { (cut, filled, b) ->
            if (filled == null) { cut.recycle(); setStatus("Couldn't lift that."); return@runBusy }
            moveBefore = img
            moveBase = filled.image
            moveCut = cut
            moveChanged = filled.changed.union(b)
            image = filled.image
            pushImageToBitmap()
            image = img // the real image stays unchanged until "Place it here"
            editor.setSelection(null)
            editor.startCutout(cut, b.left, b.top)
            editor.tool = EditorView.Tool.MOVE
            moveRow.visibility = View.VISIBLE
            toolScroll.visibility = View.GONE
            brushRow.visibility = View.GONE
            setStatus("Drag it where you want. Pinch with two fingers to resize. Then tap Place it here.")
        }
    }

    private fun applyMove() {
        val before = moveBefore ?: return
        val base = moveBase ?: return
        val cut = moveCut ?: return
        val (x, y, s) = editor.cutoutPlacement() ?: return
        runBusy("Placing…", {
            val out = Bitmap.createBitmap(base.data, base.width, base.height, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(out)
            val m = Matrix().apply { postScale(s, s); postTranslate(x, y) }
            c.drawBitmap(cut, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            val px = IntArray(base.width * base.height)
            out.getPixels(px, 0, base.width, 0, 0, base.width, base.height)
            out.recycle()
            val dest = Box(
                x.toInt() - 2, y.toInt() - 2,
                ceil(x + cut.width * s).toInt() + 2, ceil(y + cut.height * s).toInt() + 2
            ).clampTo(base.width, base.height)
            val changed = (moveChanged ?: Box(0, 0, base.width, base.height)).let { if (dest.isEmpty()) it else it.union(dest) }
            Pair(Pixels(base.width, base.height, px), changed)
        }) { (result, changed) ->
            history.record(before, result, changed)
            image = result
            endMoveSession()
            pushImageToBitmap()
            editor.refreshImage()
            setStatus("Moved. Undo if you don't like it.")
        }
    }

    private fun cancelMove() {
        if (busy) return
        endMoveSession()
        pushImageToBitmap() // back to the untouched image
        editor.refreshImage()
        setStatus("Move cancelled.")
    }

    private fun endMoveSession() {
        editor.endCutout()
        moveCut?.recycle()
        moveBefore = null; moveBase = null; moveCut = null; moveChanged = null
        selection = null
        selectorDirty = true
        editor.setSelection(null)
        moveRow.visibility = View.GONE
        toolScroll.visibility = View.VISIBLE
        setTool(EditorView.Tool.SELECT)
    }

    // ------------------------------------------------------------------ background

    private fun chooseBackground() {
        if (busy) return
        needImage() ?: return
        needSelection() ?: return
        val options = arrayOf("Blur the background", "Black", "White", "Gray", "HEM Lime", "Pick a photo…")
        AlertDialog.Builder(this)
            .setTitle("New background (keeps what you selected)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> applyBackground("Blurring background…") { img -> blurredBackground(img) }
                    1 -> applyBackground("Changing background…") { img -> Pixels.solid(img.width, img.height, 0x000000) }
                    2 -> applyBackground("Changing background…") { img -> Pixels.solid(img.width, img.height, 0xFFFFFF) }
                    3 -> applyBackground("Changing background…") { img -> Pixels.solid(img.width, img.height, 0x808080) }
                    4 -> applyBackground("Changing background…") { img -> Pixels.solid(img.width, img.height, 0xC0FF3E) }
                    5 -> pickBackground.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
            .show()
    }

    private fun blurredBackground(img: Pixels): Pixels {
        val factor = 4
        val small = img.resized(max(1, img.width / factor), max(1, img.height / factor))
        val radius = max(4, max(small.width, small.height) / 90)
        return small.blurred(radius).resized(img.width, img.height)
    }

    private fun applyBackgroundPhoto(uri: Uri) {
        applyBackground("Changing background…") { img ->
            val src = ImageDecoder.createSource(contentResolver, uri)
            val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val long = max(info.size.width, info.size.height)
                val want = max(img.width, img.height)
                if (long > want * 2) {
                    val s = (want * 2f) / long
                    decoder.setTargetSize(max(1, (info.size.width * s).roundToInt()), max(1, (info.size.height * s).roundToInt()))
                }
            }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it }
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            Pixels.coverFit(Pixels(bmp.width, bmp.height, px), img.width, img.height)
        }
    }

    private fun applyBackground(text: String, makeBackground: (Pixels) -> Pixels) {
        val img = needImage() ?: return
        val sel = needSelection() ?: return
        runBusy(text, {
            val bg = makeBackground(img)
            val feather = max(2, max(img.width, img.height) / 900)
            Pixels.composite(bg, img, sel.feathered(feather))
        }) { result ->
            history.record(img, result, Box(0, 0, img.width, img.height))
            image = result
            selectorDirty = true
            pushImageToBitmap()
            editor.refreshImage()
            setStatus("Background changed. Your selection is kept so you can try another one.")
        }
    }

    // ------------------------------------------------------------------ undo / save

    private fun undoRedo(isUndo: Boolean) {
        if (busy || moveBefore != null) return
        val img = needImage() ?: return
        val ok = if (isUndo) history.undo(img) else history.redo(img)
        if (!ok) { setStatus(if (isUndo) "Nothing to undo." else "Nothing to redo."); return }
        selectorDirty = true
        pushImageToBitmap()
        editor.refreshImage()
        setStatus(if (isUndo) "Undone." else "Redone.")
    }

    private fun save() {
        if (moveBefore != null) { setStatus("Place or cancel the move first."); return }
        val bmp = bitmap ?: run { setStatus("Open a photo first."); return }
        runBusy("Saving…", {
            val name = "HPA_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Hybrid Photo Assist")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("could not create the file")
            contentResolver.openOutputStream(uri).use { out ->
                requireNotNull(out) { "could not open the file" }
                check(bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)) { "could not write the photo" }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            name
        }) { name -> setStatus("Saved to Pictures/Hybrid Photo Assist/$name. Your original is untouched.") }
    }

    companion object {
        /** Longest side we edit at. Normal 12 MP shots fit; bigger ones (50 or 200 MP) are scaled down to this so the phone does not run out of memory. */
        const val MAX_SIDE = 4096
    }
}
