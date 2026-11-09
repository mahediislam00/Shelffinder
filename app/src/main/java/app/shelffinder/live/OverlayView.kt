package app.shelffinder.live

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws live match highlights over the camera preview.
 * Coordinates are PreviewView coordinates (the analyzer is view-referenced), so no mapping is needed.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    class Word(val box: RectF, val text: String)

    var showAllText = false
        set(value) { field = value; invalidate() }

    /** Dims everything except the matches. */
    var spotlight = true
        set(value) { field = value; invalidate() }

    /** Called when the user taps a word while [showAllText] is on. */
    var onWordTapped: ((String) -> Unit)? = null

    val wordCount: Int get() = words.size

    private val dp = resources.displayMetrics.density
    private var words: List<Word> = emptyList()
    private val tracker = MatchTracker()
    private var frozenAt = -1L
    private var downWord: Word? = null

    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = 0xCC22D17A.toInt()
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.5f * dp; strokeJoin = Paint.Join.ROUND
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * dp
    }
    private val dimPaint = Paint().apply { color = Color.BLACK }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * dp; typeface = Typeface.DEFAULT_BOLD; color = 0xFF1A1500.toInt()
    }
    private val r1 = RectF()
    private val r2 = RectF()

    private fun now(): Long = if (frozenAt >= 0) frozenAt else SystemClock.uptimeMillis()

    fun setWords(list: List<Word>) {
        words = list
        if (showAllText) invalidate()
    }

    /** Returns term indexes that just appeared (for haptic feedback). */
    fun submitHits(hits: List<Hit>): Set<Int> {
        val found = tracker.update(hits, now())
        invalidate()
        return found
    }

    /** Replace all highlights at once (used when re-searching a paused frame). */
    fun replaceHits(hits: List<Hit>) {
        tracker.clear()
        tracker.update(hits, now())
        invalidate()
    }

    fun clearHits() {
        tracker.clear()
        invalidate()
    }

    fun liveCount(term: Int): Int = tracker.liveCount(term, now())

    fun setFrozen(frozen: Boolean) {
        frozenAt = if (frozen) SystemClock.uptimeMillis() else -1L
        if (!frozen) {
            tracker.clear()
            words = emptyList()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = now()
        val real = SystemClock.uptimeMillis()
        var animating = tracker.step(frozenAt >= 0)

        if (showAllText) for (w in words) canvas.drawRect(w.box, wordPaint)

        val tracks = tracker.tracks
        if (tracks.isNotEmpty()) {
            val pad = 6f * dp
            val radius = 8f * dp

            if (spotlight) {
                var maxA = 0f
                for (t in tracks) maxA = max(maxA, tracker.alpha(t, now))
                if (maxA > 0.01f) {
                    val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
                    dimPaint.alpha = (140 * maxA).toInt()
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
                    for (t in tracks) {
                        r1.set(t.shown); r1.inset(-pad, -pad)
                        canvas.drawRoundRect(r1, radius, radius, clearPaint)
                    }
                    canvas.restoreToCount(layer)
                }
            }

            for (t in tracks) {
                val a = tracker.alpha(t, now)
                if (a <= 0f) continue
                val color = colorFor(t.term)
                r1.set(t.shown); r1.inset(-pad, -pad)
                boxPaint.color = color
                boxPaint.alpha = (255 * a).toInt()
                canvas.drawRoundRect(r1, radius, radius, boxPaint)

                val age = real - t.bornReal
                if (age in 0 until PULSE_MS) {
                    val p = age.toFloat() / PULSE_MS
                    val grow = p * 18f * dp
                    r2.set(r1); r2.inset(-grow, -grow)
                    pulsePaint.color = color
                    pulsePaint.alpha = ((1f - p) * 220 * a).toInt()
                    canvas.drawRoundRect(r2, radius + grow, radius + grow, pulsePaint)
                    animating = true
                }
                drawLabel(canvas, t, r1, color, a)
            }
        }
        if (animating) postInvalidateOnAnimation()
    }

    private fun drawLabel(c: Canvas, t: MatchTracker.Track, box: RectF, color: Int, a: Float) {
        val pct = (t.score * 100).roundToInt()
        val label = if (pct >= 100) t.label else "${t.label} · $pct%"
        val padX = 8f * dp
        val h = 22f * dp
        val w = labelText.measureText(label) + padX * 2
        var top = box.top - h - 4f * dp
        if (top < 0f) top = box.bottom + 4f * dp
        var left = box.left
        if (left + w > width) left = width - w
        left = max(0f, left)
        r2.set(left, top, left + w, top + h)
        labelBg.color = color
        labelBg.alpha = (255 * a).toInt()
        c.drawRoundRect(r2, h / 2, h / 2, labelBg)
        labelText.alpha = (255 * a).toInt()
        val fm = labelText.fontMetrics
        c.drawText(label, left + padX, top + h / 2 - (fm.ascent + fm.descent) / 2, labelText)
    }

    // Taps only get consumed when they land on a word in "show all text" mode;
    // everything else falls through to the PreviewView (tap-to-focus, pinch-to-zoom).
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!showAllText || onWordTapped == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downWord = wordAt(e.x, e.y)
                return downWord != null
            }
            MotionEvent.ACTION_UP -> {
                val w = downWord
                downWord = null
                if (w != null && wordAt(e.x, e.y) === w) {
                    performClick()
                    onWordTapped?.invoke(w.text)
                }
                return w != null
            }
            MotionEvent.ACTION_CANCEL -> downWord = null
        }
        return downWord != null
    }

    override fun performClick(): Boolean = super.performClick()

    private fun wordAt(x: Float, y: Float): Word? {
        val slop = 8f * dp
        return words.firstOrNull { w ->
            x >= w.box.left - slop && x <= w.box.right + slop && y >= w.box.top - slop && y <= w.box.bottom + slop
        }
    }

    companion object {
        private val COLORS = intArrayOf(
            0xFFFFD400.toInt(), // yellow (like the web app)
            0xFF22E5FF.toInt(), // cyan
            0xFFFF5FD2.toInt(), // pink
            0xFF9BFF4F.toInt(), // lime
        )
        private const val PULSE_MS = 700L

        fun colorFor(term: Int): Int = COLORS[term % COLORS.size]
    }
}
