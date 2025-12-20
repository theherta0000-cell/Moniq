package com.example.moniq.views

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.moniq.R
import com.example.moniq.lyrics.SyllableLine

/**
 * Simplified lyrics view: renders whole lines centered vertically.
 * Highlights the current line and centers it when in focused mode.
 */
class LyricsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): ScrollView(context, attrs) {
    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(0, pad, 0, pad)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    private data class LineInfo(val startMs: Long, val endMs: Long, val text: String, val syllableOffsets: List<Int>, val syllables: List<com.example.moniq.lyrics.Syllable>)
    private val lines = mutableListOf<LineInfo>()
    private val textViews = mutableListOf<TextView>()
    private var currentIndex = -1
    private var focusedMode = false
    private var accentColor: Int = try { context.getColor(com.example.moniq.R.color.purple_700) } catch (_: Exception) { Color.MAGENTA }

    init {
        addView(container)
    }

    fun setLines(syllableLines: List<SyllableLine>) {
        container.removeAllViews()
        lines.clear()
        textViews.clear()
        // convert syllable-lines to whole-line text with start and end timestamps
        for (sl in syllableLines) {
            // build full line text and record syllable start offsets
            val sb = StringBuilder()
            val offsets = mutableListOf<Int>()
                for (s in sl.syllables) {
                offsets.add(sb.length)
                sb.append(s.text)
            }
                val lineText = sb.toString()
            val start = sl.startMs
            val end = if (sl.syllables.isNotEmpty()) {
                val last = sl.syllables.last()
                last.startMs + last.durationMs
            } else start + 2000L
                lines.add(LineInfo(start, end, lineText, offsets, sl.syllables))

            val tv = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
                setTextColor(Color.LTGRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    text = lineText
                setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
            }
            textViews.add(tv)
            container.addView(tv)
        }
        currentIndex = -1
    }

    fun clear() {
        container.removeAllViews()
        lines.clear()
        textViews.clear()
        currentIndex = -1
    }

