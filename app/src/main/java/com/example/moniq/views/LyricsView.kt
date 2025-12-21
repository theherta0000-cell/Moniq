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
     * Simplified lyrics view: renders whole lines centered vertically with transliterations and translations.
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

        private data class LineInfo(
    val startMs: Long, 
    val endMs: Long, 
    val text: String, 
    val syllableOffsets: List<Int>, 
    val syllables: List<com.example.moniq.lyrics.Syllable>,
    val transliteration: String?,
    val translation: String?
)

private val lines = mutableListOf<LineInfo>()
private val textViews = mutableListOf<TextView>()
private val transliterationViews = mutableListOf<TextView>()
private val translationViews = mutableListOf<TextView>()
private var currentIndex = -1
private var focusedMode = false
private var accentColor: Int = try { context.getColor(com.example.moniq.R.color.purple_700) } catch (_: Exception) { Color.MAGENTA }
private var showTransliteration = true
private var showTranslation = true
private var onSeekListener: ((Long) -> Unit)? = null

        init {
            addView(container)
        }

        fun setOnSeekListener(listener: (Long) -> Unit) {
    onSeekListener = listener
}

        fun setLines(syllableLines: List<SyllableLine>) {
            container.removeAllViews()
            lines.clear()
            textViews.clear()
            transliterationViews.clear()
            translationViews.clear()
            
            for (sl in syllableLines) {
                // Build full line text and transliteration, recording syllable start offsets
                val sb = StringBuilder()
                val translitSb = StringBuilder()
                val offsets = mutableListOf<Int>()
                for (s in sl.syllables) {
                    offsets.add(sb.length)
                    sb.append(s.text)
                    if (s.transliteration != null) {
                        translitSb.append(s.transliteration)
                    }
                }
                val lineText = sb.toString()
                val translitText = if (translitSb.isNotEmpty()) translitSb.toString() else null
                val start = sl.startMs
                val end = if (sl.syllables.isNotEmpty()) {
                    val last = sl.syllables.last()
                    last.startMs + last.durationMs
                } else start + 2000L
                
                lines.add(LineInfo(start, end, lineText, offsets, sl.syllables, translitText, sl.translation))

                // Create a vertical container for this line (original + transliteration + translation)
                val lineContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    // Make line clickable for seeking
    isClickable = true
    isFocusable = true
    val ripple = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(accentColor and 0x40FFFFFF),
        null,
        null
    )
    background = ripple
    setOnClickListener {
        onSeekListener?.invoke(start)
    }
}

                // Original text view
                val tv = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    gravity = Gravity.CENTER
                    setTextColor(Color.LTGRAY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    text = lineText
                    val padding = (6 * resources.displayMetrics.density).toInt()
                    setPadding(0, padding, 0, 0)
                }
                textViews.add(tv)
                lineContainer.addView(tv)

                // Transliteration view (romanization)
val translitTv = TextView(context).apply {
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    gravity = Gravity.CENTER
    setTextColor(Color.GRAY)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    text = translitText ?: ""
    // Hide if empty, disabled, or identical to main text
    val isDuplicate = !translitText.isNullOrEmpty() && translitText.trim().equals(lineText.trim(), ignoreCase = true)
    visibility = if ((translitText.isNullOrEmpty() || !showTransliteration || isDuplicate)) GONE else VISIBLE
    val padding = (2 * resources.displayMetrics.density).toInt()
    setPadding(0, padding, 0, padding)
    alpha = 0.6f
    setTypeface(null, android.graphics.Typeface.ITALIC)
}
                transliterationViews.add(translitTv)
                lineContainer.addView(translitTv)

                // Translation view
