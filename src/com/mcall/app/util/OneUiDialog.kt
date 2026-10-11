package com.mcall.app.util

import android.app.Dialog
import android.content.Context
import android.view.View
import android.view.WindowManager
import com.mcall.app.R

/** One UI dialog: 26dp radius surface, 320dp wide, transparent window, dimmed scrim. */
object OneUiDialog {

    fun show(
        ctx: Context,
        layoutRes: Int,
        widthDp: Int = 320,
        theme: Int = R.style.Theme_Mcall_Dialog,
        configure: (Dialog) -> Unit
    ): Dialog {
        val dialog = Dialog(ctx, theme)
        dialog.setContentView(layoutRes)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(dp(ctx, widthDp), WindowManager.LayoutParams.WRAP_CONTENT)
        configure(dialog)
        return dialog
    }

    fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()
}

/** Convenience for reading a child of a dialog's content view. */
fun Dialog.view(id: Int): View = findViewById(id)
