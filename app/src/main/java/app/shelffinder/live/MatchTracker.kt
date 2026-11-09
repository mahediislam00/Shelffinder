package app.shelffinder.live

import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Smooths per-frame OCR hits into stable on-screen highlights.
 *
 * OCR on a moving camera flickers: a word is read in one frame, missed in the next.
 * Each hit is matched to an existing track (same term, overlapping position); tracks glide
 * toward new positions, are held briefly when a frame misses them, then fade out.
 */
class MatchTracker {

    class Track(
        val term: Int,
        box: RectF,
        var label: String,
        var score: Float,
        var lastSeen: Long,
        val bornReal: Long,
    ) {
        val target = RectF(box)
        val shown = RectF(box)
    }

    val tracks = ArrayList<Track>()

    fun clear() = tracks.clear()

    fun alpha(t: Track, now: Long): Float {
        val idle = now - t.lastSeen
        return if (idle <= HOLD_MS) 1f else (1f - (idle - HOLD_MS).toFloat() / FADE_MS).coerceIn(0f, 1f)
    }

    fun liveCount(term: Int, now: Long): Int =
        tracks.count { it.term == term && now - it.lastSeen <= HOLD_MS }

    /** Feeds one frame of hits. Returns the term indexes that just became visible. */
    fun update(hits: List<Hit>, now: Long): Set<Int> {
        val before = tracks.filter { now - it.lastSeen <= HOLD_MS }.mapTo(HashSet()) { it.term }
        val claimed = HashSet<Track>()
        for (h in hits) {
            var best: Track? = null
            var bestSim = 0f
            for (t in tracks) {
                if (t.term != h.term || t in claimed) continue
                val s = similarity(t.target, h.box)
                if (s > bestSim) { bestSim = s; best = t }
            }
            val track = if (best != null && bestSim >= 0.2f) {
                best.target.set(h.box)
                best.lastSeen = now
                best.score = h.score
                best.label = h.text
                best
            } else {
                Track(h.term, h.box, h.text, h.score, now, SystemClock.uptimeMillis()).also { tracks += it }
            }
            claimed += track
        }
        tracks.removeAll { now - it.lastSeen > HOLD_MS + FADE_MS }
        return hits.mapTo(HashSet()) { it.term } - before
    }

    /** Advances the glide animation by one display frame. Returns true if another frame is needed. */
    fun step(frozen: Boolean): Boolean {
        var moving = false
        for (t in tracks) if (glide(t.shown, t.target)) moving = true
        return moving || (!frozen && tracks.isNotEmpty())
    }

    private fun glide(s: RectF, t: RectF): Boolean {
        val d = abs(s.left - t.left) + abs(s.top - t.top) + abs(s.right - t.right) + abs(s.bottom - t.bottom)
        if (d < 1f) { s.set(t); return false }
        s.left += (t.left - s.left) * GLIDE
        s.top += (t.top - s.top) * GLIDE
        s.right += (t.right - s.right) * GLIDE
        s.bottom += (t.bottom - s.bottom) * GLIDE
        return true
    }

    private fun similarity(a: RectF, b: RectF): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w > 0f && h > 0f) {
            val inter = w * h
            val union = a.width() * a.height() + b.width() * b.height() - inter
            if (union > 0f) {
                val iou = inter / union
                if (iou >= 0.2f) return iou
            }
        }
        // Fast camera motion: fall back to "centres are close for this box size".
        val dist = hypot(a.centerX() - b.centerX(), a.centerY() - b.centerY())
        val size = max(max(a.width(), a.height()), max(b.width(), b.height()))
        return if (size > 0f && dist < size * 0.6f) 0.2f else 0f
    }

    companion object {
        const val HOLD_MS = 400L
        const val FADE_MS = 450L
        private const val GLIDE = 0.3f
    }
}
