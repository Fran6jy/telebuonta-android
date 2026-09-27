package uk.fran6jy.telebuonta

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.provider.MediaStore
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import org.json.JSONArray
import org.json.JSONObject
import uk.fran6jy.telebuonta.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val PREFS = "telebuonta"
private const val WPM = 140.0                  // unhurried speaking pace
private const val DP_PER_SPEED_UNIT = 20.0     // dp scrolled per second at speed 1

data class Script(val name: String, val body: String)

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    // ---- prompter state ----
    private var speed = 5
    private var fontSp = 30
    private var opacity = 62
    private var countdownSecs = 3
    private var targetSecs = 75
    private var quality = 1080
    private var frontCamera = true

    private var scrollY = 0f
    private var rolling = false
    private var rollStartedAt = 0L
    private var elapsedAcc = 0.0

    // ---- scripts ----
    private var scripts = mutableListOf<Script>()
    private var currentScript = 0

    // ---- camera ----
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var isRecording = false

    // ---- mic meter (standby only; CameraX owns the mic while recording) ----
    private var meterThread: Thread? = null
    @Volatile private var meterRunning = false

    private val choreographer by lazy { Choreographer.getInstance() }
    private val frameCallback = object : Choreographer.FrameCallback {
        private var last = 0L
        override fun doFrame(frameTimeNanos: Long) {
            if (!rolling) { last = 0L; return }
            val dt = if (last == 0L) 0f else (frameTimeNanos - last) / 1_000_000_000f
            last = frameTimeNanos
            advance(dt)
            choreographer.postFrameCallback(this)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) {
            startCamera()
            startMeter()
        } else {
            toast("Camera permission is needed to use the prompter")
        }
    }

    // ================================================================= lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        applyInsets()
        loadPrefs()
        loadScripts()
        wireControls()
        applyScript()

        if (hasPermissions()) {
            startCamera()
            startMeter()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    override fun onDestroy() {
        stopMeter()
        recording?.stop()
        super.onDestroy()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.hud.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = bars.top + dp(8) }
            b.meterBox.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = bars.top + dp(10) }
            b.panel.updateLayoutParams<FrameLayout.LayoutParams> {
                if (topMargin < bars.top) topMargin = bars.top + dp(8)
            }
            b.bar.updatePadding(bottom = dp(14) + bars.bottom)
            insets
        }
    }

    // ================================================================= camera

    private fun hasPermissions() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(b.preview.surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        when (quality) {
                            2160 -> Quality.UHD
                            720 -> Quality.HD
                            else -> Quality.FHD
                        },
                        androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                    )
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val selector =
                if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, videoCapture)
            } catch (e: Exception) {
                toast("Couldn't open the camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val capture = videoCapture ?: run { toast("Camera isn't ready yet"); return }
        stopMeter()   // release the mic so CameraX can take it

        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.UK).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "telebuonta-$stamp.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Telebuonta")
        }
        val output = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        recording = capture.output
            .prepareRecording(this, output)
            .apply {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) withAudioEnabled()
            }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        b.btnRecord.setBackgroundResource(R.drawable.bg_record_stop)
                        b.recDot.visibility = View.VISIBLE
                        b.btnPause.visibility = View.VISIBLE
                        resetRoll(); startRoll()
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        b.btnRecord.setBackgroundResource(R.drawable.bg_record)
                        b.recDot.visibility = View.INVISIBLE
                        b.btnPause.visibility = View.GONE
                        stopRoll()
                        if (event.hasError()) {
                            toast("Recording failed (${event.error})")
                        } else {
                            toast("Saved to Movies/Telebuonta")
                        }
                        startMeter()
                    }
                    else -> Unit
                }
            }
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
    }

    // ================================================================= mic meter

    @SuppressLint("MissingPermission")
    private fun startMeter() {
        if (meterRunning) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        meterRunning = true
        meterThread = Thread {
            var recorder: AudioRecord? = null
            try {
                val rate = 44100
                val minBuf = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) return@Thread
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) return@Thread
                recorder.startRecording()
                val buf = ShortArray(minBuf / 2)
                while (meterRunning) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read <= 0) continue
                    var peak = 0
                    for (i in 0 until read) {
                        val v = abs(buf[i].toInt())
                        if (v > peak) peak = v
                    }
                    val pct = min(1f, peak / 12000f)
                    runOnUiThread {
                        b.meterFill.updateLayoutParams<FrameLayout.LayoutParams> {
                            height = (b.meterBox.height * pct).toInt()
                        }
                        b.meterFill.setBackgroundColor(
                            ContextCompat.getColor(this, if (pct > 0.88f) R.color.warn else R.color.ok)
                        )
                    }
                    Thread.sleep(60)
                }
            } catch (_: Exception) {
            } finally {
                try { recorder?.stop() } catch (_: Exception) {}
                recorder?.release()
            }
        }.also { it.start() }
    }

    private fun stopMeter() {
        meterRunning = false
        meterThread?.join(300)
        meterThread = null
        runOnUiThread {
            b.meterFill.updateLayoutParams<FrameLayout.LayoutParams> { height = 0 }
        }
    }

    // ================================================================= scrolling

    private fun pxPerSecond() = (DP_PER_SPEED_UNIT * speed * resources.displayMetrics.density).toFloat()

    private fun totalDistance(): Float =
        b.script.height + b.viewport.height * 0.5f

    private fun advance(dt: Float) {
        val secs = elapsedAcc + (System.nanoTime() - rollStartedAt) / 1_000_000_000.0
        b.clock.text = "${fmt(secs)}  / ${fmt(estimatedSeconds())}"
        b.clock.setTextColor(
            ContextCompat.getColor(
                this,
                if (secs > estimatedSeconds() * 1.15) R.color.warn else R.color.text
            )
        )

        scrollY += pxPerSecond() * dt
        b.script.translationY = b.viewport.height * 0.12f - scrollY

        val pct = min(1f, scrollY / max(1f, totalDistance()))
        b.progress.updateLayoutParams<FrameLayout.LayoutParams> {
            width = (b.panel.width * pct).toInt()
        }

        if (scrollY > totalDistance()) stopRoll()
    }

    private fun startRoll() {
        if (rolling) return
        rolling = true
        rollStartedAt = System.nanoTime()
        b.btnScroll.setImageResource(R.drawable.ic_pause)
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopRoll() {
        if (rolling) elapsedAcc += (System.nanoTime() - rollStartedAt) / 1_000_000_000.0
        rolling = false
        b.btnScroll.setImageResource(R.drawable.ic_play)
    }

    private fun resetRoll() {
        stopRoll()
        scrollY = 0f
        elapsedAcc = 0.0
        b.clock.text = "0:00  / ${fmt(estimatedSeconds())}"
        b.clock.setTextColor(ContextCompat.getColor(this, R.color.text))
        b.progress.updateLayoutParams<FrameLayout.LayoutParams> { width = 0 }
        b.script.post { b.script.translationY = b.viewport.height * 0.12f }
    }

    private fun toggleRoll() = if (rolling) stopRoll() else startRoll()

    // ================================================================= controls

    private fun wireControls() {
        b.btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else runCountdown { startRecording() }
        }
        b.btnPause.setOnClickListener {
            val r = recording ?: return@setOnClickListener
            if (rolling) { r.pause(); stopRoll() } else { r.resume(); startRoll() }
        }
        b.btnScroll.setOnClickListener { toggleRoll() }
        b.btnFaster.setOnClickListener { setSpeed(speed + 1) }
        b.btnSlower.setOnClickListener { setSpeed(speed - 1) }
        b.preview.setOnClickListener { toggleRoll() }
        b.btnFlip.setOnClickListener {
            frontCamera = !frontCamera
            prefs().edit().putBoolean("front", frontCamera).apply()
            startCamera()
        }
        b.btnScripts.setOnClickListener { stopRoll(); showScripts() }
        b.gripEdit.setOnClickListener { stopRoll(); editScript(currentScript) }
        b.btnSettings.setOnClickListener { stopRoll(); showSettings() }

        dragHandle(b.gripResize) { delta ->
            b.panel.updateLayoutParams<FrameLayout.LayoutParams> {
                height = min((resources.displayMetrics.heightPixels * 0.8).toInt(),
                    max(dp(110), b.panel.height + delta))
            }
            prefs().edit().putInt("panelH", b.panel.layoutParams.height).apply()
        }
        dragHandle(b.gripMove) { delta ->
            b.panel.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = min((resources.displayMetrics.heightPixels * 0.5).toInt(),
                    max(0, (b.panel.layoutParams as FrameLayout.LayoutParams).topMargin + delta))
            }
            prefs().edit()
                .putInt("panelTop", (b.panel.layoutParams as FrameLayout.LayoutParams).topMargin)
                .apply()
        }

        setSpeed(speed)
        setFont(fontSp)
        setOpacity(opacity)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun dragHandle(handle: View, onDelta: (Int) -> Unit) {
        var lastY = 0f
        handle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastY = e.rawY; v.parent.requestDisallowInterceptTouchEvent(true); true }
                MotionEvent.ACTION_MOVE -> {
                    val d = (e.rawY - lastY).roundToInt()
                    if (d != 0) { onDelta(d); lastY = e.rawY }
                    true
                }
                else -> true
            }
        }
    }

    private fun setSpeed(v: Int) {
        speed = v.coerceIn(1, 30)
        b.speedVal.text = speed.toString()
        prefs().edit().putInt("speed", speed).apply()
    }

    private fun setFont(v: Int) {
        fontSp = v.coerceIn(16, 72)
        b.script.textSize = fontSp.toFloat()
        prefs().edit().putInt("font", fontSp).apply()
    }

    private fun setOpacity(v: Int) {
        opacity = v.coerceIn(0, 95)
        val alpha = (opacity * 255 / 100)
        b.panel.setBackgroundColor((alpha shl 24) or 0x141820)
        prefs().edit().putInt("opacity", opacity).apply()
    }

    private fun runCountdown(then: () -> Unit) {
        if (countdownSecs <= 0) { then(); return }
        var n = countdownSecs
        b.countdown.visibility = View.VISIBLE
        b.countdown.text = n.toString()
        val handler = b.countdown.handler ?: android.os.Handler(mainLooper)
        val tick = object : Runnable {
            override fun run() {
                n--
                if (n <= 0) {
                    b.countdown.visibility = View.GONE
                    then()
                } else {
                    b.countdown.text = n.toString()
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(tick, 1000)
    }

    // ================================================================= scripts

    private fun showScripts() {
        val names = scripts.map { "${it.name}  ·  ${words(it.body)} words, ${fmt(spoken(it.body))}" }
            .toMutableList()
        names.add("+  New script")

        AlertDialog.Builder(this)
            .setTitle("Scripts")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == scripts.size) editScript(-1)
                else {
                    currentScript = which
                    saveScripts()
                    applyScript()
                }
            }
            .setNeutralButton("Edit current") { _, _ -> editScript(currentScript) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun editScript(index: Int) {
        val pad = dp(16)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val nameField = EditText(this).apply {
            hint = "Script name"
            setText(if (index >= 0) scripts[index].name else "")
        }
        val bodyField = EditText(this).apply {
            hint = "Paste your script"
            setText(if (index >= 0) scripts[index].body else "")
            minLines = 8
            maxLines = 14
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        val info = TextView(this).apply {
            setPadding(0, dp(8), 0, 0)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
            textSize = 12f
            text = stat(bodyField.text.toString())
        }
        bodyField.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { info.text = stat(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
        })
        box.addView(nameField); box.addView(bodyField); box.addView(info)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (index >= 0) "Edit script" else "New script")
            .setView(box)
            .setPositiveButton("Save and use") { _, _ ->
                val name = nameField.text.toString().ifBlank { "Untitled" }
                val body = bodyField.text.toString()
                if (index >= 0) { scripts[index] = Script(name, body); currentScript = index }
                else { scripts.add(Script(name, body)); currentScript = scripts.size - 1 }
                saveScripts(); applyScript()
            }
            .setNegativeButton("Cancel", null)

        if (index >= 0 && scripts.size > 1) {
            dialog.setNeutralButton("Delete") { _, _ ->
                scripts.removeAt(index)
                if (currentScript >= scripts.size) currentScript = scripts.size - 1
                saveScripts(); applyScript()
            }
        }
        dialog.show()
    }

    private fun stat(s: String) = "${words(s)} words · about ${fmt(spoken(s))} spoken"

    private fun applyScript() {
        b.script.text = scripts[currentScript].body
        resetRoll()
    }

    private fun estimatedSeconds() = spoken(scripts[currentScript].body)
    private fun spoken(s: String) = words(s) / WPM * 60.0
    private fun words(s: String) = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size

    // ================================================================= settings

    private fun showSettings() {
        val pad = dp(18)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }

        fun label(t: String) = TextView(this).apply {
            text = t
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
            textSize = 13f
            setPadding(0, dp(12), 0, dp(4))
        }

        // finish in ---------------------------------------------------
        val targetLabel = label("Finish in ${targetSecs}s")
        val targetBar = SeekBar(this).apply {
            max = 285; progress = targetSecs - 15
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    targetSecs = p + 15
                    targetLabel.text = "Finish in ${targetSecs}s"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    prefs().edit().putInt("target", targetSecs).apply()
                }
            })
        }
        val fitNote = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
            textSize = 12f
        }
        fun refreshFit() {
            val secs = totalDistance() / max(1f, pxPerSecond())
            fitNote.text = "At speed $speed this script takes about ${fmt(secs.toDouble())}. " +
                "Spoken naturally it reads as ${fmt(estimatedSeconds())}."
        }
        refreshFit()
        val fitBtn = android.widget.Button(this).apply {
            text = "Set scroll speed to match"
            setOnClickListener {
                val needed = totalDistance() /
                    (targetSecs * DP_PER_SPEED_UNIT * resources.displayMetrics.density).toFloat()
                setSpeed(needed.roundToInt())
                refreshFit()
            }
        }

        // font --------------------------------------------------------
        val fontLabel = label("Font size ${fontSp}sp")
        val fontBar = SeekBar(this).apply {
            max = 56; progress = fontSp - 16
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    setFont(p + 16); fontLabel.text = "Font size ${fontSp}sp"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { refreshFit() }
            })
        }

        // opacity -----------------------------------------------------
        val opLabel = label("Panel opacity ${opacity}%")
        val opBar = SeekBar(this).apply {
            max = 95; progress = opacity
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    setOpacity(p); opLabel.text = "Panel opacity ${opacity}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        box.addView(targetLabel); box.addView(targetBar); box.addView(fitBtn); box.addView(fitNote)
        box.addView(fontLabel); box.addView(fontBar)
        box.addView(opLabel); box.addView(opBar)

        val scroll = android.widget.ScrollView(this).apply { addView(box) }

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .setNeutralButton("Countdown: ${countdownSecs}s") { _, _ ->
                countdownSecs = when (countdownSecs) { 0 -> 3; 3 -> 5; else -> 0 }
                prefs().edit().putInt("countdown", countdownSecs).apply()
                toast("Countdown ${if (countdownSecs == 0) "off" else "${countdownSecs}s"}")
            }
            .setNegativeButton("Quality: ${quality}p") { _, _ ->
                quality = when (quality) { 720 -> 1080; 1080 -> 2160; else -> 720 }
                prefs().edit().putInt("quality", quality).apply()
                startCamera()
                toast("Capture set to ${quality}p")
            }
            .show()
    }

    // ================================================================= storage

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadPrefs() = with(prefs()) {
        speed = getInt("speed", 5)
        fontSp = getInt("font", 30)
        opacity = getInt("opacity", 62)
        countdownSecs = getInt("countdown", 3)
        targetSecs = getInt("target", 75)
        quality = getInt("quality", 1080)
        frontCamera = getBoolean("front", true)
        getInt("panelH", 0).takeIf { it > 0 }?.let {
            b.panel.updateLayoutParams<FrameLayout.LayoutParams> { height = it }
        }
        getInt("panelTop", -1).takeIf { it >= 0 }?.let {
            b.panel.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = it }
        }
    }

    private fun loadScripts() {
        val raw = prefs().getString("scripts", null)
        scripts = mutableListOf()
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    scripts.add(Script(o.optString("name", "Untitled"), o.optString("body", "")))
                }
            } catch (_: Exception) { }
        }
        if (scripts.isEmpty()) scripts.add(Script("Upwork intro", DEFAULT_SCRIPT))
        currentScript = prefs().getInt("current", 0).coerceIn(0, scripts.size - 1)
    }

    private fun saveScripts() {
        val arr = JSONArray()
        scripts.forEach { arr.put(JSONObject().put("name", it.name).put("body", it.body)) }
        prefs().edit().putString("scripts", arr.toString()).putInt("current", currentScript).apply()
    }

    // ================================================================= helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fmt(seconds: Double): String {
        val s = max(0.0, seconds).toInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val DEFAULT_SCRIPT =
            "Hi, I'm Francis. I'm an engineer based in the UK and I build AI applications, " +
            "web apps and data pipelines.\n\n" +
            "A quick sense of what that actually means.\n\n" +
            "I built a Telegram bot that turns a text message, a voice note or a photo of a receipt " +
            "into a proper financial record. You just talk to it like a person. Behind that there's " +
            "over a hundred tests and a check that has to pass before I ship anything, because with AI " +
            "it's very easy to change one thing and quietly break another.\n\n" +
            "I built a healthcare analytics platform where you ask a question in plain English and it " +
            "writes the SQL, runs it, and shows you the query it used so you can check the answer yourself.\n\n" +
            "And I migrated a musician's website off WordPress onto a static site. Same look, same links, " +
            "hosting went from about a hundred and twenty pounds a year down to basically nothing.\n\n" +
            "The thread through all of it is that I finish things properly. Tests, documentation, code in " +
            "a repository you own, and a handover written in plain English so you're not stuck depending " +
            "on me afterwards.\n\n" +
            "If you've got something you're trying to build, message me and tell me what you're actually " +
            "trying to achieve. I'll tell you honestly whether I'm the right person for it."
    }
}
