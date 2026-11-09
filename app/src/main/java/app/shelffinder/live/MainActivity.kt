package app.shelffinder.live

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.Size
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import app.shelffinder.live.databinding.ActivityMainBinding
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var analysisExecutor: ExecutorService
    private var controller: LifecycleCameraController? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val matcher = TextMatcher()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("shelffinder", MODE_PRIVATE) }

    // Read from the analysis thread, written on the main thread.
    @Volatile private var terms: List<Term> = emptyList()
    @Volatile private var fuzz = Fuzziness.NORMAL

    private var frozen = false
    private var lastText: Text? = null
    private var lastHapticAt = 0L
    private var sliding = false
    private var pendingQuery: Runnable? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionPanel()
        }

    private val voiceSearch =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val spoken = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (r.resultCode == RESULT_OK && !spoken.isNullOrBlank()) setQuery(spoken)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        analysisExecutor = Executors.newSingleThreadExecutor()

        val topPad = b.topPanel.paddingTop
        val bottomPad = b.bottomPanel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            b.topPanel.updatePadding(top = topPad + bars.top)
            b.bottomPanel.updatePadding(bottom = bottomPad + bars.bottom)
            insets
        }

        setupSearch()
        setupControls()
        updateStatus()

        if (hasCameraPermission()) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        // Returning from system settings after granting the permission there.
        if (controller == null && hasCameraPermission()) startCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        controller?.clearImageAnalysisAnalyzer()
        analysisExecutor.shutdown()
        recognizer.close()
    }

    // ---------------------------------------------------------------- camera

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        b.permissionPanel.isVisible = false
        if (controller != null) return

        val c = LifecycleCameraController(this)
        c.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        c.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        c.previewResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        // High analysis resolution: small print on packs needs pixels. Falls back on weaker phones.
        c.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()
        c.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        c.bindToLifecycle(this)
        b.previewView.controller = c   // also enables tap-to-focus and pinch-to-zoom
        controller = c

        c.zoomState.observe(this) { z ->
            b.zoomLabel.text = String.format(Locale.US, "%.1f×", z.zoomRatio)
            if (!sliding) b.zoomSlider.value = z.linearZoom.coerceIn(0f, 1f)
            b.torchButton.isVisible = c.cameraInfo?.hasFlashUnit() != false
        }
        c.torchState.observe(this) { state ->
            b.torchButton.setImageResource(if (state == TorchState.ON) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        }

        attachAnalyzer()
        updateStatus()
    }

    private fun attachAnalyzer() {
        val c = controller ?: return
        val analyzer = MlKitAnalyzer(
            listOf(recognizer),
            ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,   // results arrive in PreviewView pixels
            analysisExecutor,
        ) { result ->
            result.getThrowable(recognizer)?.let { Log.w(TAG, "Text recognition failed", it) }
            val text = result.getValue(recognizer) ?: return@MlKitAnalyzer
            val frameTerms = terms
            val words = collectWords(text)
            val hits = matcher.match(text, frameTerms, fuzz)
            main.post { onFrame(text, words, hits, frameTerms) }
        }
        c.setImageAnalysisAnalyzer(analysisExecutor, analyzer)
    }

    private fun onFrame(text: Text, words: List<OverlayView.Word>, hits: List<Hit>, frameTerms: List<Term>) {
        if (frozen || isFinishing || isDestroyed) return
        lastText = text
        b.overlay.setWords(words)
        if (frameTerms === terms) {          // ignore results computed for an older query
            val newlyFound = b.overlay.submitHits(hits)
            if (newlyFound.isNotEmpty()) buzz()
        }
        updateStatus()
    }

    private fun collectWords(text: Text): List<OverlayView.Word> {
        val out = ArrayList<OverlayView.Word>()
        for (block in text.textBlocks) for (line in block.lines) for (el in line.elements) {
            val bb = el.boundingBox ?: continue
            val clean = el.text.trim { !it.isLetterOrDigit() }
            if (Norm.key(clean).length < 2) continue
            out += OverlayView.Word(RectF(bb), clean)
        }
        return out
    }

    private fun setFrozen(f: Boolean) {
        if (f == frozen) return
        val c = controller ?: return
        frozen = f
        if (f) {
            b.frozenImage.setImageBitmap(b.previewView.bitmap)
            b.frozenImage.isVisible = true
            c.clearImageAnalysisAnalyzer()
            b.overlay.setFrozen(true)
            b.freezeButton.setImageResource(R.drawable.ic_play)
        } else {
            b.frozenImage.isVisible = false
            b.frozenImage.setImageDrawable(null)
            b.overlay.setFrozen(false)
            lastText = null
            attachAnalyzer()
            b.freezeButton.setImageResource(R.drawable.ic_pause)
        }
        updateStatus()
    }

    /** While paused, a new query is searched against the frozen frame. */
    private fun researchFrozenFrame() {
        val t = lastText ?: return
        b.overlay.replaceHits(matcher.match(t, terms, fuzz))
        updateStatus()
    }

    // ---------------------------------------------------------------- search

    private fun setupSearch() {
        val brands = runCatching {
            assets.open("brands.txt").bufferedReader().useLines { seq -> seq.map { it.trim() }.filter { it.isNotEmpty() }.toList() }
        }.getOrDefault(emptyList())
        b.query.setAdapter(ArrayAdapter(this, R.layout.item_suggestion, brands))
        b.query.setDropDownBackgroundResource(R.drawable.bg_dropdown)
        b.query.setOnItemClickListener { parent, _, pos, _ -> setQuery(parent.getItemAtPosition(pos).toString()) }

        // Live search as you type, like Ctrl+F.
        b.query.doAfterTextChanged { e ->
            val s = e?.toString().orEmpty()
            b.clearButton.isVisible = s.isNotEmpty()
            pendingQuery?.let(main::removeCallbacks)
            pendingQuery = Runnable { applyQuery(s) }.also { main.postDelayed(it, 180) }
        }
        b.query.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                commitQuery(v.text.toString()); true
            } else false
        }
        b.clearButton.setOnClickListener { b.query.setText("") }
        b.micButton.setOnClickListener { startVoiceSearch() }
        renderRecents()
    }

    private fun setQuery(s: String) {
        b.query.setText(s, false)
        b.query.setSelection(s.length)
        b.query.dismissDropDown()
        commitQuery(s)
    }

    private fun commitQuery(s: String) {
        pendingQuery?.let(main::removeCallbacks)
        applyQuery(s)
        saveRecent(s)
        hideKeyboard()
    }

    private fun applyQuery(raw: String) {
        val parsed = raw.split(',', ';')
            .map { it.trim() }
            .filter { Norm.key(it).length >= 2 }
            .distinctBy { Norm.key(it) }
            .take(MAX_TERMS)
            .map { Term(it) }
        if (parsed.map { it.norm } == terms.map { it.norm }) return
        terms = parsed
        b.overlay.clearHits()
        if (frozen) researchFrozenFrame()
        updateStatus()
    }

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        try {
            voiceSearch.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.query.windowToken, 0)
        b.query.clearFocus()
    }

    private fun recents(): List<String> =
        prefs.getString(KEY_RECENT, "").orEmpty().split('\n').filter { it.isNotBlank() }

    private fun saveRecent(s: String) {
        val q = s.trim()
        if (Norm.key(q).length < 2) return
        val list = (listOf(q) + recents().filterNot { it.equals(q, ignoreCase = true) }).take(MAX_RECENTS)
        prefs.edit().putString(KEY_RECENT, list.joinToString("\n")).apply()
        renderRecents()
    }

    private fun renderRecents() {
        val group = b.chips
        for (i in group.childCount - 1 downTo 0) {
            if (group.getChildAt(i).tag == RECENT_TAG) group.removeViewAt(i)
        }
        for (r in recents()) {
            group.addView(Chip(this).apply {
                text = r
                tag = RECENT_TAG
                isCheckable = false
                setOnClickListener { setQuery(r) }
            })
        }
    }

    // ---------------------------------------------------------------- controls

    private fun setupControls() {
        b.chipSpotlight.setOnCheckedChangeListener { _, on -> b.overlay.spotlight = on }
        b.chipShowText.setOnCheckedChangeListener { _, on -> b.overlay.showAllText = on; updateStatus() }
        b.chipFuzzy.text = getString(R.string.fuzzy_label, fuzz.label)
        b.chipFuzzy.setOnClickListener {
            fuzz = fuzz.next()
            b.chipFuzzy.text = getString(R.string.fuzzy_label, fuzz.label)
            b.overlay.clearHits()
            if (frozen) researchFrozenFrame()
        }
        b.overlay.onWordTapped = { word ->
            b.chipShowText.isChecked = false
            setQuery(word)
        }

        b.torchButton.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            c.enableTorch(c.torchState.value != TorchState.ON)
        }
        b.freezeButton.setOnClickListener { setFrozen(!frozen) }

        b.zoomSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) controller?.setLinearZoom(value)
        }
        b.zoomSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { sliding = true }
            override fun onStopTrackingTouch(slider: Slider) { sliding = false }
        })

        b.permissionButton.setOnClickListener {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                cameraPermission.launch(Manifest.permission.CAMERA)
            } else {
                // Permanently denied: the only way back is the app's settings page.
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
        }
    }

    private fun showPermissionPanel() {
        b.permissionPanel.isVisible = true
        updateStatus()
    }

    private fun updateStatus() {
        val current = terms
        val sb = SpannableStringBuilder()
        when {
            controller == null && !hasCameraPermission() -> sb.append(getString(R.string.status_need_camera))
            current.isEmpty() && b.overlay.showAllText -> sb.append(getString(R.string.status_tap_word, b.overlay.wordCount))
            current.isEmpty() -> sb.append(getString(R.string.status_idle))
            else -> current.forEachIndexed { i, term ->
                if (i > 0) sb.append("    ")
                val dot = sb.length
                sb.append("● ")
                sb.setSpan(ForegroundColorSpan(OverlayView.colorFor(i)), dot, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val name = sb.length
                sb.append(term.raw)
                sb.setSpan(StyleSpan(Typeface.BOLD), name, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val n = b.overlay.liveCount(i)
                sb.append(if (n > 0) "  ×$n" else "  " + getString(R.string.searching))
            }
        }
        if (frozen) sb.insert(0, getString(R.string.paused_prefix))
        b.status.text = sb
    }

    @Suppress("DEPRECATION")
    private fun buzz() {
        val now = SystemClock.uptimeMillis()
        if (now - lastHapticAt < 1200) return
        lastHapticAt = now
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    companion object {
        private const val TAG = "ShelfFinder"
        private const val MAX_TERMS = 4
        private const val MAX_RECENTS = 6
        private const val KEY_RECENT = "recent"
        private const val RECENT_TAG = "recent"
    }
}
