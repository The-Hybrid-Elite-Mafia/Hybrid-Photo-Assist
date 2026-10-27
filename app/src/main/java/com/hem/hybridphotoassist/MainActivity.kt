package com.hem.hybridphotoassist

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.hem.hybridphotoassist.CommandParser.BackgroundKind
import com.hem.hybridphotoassist.CommandParser.Command
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
    private lateinit var btnUndo: View
    private lateinit var btnRedo: View
    private lateinit var promptInput: EditText
    private lateinit var refRow: View
    private lateinit var promptRow: View

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false

    // Editing state (touched only on the worker thread or while not busy).
    private var image: Pixels? = null
    private var bitmap: Bitmap? = null
    /** Smaller copy of the photo as opened, for "Hold: Before". Never edited. */
    private var originalPreview: Bitmap? = null
    private var selection: Mask? = null
    private val history = History()
    private var selector: Selector? = null
    private var selectorDirty = true
    private var inpainter: Inpainter? = null
    private var lama: LamaModel? = null
    private val downloader by lazy { ModelDownloader(this) }
    private val references by lazy { ReferenceStore(this) }
    private val prefs by lazy { getSharedPreferences("hpa", MODE_PRIVATE) }
    private lateinit var phrases: PhraseBook

    // Picking something out of a reference photo: the main photo is parked here meanwhile.
    private var refMode = false
    private var parkedImage: Pixels? = null
    private var parkedBitmap: Bitmap? = null

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
    private val pickReferences = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) addReferences(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 draws apps edge to edge: pad the layout by the status/navigation bars and the
        // keyboard ourselves, so nothing hides behind them (the prompt box stays above the keyboard).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
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
            if (!busy && notPicking()) pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)
        btnUndo.setOnClickListener { undoRedo(true) }
        btnRedo.setOnClickListener { undoRedo(false) }
        promptInput = findViewById(R.id.promptInput)
        refRow = findViewById(R.id.refRow)
        promptRow = findViewById(R.id.promptRow)
        phrases = PhraseBook.parse(prefs.getString(PREF_PHRASES, null))
        findViewById<View>(R.id.btnPhrases).setOnClickListener { showPhrases() }
        findViewById<View>(R.id.toolRefs).setOnClickListener { showReferences() }
        findViewById<View>(R.id.btnRefBack).setOnClickListener { leaveReference(copy = false) }
        findViewById<View>(R.id.btnRefCopy).setOnClickListener { leaveReference(copy = true) }
        findViewById<View>(R.id.btnApply).setOnClickListener { applyPrompt() }
        findViewById<View>(R.id.btnPromptHelp).setOnClickListener { showPromptHelp() }
        promptInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { applyPrompt(); true } else false
        }
        setupCompareButton(findViewById(R.id.btnCompare))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (moveBefore != null && !busy) { cancelMove(); return }
                if (refMode && !busy) { leaveReference(copy = false); return }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
        findViewById<View>(R.id.btnSave).setOnClickListener { save() }
        findViewById<View>(R.id.toolClear).setOnClickListener { clearSelection() }
        findViewById<View>(R.id.toolErase).setOnClickListener { withFillModel { erase() } }
        findViewById<View>(R.id.toolMove).setOnClickListener { withFillModel { startMove() } }
        findViewById<View>(R.id.toolBackground).setOnClickListener { chooseBackground() }
        findViewById<View>(R.id.btnMoveApply).setOnClickListener { applyMove() }
        findViewById<View>(R.id.btnMoveCancel).setOnClickListener { cancelMove() }
        actionButtons = listOf<View>(
            findViewById(R.id.btnOpen), findViewById(R.id.btnSave), findViewById(R.id.btnApply), findViewById(R.id.toolClear), findViewById(R.id.toolErase),
            findViewById(R.id.toolMove), findViewById(R.id.toolBackground), findViewById(R.id.toolRefs),
            findViewById(R.id.btnRefBack), findViewById(R.id.btnRefCopy)
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
        updateUndoRedo()
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
        updateUndoRedo()
        if (text != null) setStatus(text)
    }

    /** Undo is usable when there is a step to undo, or a move to call off; Redo when there is a step to redo. */
    private fun updateUndoRedo() {
        if (!::btnUndo.isInitialized) return
        val moving = moveBefore != null
        btnUndo.isEnabled = !busy && !refMode && (history.canUndo || moving)
        btnRedo.isEnabled = !busy && !refMode && !moving && history.canRedo
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
        if (busy) return
        if (refMode) leaveReference(copy = false)
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
            val long = max(decoded.width, decoded.height)
            val preview = if (long > PREVIEW_SIDE) {
                val s = PREVIEW_SIDE.toFloat() / long
                Bitmap.createScaledBitmap(decoded, max(1, (decoded.width * s).roundToInt()), max(1, (decoded.height * s).roundToInt()), true)
            } else decoded.copy(Bitmap.Config.ARGB_8888, false)
            Triple(decoded, Pixels(decoded.width, decoded.height, px), preview)
        }) { (bmp, px, preview) ->
            editor.showCompare(null)
            bitmap?.recycle()
            bitmap = bmp
            originalPreview?.recycle()
            originalPreview = preview
            image = px
            selection = null
            history.clear()
            updateUndoRedo()
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
        if (busy || !notPicking()) return
        if (needImage() == null || needSelection() == null) return
        if (inpainter != null) { then(); return }
        if (downloader.hasFile()) { loadFillModel(then); return }
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
        runBusy("Checking and loading fill AI…", {
            downloader.ensureVerified()
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
            if (!history.record(img, r.image, r.changed)) { setStatus("That didn't change the photo. Try selecting a bit more around it."); return@runBusy }
            updateUndoRedo()
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
            updateUndoRedo()
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
            // moveChanged is null for something pasted from a reference photo (no hole was filled).
            val changed = moveChanged?.let { if (dest.isEmpty()) it else it.union(dest) } ?: dest
            Pair(Pixels(base.width, base.height, px), changed)
        }) { (result, changed) ->
            val recorded = history.record(before, result, changed)
            image = if (recorded) result else before
            endMoveSession()
            pushImageToBitmap()
            editor.refreshImage()
            setStatus(if (recorded) "Placed. Undo if you don't like it." else "Nothing changed (it ended up off the photo or where it started).")
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
        updateUndoRedo()
    }

    // ------------------------------------------------------------------ background

    private fun chooseBackground() {
        if (busy || !notPicking()) return
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
            if (!history.record(img, result, Box(0, 0, img.width, img.height))) { setStatus("That background is the same as before, so nothing changed."); return@runBusy }
            updateUndoRedo()
            image = result
            selectorDirty = true
            pushImageToBitmap()
            editor.refreshImage()
            setStatus("Background changed. Your selection is kept so you can try another one.")
        }
    }

    // ------------------------------------------------------------------ undo / save

    private fun undoRedo(isUndo: Boolean) {
        if (!notPicking()) return
        if (busy) { setStatus("Still working on the last step. Undo/Redo will work when it's done."); return }
        if (moveBefore != null) {
            // A move that isn't placed yet is not in the history: Undo calls it off.
            if (isUndo) { cancelMove(); setStatus("Move called off. The photo is back how it was.") }
            else setStatus("Place it or cancel the move first.")
            return
        }
        val img = needImage() ?: return
        val ok = if (isUndo) history.undo(img) else history.redo(img)
        if (!ok) { setStatus(if (isUndo) "Nothing to undo." else "Nothing to redo."); updateUndoRedo(); return }
        selectorDirty = true
        pushImageToBitmap()
        editor.refreshImage()
        updateUndoRedo()
        val left = if (isUndo) history.undoCount() else history.redoCount()
        setStatus(
            if (isUndo) "Undone. " + (if (left > 0) "$left more step(s) can be undone." else "That was the first edit.")
            else "Redone. " + (if (left > 0) "$left more step(s) can be redone." else "Nothing more to redo.")
        )
    }

    private fun save() {
        if (!notPicking()) return
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
                ?: error("could not create the file (is the phone's storage full?)")
            try {
                contentResolver.openOutputStream(uri).use { out ->
                    requireNotNull(out) { "could not open the file" }
                    check(bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)) { "could not write the photo" }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                // Read the saved file back to be sure it is a real, complete photo of the right size.
                val check = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, check) }
                check(check.outWidth == bmp.width && check.outHeight == bmp.height) {
                    "the saved file didn't read back correctly (${check.outWidth} × ${check.outHeight})"
                }
            } catch (e: Exception) {
                runCatching { contentResolver.delete(uri, null, null) }
                throw e
            }
            "$name (${bmp.width} × ${bmp.height})"
        }) { name -> setStatus("Saved to Pictures/Hybrid Photo Assist/$name. Your original is untouched.") }
    }

    // ------------------------------------------------------------------ before / after

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCompareButton(b: View) {
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val orig = originalPreview
                    if (orig == null || moveBefore != null || busy || refMode) {
                        setStatus(if (orig == null) "Open a photo first." else "Finish what you're doing first.")
                    } else {
                        editor.showCompare(orig)
                        setStatus("Showing the ORIGINAL photo. Let go to see your edits.")
                    }
                }
                MotionEvent.ACTION_UP -> { editor.showCompare(null); v.performClick() }
                MotionEvent.ACTION_CANCEL -> editor.showCompare(null)
            }
            true
        }
        b.setOnClickListener { if (originalPreview != null && moveBefore == null && !busy) setStatus("Back to your edited photo.") }
    }

    // ------------------------------------------------------------------ typed instructions

    private fun hideKeyboard() {
        promptInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(promptInput.windowToken, 0)
    }

    private fun showPromptHelp() {
        AlertDialog.Builder(this)
            .setTitle("What you can type")
            .setMessage(
                "First tap the thing you mean with Select (or paint it with Brush +). Then type, for example:\n\n" +
                "• Remove the selected object and fill the gap\n" +
                "• Move it left   /   move it up a little   /   move it way right\n" +
                "• Make the selected area brighter   /   a little darker\n" +
                "• Make the whole photo brighter\n" +
                "• Blur the background   /   make the background white\n" +
                "• Undo   •   Redo   •   Cancel   •   Save\n\n" +
                "This box understands simple keywords. It is not a chat AI.\n" +
                "Face changes (smile, eyes, expression) are NOT supported: the phone has no face-editing AI, " +
                "so the app will tell you instead of pretending."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun applyPrompt() {
        val text = promptInput.text.toString()
        if (!notPicking()) return
        val cmd = phrases.match(text) ?: CommandParser.parse(text)
        if (cmd != Command.Empty) hideKeyboard()
        if (busy && cmd !is Command.Unsupported && cmd != Command.Empty) {
            setStatus("Still working on the last step. Try again when it's done."); return
        }
        if (moveBefore != null && cmd !in listOf(Command.Undo, Command.Cancel, Command.Empty) && cmd !is Command.Unsupported) {
            setStatus("Place it or cancel the move first (buttons above)."); return
        }
        when (cmd) {
            Command.Empty -> setStatus("Type what you want to change, then tap Apply. Tap ? for examples.")
            is Command.Unsupported -> setStatus(cmd.reason)
            Command.Undo -> undoRedo(true)
            Command.Redo -> undoRedo(false)
            Command.Cancel -> when {
                moveBefore != null -> cancelMove()
                selection != null -> { clearSelection(); setStatus("Selection cleared.") }
                else -> setStatus("Nothing to cancel.")
            }
            Command.ClearSelection -> { clearSelection(); setStatus("Selection cleared.") }
            Command.Save -> save()
            Command.Erase -> {
                if (needImage() == null) return
                if (selection == null || selection!!.isEmpty()) {
                    setStatus("Tap the thing you want removed first (Select), then tap Apply again. I can't find objects from words."); return
                }
                withFillModel { erase() }
            }
            is Command.Move -> {
                if (needImage() == null) return
                if (selection == null || selection!!.isEmpty()) {
                    setStatus("Tap the thing you want to move first (Select), then tap Apply again."); return
                }
                withFillModel { moveBy(cmd.dx, cmd.dy, cmd.fraction) }
            }
            is Command.Brightness -> adjustBrightness(cmd.percent, cmd.wholePhoto)
            is Command.Background -> {
                if (needImage() == null) return
                if (selection == null || selection!!.isEmpty()) {
                    setStatus("Select the person or thing to KEEP first, then tap Apply again."); return
                }
                when (cmd.kind) {
                    BackgroundKind.BLUR -> applyBackground("Blurring background…") { img -> blurredBackground(img) }
                    BackgroundKind.BLACK -> applyBackground("Changing background…") { img -> Pixels.solid(img.width, img.height, 0x000000) }
                    BackgroundKind.WHITE -> applyBackground("Changing background…") { img -> Pixels.solid(img.width, img.height, 0xFFFFFF) }
                    BackgroundKind.GRAY -> applyBackground("Changing background…") { img -> Pixels.solid(img.width, img.height, 0x808080) }
                    BackgroundKind.LIME -> applyBackground("Changing background…") { img -> Pixels.solid(img.width, img.height, 0xC0FF3E) }
                }
            }
        }
    }

    /** Brightens/darkens the selection (or the whole photo). */
    private fun adjustBrightness(percent: Int, wholePhoto: Boolean) {
        if (!notPicking()) return
        val img = needImage() ?: return
        val sel = selection?.takeIf { !it.isEmpty() }
        if (!wholePhoto && sel == null) {
            setStatus("Select an area first, or type \"make the whole photo ${if (percent > 0) "brighter" else "darker"}\"."); return
        }
        val word = if (percent > 0) "Brightening" else "Darkening"
        runBusy("$word…", {
            val mask = if (wholePhoto) null else sel!!.feathered(max(1, max(img.width, img.height) / 900))
            val out = img.brightness(percent, mask)
            val box = if (wholePhoto) Box(0, 0, img.width, img.height) else (mask!!.bounds() ?: Box(0, 0, 0, 0))
            Pair(out, box)
        }) { (out, box) ->
            if (!history.record(img, out, box)) { setStatus("That didn't change anything (already as bright/dark as it goes?)."); return@runBusy }
            image = out
            updateUndoRedo()
            selectorDirty = true
            pushImageToBitmap()
            editor.refreshImage()
            setStatus("${if (percent > 0) "Brighter" else "Darker"} (${kotlin.math.abs(percent)}%). Undo if you don't like it.")
        }
    }

    /** Typed move: lifts the selection, fills the gap and places it [fraction] of the photo away. */
    private fun moveBy(dx: Int, dy: Int, fraction: Float) {
        val img = needImage() ?: return
        val sel = needSelection() ?: return
        val ip = inpainter ?: return
        val b = sel.bounds() ?: return
        val stepX = (dx * fraction * img.width).roundToInt()
        val stepY = (dy * fraction * img.height).roundToInt()
        val dest = Box(b.left + stepX, b.top + stepY, b.right + stepX, b.bottom + stepY)
        if (dest.clampTo(img.width, img.height).isEmpty()) { setStatus("That would push it right off the photo. Try a smaller move."); return }
        runBusy("Moving it and filling the gap…", {
            val soft = sel.feathered(2).crop(b)
            val cut = img.crop(b)
            val filled = ip.inpaint(img, sel) ?: return@runBusy null
            val out = Pixels.overlay(filled.image, cut, soft, dest.left, dest.top)
            Pair(out, filled.changed.union(dest.clampTo(img.width, img.height)))
        }) { r ->
            if (r == null) { setStatus("Couldn't lift that. Try selecting it again."); return@runBusy }
            val (out, changed) = r
            if (!history.record(img, out, changed)) { setStatus("That didn't change the photo."); return@runBusy }
            image = out
            updateUndoRedo()
            selection = null
            selectorDirty = true
            editor.setSelection(null)
            pushImageToBitmap()
            editor.refreshImage()
            setTool(EditorView.Tool.SELECT)
            val dir = listOfNotNull(if (dy < 0) "up" else if (dy > 0) "down" else null, if (dx < 0) "left" else if (dx > 0) "right" else null).joinToString(" and ")
            setStatus("Moved $dir. Undo if you don't like it. For exact placing, use the Move button and drag.")
        }
    }

    // ------------------------------------------------------------------ my phrases (Edit)

    private fun savePhrases() { prefs.edit().putString(PREF_PHRASES, phrases.serialize()).apply() }

    private fun showPhrases() {
        if (busy) return
        val list = phrases.entries
        val items = list.map { "${it.phrase}   →   ${it.action.label}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("My phrases (tap one)")
            .setItems(items) { _, i -> phraseOptions(i) }
            .setPositiveButton("Add a phrase") { _, _ -> editPhrase(null) }
            .setNeutralButton("Reset list") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("Put the list back to the starting phrases? Your own phrases will be removed.")
                    .setPositiveButton("Reset") { _, _ -> phrases.resetToDefaults(); savePhrases(); setStatus("Phrase list reset.") }
                    .setNegativeButton("Keep mine", null).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun phraseOptions(i: Int) {
        val e = phrases.entries.getOrNull(i) ?: return
        AlertDialog.Builder(this)
            .setTitle("\"${e.phrase}\"")
            .setMessage("Does: ${e.action.label}")
            .setPositiveButton("Use it") { _, _ -> promptInput.setText(e.phrase); promptInput.setSelection(e.phrase.length); applyPrompt() }
            .setNeutralButton("Delete") { _, _ -> phrases.remove(i); savePhrases(); setStatus("Deleted \"${e.phrase}\".") }
            .setNegativeButton("Edit") { _, _ -> editPhrase(i) }
            .show()
    }

    /** Add (index null) or change a phrase: what you type, and which real action it runs. */
    private fun editPhrase(index: Int?) {
        val old = index?.let { phrases.entries.getOrNull(it) }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val input = EditText(this).apply {
            hint = "What you'll type, e.g. scoot it left"
            setText(old?.phrase ?: promptInput.text.toString().trim())
            setSingleLine(true)
        }
        val actions = PhraseBook.Action.values()
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, actions.map { it.label })
            setSelection(actions.indexOf(old?.action ?: PhraseBook.Action.MOVE_LEFT))
        }
        box.addView(input)
        box.addView(TextView(this).apply { text = "What it does:"; setPadding(0, pad / 2, 0, 0) })
        box.addView(spinner)
        val dlg = AlertDialog.Builder(this)
            .setTitle(if (old == null) "Add a phrase" else "Edit phrase")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val err = phrases.put(input.text.toString(), actions[spinner.selectedItemPosition], index)
                if (err != null) input.error = err
                else { savePhrases(); dlg.dismiss(); setStatus("Saved. Type \"${input.text.toString().trim()}\" and tap Apply.") }
            }
        }
        dlg.show()
    }

    // ------------------------------------------------------------------ reference photos

    /** False (with a message) while picking something out of a reference photo. */
    private fun notPicking(): Boolean {
        if (refMode) setStatus("You're on a reference photo. Tap Copy into my photo, or Back to my photo, first.")
        return !refMode
    }

    private fun showReferences() {
        if (busy || !notPicking()) return
        if (moveBefore != null) { setStatus("Place it or cancel the move first."); return }
        val files = references.list()
        val names = files.mapIndexed { i, f ->
            "Reference ${files.size - i}  (" + SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(f.lastModified())) + ")"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (files.isEmpty()) "No reference photos yet" else "Reference photos (tap one)")
            .setItems(names) { _, i -> referenceOptions(files[i], names[i]) }
            .setPositiveButton("Add reference photos") { _, _ ->
                pickReferences.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun addReferences(uris: List<Uri>) {
        runBusy("Adding ${uris.size} reference photo(s)…", {
            var ok = 0; var lastError: String? = null
            for (u in uris) runCatching { references.add(u) }.onSuccess { ok++ }.onFailure { lastError = it.message }
            Pair(ok, lastError)
        }) { (ok, err) ->
            setStatus(
                "Added $ok reference photo(s). They stay in the app (private, never uploaded). Tap Reference to use one." +
                (err?.let { " Some couldn't be added: $it." } ?: "")
            )
        }
    }

    private fun referenceOptions(f: java.io.File, name: String) {
        val opts = arrayOf(
            "Match the look (colors + brightness)",
            "Match the scene / vibe (also contrast + shadows)",
            "Copy something from it into my photo",
            "Delete this reference"
        )
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> chooseStrength(f, LookMatch.Mode.LOOK)
                    1 -> chooseStrength(f, LookMatch.Mode.VIBE)
                    2 -> enterReference(f)
                    3 -> AlertDialog.Builder(this).setMessage("Delete this reference photo from the app? (Your original in the gallery is not touched.)")
                        .setPositiveButton("Delete") { _, _ -> references.delete(f); setStatus("Reference deleted.") }
                        .setNegativeButton("Keep", null).show()
                }
            }
            .show()
    }

    private fun chooseStrength(f: java.io.File, mode: LookMatch.Mode) {
        if (needImage() == null) return
        val where = if (selection?.isEmpty() == false) "your SELECTION" else "the WHOLE photo"
        val labels = arrayOf("Light (40%)", "Medium (70%)", "Full (100%)")
        val values = floatArrayOf(0.4f, 0.7f, 1f)
        AlertDialog.Builder(this)
            .setTitle("How strong? (changes $where)")
            .setItems(labels) { _, i -> matchReference(f, mode, values[i]) }
            .show()
    }

    private fun matchReference(f: java.io.File, mode: LookMatch.Mode, strength: Float) {
        val img = needImage() ?: return
        val sel = selection?.takeIf { !it.isEmpty() }
        runBusy(if (mode == LookMatch.Mode.VIBE) "Matching the vibe…" else "Matching the look…", {
            val refBmp = references.load(f, LookMatch.SAMPLE_SIDE)
            val rw = refBmp.width; val rh = refBmp.height
            val px = IntArray(rw * rh)
            refBmp.getPixels(px, 0, rw, 0, 0, rw, rh)
            refBmp.recycle()
            val ref = Pixels(rw, rh, px)
            val mask = sel?.feathered(max(1, max(img.width, img.height) / 900))
            val out = LookMatch.plan(img, mask, ref, mode).apply(img, strength, mask)
            val box = if (mask == null) Box(0, 0, img.width, img.height) else (mask.bounds() ?: Box(0, 0, 0, 0))
            Pair(out, box)
        }) { (out, box) ->
            if (!history.record(img, out, box)) { setStatus("This photo already looks like that reference, so nothing changed."); return@runBusy }
            image = out
            updateUndoRedo()
            selectorDirty = true
            pushImageToBitmap()
            editor.refreshImage()
            setStatus("${if (mode == LookMatch.Mode.VIBE) "Vibe" else "Look"} matched (${(strength * 100).roundToInt()}%). Hold Before to compare, Undo if you don't like it.")
        }
    }

    /** Shows a reference photo so something can be selected in it and copied over. */
    private fun enterReference(f: java.io.File) {
        val img = needImage() ?: return
        val mainBmp = bitmap ?: return
        runBusy("Opening the reference…", {
            val bmp = references.load(f)
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            Pair(bmp, Pixels(bmp.width, bmp.height, px))
        }) { (bmp, px) ->
            parkedImage = img; parkedBitmap = mainBmp
            image = px; bitmap = bmp
            selection = null; selectorDirty = true
            refMode = true
            editor.setSelection(null)
            editor.setImage(bmp, resetView = true)
            refRow.visibility = View.VISIBLE
            promptRow.visibility = View.GONE
            setTool(EditorView.Tool.SELECT)
            updateUndoRedo()
            setStatus("REFERENCE photo: tap what you want to copy (Brush +/− to fix edges), then tap Copy into my photo.")
        }
    }

    /** Back to the main photo; with [copy], the selection from the reference comes along to be placed. */
    private fun leaveReference(copy: Boolean) {
        if (busy || !refMode) return
        val refImg = image ?: return
        val sel = selection?.takeIf { !it.isEmpty() }
        if (copy && sel == null) { setStatus("Select something on the reference first (tap it)."); return }
        val cut: Bitmap? = if (copy) {
            val b = sel!!.bounds()!!
            val soft = sel.feathered(2).crop(b)
            val cutPx = refImg.crop(b)
            val argb = IntArray(cutPx.data.size) { i -> (soft[i] shl 24) or (cutPx.data[i] and 0x00FFFFFF) }
            Bitmap.createBitmap(argb, cutPx.width, cutPx.height, Bitmap.Config.ARGB_8888)
        } else null
        // Restore the main photo exactly as it was.
        val refBmp = bitmap
        image = parkedImage; bitmap = parkedBitmap
        parkedImage = null; parkedBitmap = null
        refMode = false
        selection = null; selectorDirty = true
        editor.setSelection(null)
        editor.setImage(bitmap, resetView = true)
        refBmp?.recycle()
        refRow.visibility = View.GONE
        promptRow.visibility = View.VISIBLE
        setTool(EditorView.Tool.SELECT)
        updateUndoRedo()
        if (cut == null) { setStatus("Back on your photo."); return }
        val img = image ?: return
        // Start it centred and no bigger than about 60% of the photo; pinch to resize.
        val scale = minOf(1f, 0.6f * minOf(img.width.toFloat() / cut.width, img.height.toFloat() / cut.height))
        val x = ((img.width - cut.width * scale) / 2f).roundToInt()
        val y = ((img.height - cut.height * scale) / 2f).roundToInt()
        moveBefore = img; moveBase = img; moveCut = cut; moveChanged = null
        editor.startCutout(cut, x, y, scale)
        editor.tool = EditorView.Tool.MOVE
        moveRow.visibility = View.VISIBLE
        toolScroll.visibility = View.GONE
        brushRow.visibility = View.GONE
        updateUndoRedo()
        setStatus("Drag it where you want. Pinch with two fingers to resize. Then tap Place it here.")
    }

    companion object {
        const val PREF_PHRASES = "phrases"
        /** Long side of the "Hold: Before" copy of the original. */
        const val PREVIEW_SIDE = 2048
        /** Longest side we edit at. Normal 12 MP shots fit; bigger ones (50 or 200 MP) are scaled down to this so the phone does not run out of memory. */
        const val MAX_SIDE = 4096
    }
}
