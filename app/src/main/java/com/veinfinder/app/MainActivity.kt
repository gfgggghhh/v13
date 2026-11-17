package com.veinfinder.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.veinfinder.app.camera.CameraSourceFactory
import com.veinfinder.app.camera.DefaultCameraSourceFactory
import com.veinfinder.app.camera.Res
import com.veinfinder.app.processing.DisplayMode
import com.veinfinder.app.processing.EnhanceLevel
import com.veinfinder.app.processing.FramePipeline
import com.veinfinder.app.processing.PerfMonitor
import com.veinfinder.app.processing.TemporalStabilizer
import com.veinfinder.app.processing.ViewMode
import com.veinfinder.app.settings.AppSettings
import com.veinfinder.app.settings.BitmapRing
import com.veinfinder.app.settings.ImageSaver
import com.veinfinder.app.settings.SettingsDialog
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var statusText: TextView
    private lateinit var perfText: TextView
    private lateinit var thumb: ImageView
    private lateinit var spinner: Spinner
    private lateinit var btnStart: TextView
    private lateinit var btnDisplay: TextView
    private lateinit var btnGreen: TextView
    private lateinit var btnNegative: TextView

    private lateinit var prefs: SharedPreferences
    private lateinit var factory: CameraSourceFactory
    private val pipeline = FramePipeline()
    private val compareProcessor = VeinProcessor()            // separate instance: the pipeline's one belongs to the camera thread
    private val perf = PerfMonitor()
    private val bitmaps = BitmapRing(3)
    private val io = Executors.newSingleThreadExecutor()

    private var source: FrameSource? = null
    private var cameras: List<CameraEntry> = emptyList()
    private val busy = AtomicBoolean(false)

    @Volatile private var running = false
    @Volatile private var settings = AppSettings()
    @Volatile private var runningResolution = ""
    @Volatile private var sizeInfo = ""                  // requested -> received -> processed frame size, shown in the status line
    @Volatile private var lastInfo: com.veinfinder.app.processing.FrameInfo? = null
    @Volatile private var lastShown: Bitmap? = null
    private val rawLock = Any()
    private var latestRaw: Mat? = null                       // untouched copy of the newest frame (what Save / Compare keep)
    private var lastSavedUri: Uri? = null
    private var lastPerfUi = 0L

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else toast("Camera permission is required")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // was an invalid manifest attribute
        OpenCVLoader.initLocal()

        imageView = findViewById(R.id.imageView)
        statusText = findViewById(R.id.statusText)
        perfText = findViewById(R.id.perfText)
        thumb = findViewById(R.id.thumb)
        spinner = findViewById(R.id.cameraSpinner)
        btnStart = findViewById(R.id.btnStart)
        btnDisplay = findViewById(R.id.btnDisplay)
        btnGreen = findViewById(R.id.btnGreen)
        btnNegative = findViewById(R.id.btnNegative)

        prefs = getSharedPreferences("vein", Context.MODE_PRIVATE)         // same file as before: old k / b values are kept
        settings = AppSettings.load(prefs)
        factory = DefaultCameraSourceFactory(this)
        refreshButtons()
        refreshCameraList()

        btnStart.setOnClickListener {
            if (running) stopCamera() else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                    startCamera() else permission.launch(Manifest.permission.CAMERA)
            }
        }
        btnDisplay.setOnClickListener {
            val all = DisplayMode.values()
            update(settings.copy(display = all[(settings.display.ordinal + 1) % all.size]))
        }
        btnGreen.setOnClickListener {
            update(settings.copy(view = if (settings.view == ViewMode.GREEN) ViewMode.GRAY else ViewMode.GREEN))
        }
        btnNegative.setOnClickListener {
            update(settings.copy(view = if (settings.view == ViewMode.NEGATIVE) ViewMode.GRAY else ViewMode.NEGATIVE))
        }
        findViewById<View>(R.id.btnSave).setOnClickListener { saveImage() }
        findViewById<View>(R.id.btnCompare).setOnClickListener { saveComparison() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
        perfText.setOnClickListener { showCameraReport() }                 // tap the status line for the camera capability report
        thumb.setOnClickListener {
            lastSavedUri?.let {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(it, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (running) { stopCamera(); startCamera() }   // switching camera while running
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ---------------------------------------------------------------- settings plumbing
    /** Single entry point for every setting change: stores it, applies it live, refreshes the buttons. */
    private fun update(n: AppSettings, persist: Boolean = true) {
        val old = settings
        settings = n.sanitized()
        if (persist) AppSettings.save(prefs, settings)
        refreshButtons()
        if (running) {
            if (old.resolution != settings.resolution) { stopCamera(); startCamera() }
            else if (old.camera != settings.camera) source?.applyControls(settings.camera)
        }
    }

    private fun refreshButtons() {
        btnDisplay.text = when (settings.display) {
            DisplayMode.ORIGINAL -> "Original"; DisplayMode.ENHANCED -> "Enhanced"; DisplayMode.STABILIZED -> "Stabilized"
        }
        btnGreen.text = if (settings.view == ViewMode.GREEN) "Gray" else "Green"
        btnNegative.text = if (settings.view == ViewMode.NEGATIVE) "Negative" else "Positive"
    }

    private fun currentEntry(): CameraEntry? = cameras.getOrNull(spinner.selectedItemPosition)

    private fun showSettings() {
        val entry = currentEntry()
        SettingsDialog(
            this, settings, entry?.let { factory.capabilities(it) }, entry?.usb == true, source?.autoValues(),
            onChange = { update(it, persist = false) },
            onCommit = { update(it) }
        ).show()
    }

    private fun showCameraReport() {
        val c = currentEntry()?.let { factory.capabilities(it) }
        AlertDialog.Builder(this).setTitle("Camera report")
            .setMessage(c?.describe() ?: "No capability information for this camera.")
            .setPositiveButton("OK", null).show()
    }

    // ---------------------------------------------------------------- cameras
    private fun refreshCameraList() {
        cameras = factory.enumerate()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameras.map { it.label })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        refreshCameraList()           // USB camera plugged in
    }

    override fun onResume() { super.onResume(); if (!running) refreshCameraList() }

    private fun startCamera() {
        val entry = currentEntry()
        if (entry == null) { toast("No camera found"); return }
        val src = factory.create(entry, this) ?: run { toast("Camera not found"); return }
        source = src
        pipeline.reset(); perf.reset(); bitmaps.clear()
        running = true
        runningResolution = (Res.parse(settings.resolution) ?: Res(640, 480)).toString()   // requested size
        sizeInfo = ""
        btnStart.text = "Stop"
        statusText.visibility = View.GONE
        perfText.visibility = View.VISIBLE
        src.applyControls(settings.camera)
        src.start(CaptureConfig(Res.parse(settings.resolution) ?: Res(640, 480)),
            { frame -> onFrame(frame) }, { msg -> runOnUiThread { toast(msg); stopCamera() } })
    }

    private fun stopCamera() {
        running = false
        source?.stop(); source = null
        btnStart.text = "Start"
        statusText.visibility = View.VISIBLE
        perfText.visibility = View.GONE
        pipeline.reset()
        synchronized(rawLock) { latestRaw?.release(); latestRaw = null }
    }

    override fun onPause() { super.onPause(); if (running) stopCamera() }

    override fun onDestroy() {
        super.onDestroy()
        if (running) stopCamera()
        pipeline.release()
        io.shutdown()
    }

    // ---------------------------------------------------------------- frames
    private fun onFrame(frame: Mat) {
        if (!running || !busy.compareAndSet(false, true)) { frame.release(); if (running) perf.frameDropped(); return }
        var src: Mat? = frame                                    // released in finally unless handed over to latestRaw
        var shown: Mat? = null
        try {
            val gotW = maxOf(frame.width(), frame.height()); val gotH = minOf(frame.width(), frame.height())
            if (frame.width() > MAX_PROC_WIDTH) {                // averaging down also lowers noise
                val s = Mat()
                try {
                    val ratio = MAX_PROC_WIDTH.toDouble() / frame.width()
                    Imgproc.resize(frame, s, Size(MAX_PROC_WIDTH.toDouble(), frame.height() * ratio), 0.0, 0.0, Imgproc.INTER_AREA)
                } catch (t: Throwable) { s.release(); throw t }
                frame.release(); src = s
            }
            val cur = src!!
            val st = settings
            val out = pipeline.process(cur, st.display, st.toProcessing(), st.view)
            shown = out.shown
            val bmp = bitmaps.next(out.shown.width(), out.shown.height())
            Utils.matToBitmap(out.shown, bmp)
            // the processed input becomes the "untouched original" for Save / Compare (no per-frame clone: process() never writes to it)
            synchronized(rawLock) {
                latestRaw?.release()
                if (running) { latestRaw = cur; src = null } else latestRaw = null
            }
            lastShown = bmp; lastInfo = out.info
            sizeInfo = "$runningResolution→${gotW}×${gotH}→${cur.width().coerceAtLeast(cur.height())}×${cur.width().coerceAtMost(cur.height())}"
            perf.frameProcessed(out.info.processMs)
            runOnUiThread { imageView.setImageBitmap(bmp); updatePerf() }
        } catch (e: Exception) {
            runOnUiThread { toast(e.message ?: "Processing error") }
        } finally {
            shown?.release(); src?.release()
            busy.set(false)
        }
    }

    private fun updatePerf() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPerfUi < 500) return
        lastPerfUi = now
        val i = lastInfo
        val stab = when (i?.stabilizer) {
            TemporalStabilizer.Status.MERGED -> "  stab ×%.1f".format(i!!.effectiveFrames)
            TemporalStabilizer.Status.REJECTED -> "  MOTION: frame not merged"
            TemporalStabilizer.Status.INIT -> "  stab init"
            null -> ""
        }
        val nativeMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        perfText.text = "%.0f fps  %.0f ms  drop %d  native %d MB%s\n%s".format(perf.fps(), perf.procMs(), perf.dropped(), nativeMb, stab, sizeInfo)
    }

    // ---------------------------------------------------------------- save
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun rawBitmap(): Bitmap? {
        val m = synchronized(rawLock) { latestRaw?.clone() } ?: return null
        val b = Bitmap.createBitmap(m.width(), m.height(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(m, b); m.release()
        return b
    }

    /** Saves the untouched original (lossless PNG) and, unless the display mode is Original, the processed image. */
    private fun saveImage() {
        val shown = lastShown?.copy(Bitmap.Config.ARGB_8888, false)
        if (!running || shown == null) { AlertDialog.Builder(this).setTitle("Kindly reminder")
            .setMessage("Please open the camera").setPositiveButton("OK", null).show(); return }
        val raw = rawBitmap()
        val mode = settings.display
        val ts = stamp()
        io.execute {
            try {
                raw?.let { ImageSaver.save(this, it, null, "${ts}_original", png = true) }
                var uri: Uri? = null
                if (mode != DisplayMode.ORIGINAL) uri = ImageSaver.save(this, shown, null, "${ts}_${mode.name.lowercase()}", png = false)
                runOnUiThread {
                    lastSavedUri = uri ?: lastSavedUri
                    thumb.setImageBitmap(shown)
                    toast("Saved: Pictures/Veinfinder/${ts}_original.png" + if (uri != null) " + ${mode.name.lowercase()}" else "")
                }
            } catch (e: Exception) { runOnUiThread { toast("Save failed: ${e.message}") } }
        }
    }

    /**
     * Saves the SAME frame processed with every level (plus the current stabilized picture when that mode is on)
     * into one folder, so settings can be compared fairly under identical shooting conditions.
     */
    private fun saveComparison() {
        if (!running) { toast("Please start the camera first"); return }
        val raw = synchronized(rawLock) { latestRaw?.clone() } ?: run { toast("No frame yet"); return }
        val st = settings
        val stabShown = if (st.display == DisplayMode.STABILIZED) lastShown?.copy(Bitmap.Config.ARGB_8888, false) else null
        val dir = "compare_${stamp()}"
        toast("Saving comparison set…")
        io.execute {
            try {
                val ob = Bitmap.createBitmap(raw.width(), raw.height(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(raw, ob)
                ImageSaver.save(this, ob, dir, "00_original", png = true)
                var n = 1
                for (lvl in EnhanceLevel.values()) {
                    val out = compareProcessor.enhanceOnce(raw, st.toProcessing().copy(level = lvl), st.view)
                    val b = Bitmap.createBitmap(out.width(), out.height(), Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(out, b); out.release()
                    ImageSaver.save(this, b, dir, "%02d_%s".format(n++, lvl.name.lowercase()), png = false)
                }
                stabShown?.let { ImageSaver.save(this, it, dir, "%02d_stabilized_current".format(n), png = false) }
                runOnUiThread { toast("Saved: Pictures/Veinfinder/$dir") }
            } catch (e: Exception) { runOnUiThread { toast("Compare failed: ${e.message}") } }
            finally { raw.release() }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object { private const val MAX_PROC_WIDTH = 960 }
}
