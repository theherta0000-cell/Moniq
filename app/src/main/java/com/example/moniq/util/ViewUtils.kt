package com.example.moniq.util

import android.view.View

object ViewUtils {
    fun animateVisibility(view: View?, visible: Boolean, duration: Long = 220L) {
        if (view == null) return
        try {
            if (visible) {
                if (view.visibility != View.VISIBLE) {
                    view.alpha = 0f
                    view.visibility = View.VISIBLE
                    view.animate().alpha(1f).setDuration(duration).start()
                }
            } else {
                if (view.visibility == View.VISIBLE) {
                    view.animate().alpha(0f).setDuration(duration).withEndAction {
                        view.visibility = View.GONE
                        view.alpha = 1f
                    }.start()
                }
            }
        } catch (_: Exception) {}
    }
}
