package app.shelffinder.live

/**
 * Pure string-matching logic (no Android dependencies).
 *
 * The idea is "Ctrl+F that forgives OCR": both the query and the text read from the
 * camera are normalised the same way, then the query is aligned against any substring
 * of the OCR text with edit distance (Sellers' semi-global Levenshtein).
 */

enum class Fuzziness(val label: String) {
    EXACT("Exact"), NORMAL("Normal"), LOOSE("Loose");

    fun next(): Fuzziness = entries[(ordinal + 1) % entries.size]

    /** How many character edits are tolerated for a query of [len] normalised chars. */
    fun allowedEdits(len: Int): Int = when (this) {
        EXACT -> 0
        NORMAL -> when {
            len <= 3 -> 0
            len <= 6 -> 1
            len <= 10 -> 2
            else -> 3
        }
        LOOSE -> when {
            len <= 2 -> 0
            len <= 4 -> 1
            len <= 7 -> 2
            len <= 10 -> 3
            else -> 4
        }
    }
}

object Norm {
    class Result(val s: String, val map: IntArray)

    /**
     * Upper-cases, drops spaces/punctuation, and folds characters OCR commonly confuses
     * (0↔O, 1↔I, 5↔S, 8↔B, |↔I). [Result.map] gives, for each kept char, its index in [raw].
     */
    fun normalize(raw: String): Result {
        val sb = StringBuilder(raw.length)
        val map = IntArray(raw.length)
        var n = 0
        for (i in raw.indices) {
            val c = fold(raw[i]) ?: continue
            sb.append(c)
            map[n++] = i
        }
        return Result(sb.toString(), map.copyOf(n))
    }

    fun key(raw: String): String = normalize(raw).s

    private fun fold(ch: Char): Char? {
        val u = ch.uppercaseChar()
        return when (u) {
            in 'A'..'Z' -> u
            '0' -> 'O'
            '1', '|', '!' -> 'I'
            '5' -> 'S'
            '8' -> 'B'
            in '2'..'9' -> u
            else -> null
        }
    }
}

class Term(val raw: String) {
    val norm: String = Norm.key(raw)
}

class Align(val dist: Int, val start: Int, val end: Int)

/**
 * Smallest edit distance between [p] and any substring of [t].
 * Returns the distance and the [start, end) range of that substring in [t].
 */
fun substringAlign(p: String, t: String): Align {
    val m = p.length
    val n = t.length
    if (n == 0) return Align(m, 0, 0)
    var prev = IntArray(n + 1)              // row 0: a match may start anywhere for free
    var prevSt = IntArray(n + 1) { it }
    var cur = IntArray(n + 1)
    var curSt = IntArray(n + 1)
    for (i in 1..m) {
        cur[0] = i
        curSt[0] = 0
        val pc = p[i - 1]
        for (j in 1..n) {
            var best = prev[j - 1] + if (pc == t[j - 1]) 0 else 1
            var st = prevSt[j - 1]
            val skipP = prev[j] + 1
            if (skipP < best) { best = skipP; st = prevSt[j] }
            val skipT = cur[j - 1] + 1
            if (skipT < best) { best = skipT; st = curSt[j - 1] }
            cur[j] = best
            curSt[j] = st
        }
        val a = prev; prev = cur; cur = a
        val b = prevSt; prevSt = curSt; curSt = b
    }
    var bestJ = 0
    for (j in 1..n) if (prev[j] < prev[bestJ]) bestJ = j
    return Align(prev[bestJ], prevSt[bestJ], bestJ)
}

/** Result of testing one query against one OCR candidate string. */
class Scored(val score: Float, val start: Int, val end: Int)

/**
 * Scores [q] (normalised query) against [cand] (normalised OCR text).
 * Returns null when it isn't a match at the given fuzziness.
 *
 * A query found *inside* a much longer word (e.g. "ITRA" inside "ITRANOVA") is a valid
 * Ctrl+F hit, but is held to a stricter edit budget so short queries don't light up noise.
 */
fun scoreCandidate(q: String, cand: String, fuzz: Fuzziness): Scored? {
    if (q.length < 2 || cand.isEmpty()) return null
    val allowed = fuzz.allowedEdits(q.length)
    if (cand.length + allowed < q.length) return null
    val r = substringAlign(q, cand)
    val partial = cand.length > q.length + allowed
    val limit = if (partial) {
        if (q.length <= 5) 0 else (allowed - 1).coerceAtLeast(0)
    } else allowed
    if (r.dist > limit) return null
    var score = 1f - r.dist.toFloat() / q.length
    if (partial) score *= 0.97f
    return Scored(score, r.start, r.end)
}
