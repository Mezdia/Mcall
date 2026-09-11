package com.mcall.app.util

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets

/** Edge-to-edge helpers: One UI draws under the status and navigation bars. */
object Insets {

    fun enableEdgeToEdge(activity: Activity) {
        val w = activity.window
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    /** Pads [view] by the top system inset (status bar) so content clears it. */
    fun padTop(view: View) {
        val base = view.paddingTop
        view.setOnApplyWindowInsetsListener { v, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).top
            else @Suppress("DEPRECATION") insets.systemWindowInsetTop
            v.setPadding(v.paddingLeft, base + top, v.paddingRight, v.paddingBottom)
            insets
        }
        view.requestApplyInsets()
    }

    /** Pads [view] by the bottom system inset (navigation bar / gesture area). */
    fun padBottom(view: View) {
        val base = view.paddingBottom
        view.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).bottom
            else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, base + bottom)
            insets
        }
        view.requestApplyInsets()
    }
}