    fun setFocusedMode(enabled: Boolean) {
        focusedMode = enabled
        // adjust sizes
        for ((i, tv) in textViews.withIndex()) {
            if (enabled) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (i == currentIndex) 32f else 18f)
                tv.setTextColor(if (i == currentIndex) accentColor else Color.LTGRAY)
            } else {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                tv.setTextColor(Color.LTGRAY)
            }
        }
    }

    fun updatePosition(posMs: Long) {
        if (lines.isEmpty()) return
        // find index
        var idx = -1
        for ((i, l) in lines.withIndex()) {
            if (posMs >= l.startMs && posMs < l.endMs) { idx = i; break }
        }
        if (idx == -1 && posMs >= lines.last().endMs) idx = lines.size - 1
        if (idx == currentIndex) {
            // still same line; update syllable highlight
            if (idx in lines.indices) highlightSyllable(idx, posMs)
            return
        }
        // update previous
        if (currentIndex in textViews.indices) {
            val prev = textViews[currentIndex]
            prev.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(180).start()
            prev.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (focusedMode) 18f else 18f)
            prev.setTextColor(Color.LTGRAY)
        }
        currentIndex = idx
        if (currentIndex in textViews.indices) {
            val cur = textViews[currentIndex]
            cur.animate().scaleX(1.08f).scaleY(1.08f).alpha(1.0f).setDuration(200).start()
            cur.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (focusedMode) 32f else 20f)
            cur.setTextColor(context.getColor(R.color.purple_700))
            // apply per-syllable highlight for this line
            highlightSyllable(currentIndex, posMs)
            // always center the active line for better focus
            post {
                val target = (cur.top - height / 2 + cur.height / 2).coerceAtLeast(0)
                // immediate scroll to ensure the active line is centered; fallback to smooth scroll
                scrollTo(0, target)
                postDelayed({ smoothScrollTo(0, target) }, 120)
            }
        }
    }

    private fun highlightSyllable(lineIndex: Int, posMs: Long) {
        val li = lines[lineIndex]
        val tv = textViews[lineIndex]
        val spannable = android.text.SpannableString(li.text)
        // default style for whole line
        spannable.setSpan(android.text.style.ForegroundColorSpan(Color.LTGRAY), 0, li.text.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        // find syllable index
        var sIdx = -1
        for ((i, s) in li.syllables.withIndex()) {
            val sStart = s.startMs
            val sEnd = s.startMs + s.durationMs
            if (posMs >= sStart && posMs < sEnd) { sIdx = i; break }
        }
        if (sIdx == -1 && posMs >= li.endMs) sIdx = li.syllables.size - 1

        val fullColor = accentColor
        val baseColor = Color.LTGRAY

        // color earlier syllables fully, current syllable partially (blend), later syllables base color
        for (i in li.syllables.indices) {
            val start = li.syllableOffsets.getOrNull(i) ?: 0
            val end = if (i + 1 < li.syllableOffsets.size) li.syllableOffsets[i + 1] else li.text.length
            if (i < sIdx) {
                spannable.setSpan(android.text.style.ForegroundColorSpan(fullColor), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (i == sIdx) {
                val s = li.syllables[i]
                val progress = if (s.durationMs > 0) ((posMs - s.startMs).toFloat() / s.durationMs.toFloat()).coerceIn(0f, 1f) else 1f
                val blended = blendColors(baseColor, fullColor, progress)
                spannable.setSpan(android.text.style.ForegroundColorSpan(blended), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                // later syllables keep base color (already set)
            }
        }

        tv.text = spannable
    }

    private fun blendColors(a: Int, b: Int, ratio: Float): Int {
        val inverse = 1f - ratio
        val aA = (a shr 24) and 0xff
        val aR = (a shr 16) and 0xff
        val aG = (a shr 8) and 0xff
        val aB = a and 0xff

        val bA = (b shr 24) and 0xff
        val bR = (b shr 16) and 0xff
        val bG = (b shr 8) and 0xff
        val bB = b and 0xff

        val A = (aA * inverse + bA * ratio).toInt() and 0xff
        val R = (aR * inverse + bR * ratio).toInt() and 0xff
        val G = (aG * inverse + bG * ratio).toInt() and 0xff
        val B = (aB * inverse + bB * ratio).toInt() and 0xff
        return (A shl 24) or (R shl 16) or (G shl 8) or B
    }

    fun setAccentColor(color: Int) {
        try {
            // ensure accent is readable against dark backgrounds: if luminance is too low,
            // lighten the color slightly for better contrast
            val safe = ensureReadableAccent(color)
            accentColor = safe
            // refresh current styling
            if (focusedMode) setFocusedMode(true) else setFocusedMode(false)
            // also force re-highlight for current position
            if (currentIndex >= 0) updatePosition((lines.getOrNull(currentIndex)?.startMs ?: 0L))
        } catch (_: Exception) {}
    }

    private fun ensureReadableAccent(color: Int): Int {
        try {
            val r = ((color shr 16) and 0xff).toDouble()
            val g = ((color shr 8) and 0xff).toDouble()
            val b = (color and 0xff).toDouble()
            // relative luminance per WCAG
            fun channel(c: Double): Double {
                val v = c / 255.0
                return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            }
            val lum = 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
            // target minimum luminance; if too dark, blend toward white
            if (lum < 0.15) {
                // blend factor to mix with white
                val factor = ((0.15 - lum) / 0.15).coerceIn(0.12, 0.7)
                val nr = (r + (255 - r) * factor).toInt().coerceIn(0,255)
                val ng = (g + (255 - g) * factor).toInt().coerceIn(0,255)
                val nb = (b + (255 - b) * factor).toInt().coerceIn(0,255)
                return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        } catch (_: Exception) {}
        return color
    }
}