val translationTv = TextView(context).apply {
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    gravity = Gravity.CENTER
    setTextColor(Color.GRAY)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    text = sl.translation ?: ""
    // Hide if empty, disabled, or identical to main text
    val isDuplicate = !sl.translation.isNullOrEmpty() && sl.translation.trim().equals(lineText.trim(), ignoreCase = true)
    visibility = if ((sl.translation.isNullOrEmpty() || !showTranslation || isDuplicate)) GONE else VISIBLE
    val padding = (4 * resources.displayMetrics.density).toInt()
    setPadding(0, padding, 0, padding)
    alpha = 0.7f
}
                translationViews.add(translationTv)
                lineContainer.addView(translationTv)

                container.addView(lineContainer)
            }
            currentIndex = -1
        }

        fun clear() {
            container.removeAllViews()
            lines.clear()
            textViews.clear()
            transliterationViews.clear()
            translationViews.clear()
            currentIndex = -1
        }

        fun setFocusedMode(enabled: Boolean) {
            focusedMode = enabled
            for ((i, tv) in textViews.withIndex()) {
                if (enabled) {
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (i == currentIndex) 32f else 18f)
                    tv.setTextColor(if (i == currentIndex) accentColor else Color.LTGRAY)
                    transliterationViews.getOrNull(i)?.apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (i == currentIndex) 15f else 13f)
                    }
                    translationViews.getOrNull(i)?.apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (i == currentIndex) 16f else 14f)
                    }
                } else {
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    tv.setTextColor(Color.LTGRAY)
                    transliterationViews.getOrNull(i)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    translationViews.getOrNull(i)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                }
            }
        }

        fun updatePosition(posMs: Long) {
            if (lines.isEmpty()) return
            
            var idx = -1
            for ((i, l) in lines.withIndex()) {
                if (posMs >= l.startMs && posMs < l.endMs) { 
                    idx = i
                    break 
                }
            }
            if (idx == -1 && posMs >= lines.last().endMs) idx = lines.size - 1
            
            if (idx == currentIndex) {
                if (idx in lines.indices) highlightSyllable(idx, posMs)
                return
            }
            
            // Update previous
            if (currentIndex in textViews.indices) {
                val prev = textViews[currentIndex]
                prev.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(180).start()
                prev.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (focusedMode) 18f else 18f)
                prev.setTextColor(Color.LTGRAY)
                transliterationViews.getOrNull(currentIndex)?.apply {
                    animate().scaleX(1.0f).scaleY(1.0f).alpha(0.6f).setDuration(180).start()
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (focusedMode) 13f else 13f)
                }
                translationViews.getOrNull(currentIndex)?.apply {
                    animate().scaleX(1.0f).scaleY(1.0f).alpha(0.7f).setDuration(180).start()
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (focusedMode) 14f else 14f)
                }
            }
            
            currentIndex = idx
            if (currentIndex in textViews.indices) {
                val cur = textViews[currentIndex]
                cur.animate().scaleX(1.08f).scaleY(1.08f).alpha(1.0f).setDuration(200).start()
                cur.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (focusedMode) 32f else 20f)
                cur.setTextColor(context.getColor(R.color.purple_700))
                
                transliterationViews.getOrNull(currentIndex)?.apply {
                    animate().scaleX(1.05f).scaleY(1.05f).alpha(0.8f).setDuration(200).start()
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (focusedMode) 15f else 13f)
                }
                
                translationViews.getOrNull(currentIndex)?.apply {
                    animate().scaleX(1.05f).scaleY(1.05f).alpha(0.9f).setDuration(200).start()
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (focusedMode) 16f else 14f)
                }
                
                highlightSyllable(currentIndex, posMs)
                
                // Center the active line - get the parent lineContainer's position
                post {
                    val lineContainer = cur.parent as? ViewGroup
                    if (lineContainer != null) {
                        val target = (lineContainer.top - height / 2 + lineContainer.height / 2).coerceAtLeast(0)
                        smoothScrollTo(0, target)
                    }
                }
            }
        }

        private fun highlightSyllable(lineIndex: Int, posMs: Long) {
            val li = lines[lineIndex]
            val tv = textViews[lineIndex]
            val spannable = android.text.SpannableString(li.text)
            
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(Color.LTGRAY), 
                0, 
                li.text.length, 
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            var sIdx = -1
            for ((i, s) in li.syllables.withIndex()) {
                val sStart = s.startMs
                val sEnd = s.startMs + s.durationMs
                if (posMs >= sStart && posMs < sEnd) { 
                    sIdx = i
                    break 
                }
            }
            if (sIdx == -1 && posMs >= li.endMs) sIdx = li.syllables.size - 1

            val fullColor = accentColor
            val baseColor = Color.LTGRAY

            for (i in li.syllables.indices) {
                val start = li.syllableOffsets.getOrNull(i) ?: 0
                val end = if (i + 1 < li.syllableOffsets.size) li.syllableOffsets[i + 1] else li.text.length
                
                if (i < sIdx) {
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(fullColor), 
                        start, 
                        end, 
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else if (i == sIdx) {
                    val s = li.syllables[i]
                    val progress = if (s.durationMs > 0) {
                        ((posMs - s.startMs).toFloat() / s.durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 1f
                    val blended = blendColors(baseColor, fullColor, progress)
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(blended), 
                        start, 
                        end, 
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 
                        start, 
                        end, 
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            tv.text = spannable
            
            // Also highlight transliteration if present
            val translitTv = transliterationViews.getOrNull(lineIndex)
            if (translitTv != null && !li.transliteration.isNullOrEmpty()) {
                val translitSpannable = android.text.SpannableString(li.transliteration)
                translitSpannable.setSpan(
                    android.text.style.ForegroundColorSpan(Color.GRAY),
                    0,
                    li.transliteration.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                
                // Build transliteration offsets
                var offset = 0
                for (i in li.syllables.indices) {
                    val translitText = li.syllables[i].transliteration ?: ""
                    val translitStart = offset
                    val translitEnd = offset + translitText.length
                    
                    if (i < sIdx) {
                        translitSpannable.setSpan(
                            android.text.style.ForegroundColorSpan(fullColor),
                            translitStart,
                            translitEnd,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    } else if (i == sIdx) {
                        val s = li.syllables[i]
                        val progress = if (s.durationMs > 0) {
                            ((posMs - s.startMs).toFloat() / s.durationMs.toFloat()).coerceIn(0f, 1f)
                        } else 1f
                        val blended = blendColors(Color.GRAY, fullColor, progress)
                        translitSpannable.setSpan(
                            android.text.style.ForegroundColorSpan(blended),
                            translitStart,
                            translitEnd,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        translitSpannable.setSpan(
                            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                            translitStart,
                            translitEnd,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    
                    offset = translitEnd
                }
                
                translitTv.text = translitSpannable
            }
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
                val safe = ensureReadableAccent(color)
                accentColor = safe
                if (focusedMode) setFocusedMode(true) else setFocusedMode(false)
                if (currentIndex >= 0) updatePosition((lines.getOrNull(currentIndex)?.startMs ?: 0L))
            } catch (_: Exception) {}
        }

       fun setShowTransliteration(show: Boolean) {
        showTransliteration = show
        transliterationViews.forEach { tv ->
            val hasContent = !tv.text.isNullOrBlank()
            tv.visibility = if (show && hasContent) VISIBLE else GONE 
        }
        // Force layout refresh
        requestLayout()
    }

    fun setShowTranslation(show: Boolean) {
        showTranslation = show
        translationViews.forEach { tv ->
            val hasContent = !tv.text.isNullOrBlank()
            tv.visibility = if (show && hasContent) VISIBLE else GONE 
        }
        // Force layout refresh
        requestLayout()
    }

        private fun ensureReadableAccent(color: Int): Int {
            try {
                val r = ((color shr 16) and 0xff).toDouble()
                val g = ((color shr 8) and 0xff).toDouble()
                val b = (color and 0xff).toDouble()
                
                fun channel(c: Double): Double {
                    val v = c / 255.0
                    return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
                }
                
                val lum = 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
                
                if (lum < 0.15) {
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