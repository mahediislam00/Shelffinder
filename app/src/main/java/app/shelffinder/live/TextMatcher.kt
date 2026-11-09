package app.shelffinder.live

import android.graphics.Rect
import android.graphics.RectF
import com.google.mlkit.vision.text.Text
import kotlin.math.max
import kotlin.math.min

/** One highlighted match, in PreviewView coordinates. */
class Hit(val term: Int, val box: RectF, val text: String, val score: Float)

/**
 * Finds every query term inside one frame of ML Kit OCR output.
 *
 * Candidates are single words plus runs of up to 3 neighbouring words on a line, so
 * "Bisopro Plus" or a name OCR split as "Panto nix" is still found.
 * Each normalised character keeps its own box, so a partial hit (query inside a longer
 * word) is boxed tightly rather than highlighting the whole word.
 */
class TextMatcher {

    private class Piece(val norm: String, val boxes: List<RectF>)

    fun match(text: Text, terms: List<Term>, fuzz: Fuzziness): List<Hit> {
        if (terms.isEmpty()) return emptyList()
        val lines: List<List<Piece>> = text.textBlocks.flatMap { block ->
            block.lines.map { line -> line.elements.mapNotNull { piece(it) } }
        }
        val out = ArrayList<Hit>()
        terms.forEachIndexed { ti, term ->
            val q = term.norm
            if (q.length < 2) return@forEachIndexed
            val found = ArrayList<Hit>()
            for (line in lines) {
                for (i in line.indices) {
                    val sb = StringBuilder()
                    val boxes = ArrayList<RectF>()
                    for (span in 0 until MAX_SPAN) {
                        val k = i + span
                        if (k >= line.size) break
                        if (span > 0 && sb.length >= q.length) break   // joining more words can't help
                        sb.append(line[k].norm)
                        boxes.addAll(line[k].boxes)
                        val s = scoreCandidate(q, sb.toString(), fuzz) ?: continue
                        found += Hit(ti, unionOf(boxes, s.start, s.end), term.raw, s.score)
                    }
                }
            }
            out += suppressDuplicates(found)
        }
        return out
    }

    private fun piece(el: Text.Element): Piece? {
        val bb = el.boundingBox ?: return null
        val raw = el.text
        val n = Norm.normalize(raw)
        if (n.s.isEmpty()) return null
        val symbols = el.symbols
        // Use per-character symbol boxes only if they look sane (one per char, inside the word box).
        val tol = max(bb.height(), bb.width() / max(1, raw.length)) / 2
        val useSymbols = symbols.size == raw.length && symbols.all { sym ->
            val sb = sym.boundingBox
            sb != null && sb.centerX() in (bb.left - tol)..(bb.right + tol) &&
                sb.centerY() in (bb.top - tol)..(bb.bottom + tol)
        }
        val boxes = n.map.map { oi ->
            if (useSymbols) RectF(symbols[oi].boundingBox!!) else slice(bb, oi, raw.length)
        }
        return Piece(n.s, boxes)
    }

    /** Approximate box of character [i] of [len] inside a word box. */
    private fun slice(bb: Rect, i: Int, len: Int): RectF {
        val vertical = bb.height() > bb.width() * 1.3f
        return if (vertical) {
            val step = bb.height().toFloat() / len
            RectF(bb.left.toFloat(), bb.top + step * i, bb.right.toFloat(), bb.top + step * (i + 1))
        } else {
            val step = bb.width().toFloat() / len
            RectF(bb.left + step * i, bb.top.toFloat(), bb.left + step * (i + 1), bb.bottom.toFloat())
        }
    }

    private fun unionOf(boxes: List<RectF>, start: Int, end: Int): RectF {
        val from = if (end > start) start.coerceIn(0, boxes.size - 1) else 0
        val to = if (end > start) end.coerceIn(from + 1, boxes.size) else boxes.size
        val r = RectF(boxes[from])
        for (i in from + 1 until to) r.union(boxes[i])
        return r
    }

    /** Keeps the best hit where several candidates cover the same spot. */
    private fun suppressDuplicates(hits: List<Hit>): List<Hit> {
        if (hits.size < 2) return hits
        val sorted = hits.sortedWith(
            compareByDescending<Hit> { it.score }.thenBy { it.box.width() * it.box.height() }
        )
        val kept = ArrayList<Hit>()
        for (h in sorted) if (kept.none { overlap(it.box, h.box) > 0.5f }) kept += h
        return kept
    }

    private fun overlap(a: RectF, b: RectF): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val smaller = min(a.width() * a.height(), b.width() * b.height())
        return if (smaller <= 0f) 0f else (w * h) / smaller
    }

    companion object {
        private const val MAX_SPAN = 3
    }
}
