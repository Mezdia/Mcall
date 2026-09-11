package com.mcall.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.mcall.app.R

/**
 * One UI 8.5 grouped-list row: 36dp leading icon, 17sp title + 13sp summary,
 * trailing 35dp switch or chevron, minimum 58.5dp height.
 */
class SettingRow @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : LinearLayout(ctx, attrs) {

    enum class Widget { NONE, SWITCH, CHEVRON }

    private val icon: ImageView
    private val title: TextView
    private val summary: TextView
    val switch: Switch
    private val chevron: ImageView

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        minimumHeight = resources.getDimensionPixelSize(R.dimen.row_min_height)
        setPadding(
            resources.getDimensionPixelSize(R.dimen.screen_margin),
            resources.getDimensionPixelSize(R.dimen.row_padding_v),
            resources.getDimensionPixelSize(R.dimen.screen_margin),
            resources.getDimensionPixelSize(R.dimen.row_padding_v)
        )
        background = context.getDrawable(R.drawable.bg_row_ripple)
        LayoutInflater.from(ctx).inflate(R.layout.item_setting, this, true)
        icon = findViewById(R.id.row_icon)
        title = findViewById(R.id.row_title)
        summary = findViewById(R.id.row_summary)
        switch = findViewById(R.id.row_switch)
        chevron = findViewById(R.id.row_chevron)
    }

    fun setIcon(res: Int): SettingRow {
        icon.setImageResource(res)
        icon.visibility = View.VISIBLE
        return this
    }

    fun setTitle(text: CharSequence): SettingRow {
        title.text = text
        return this
    }

    fun setSummary(text: CharSequence?): SettingRow {
        if (text.isNullOrEmpty()) {
            summary.visibility = View.GONE
        } else {
            summary.visibility = View.VISIBLE
            summary.text = text
        }
        return this
    }

    fun setWidget(kind: Widget): SettingRow {
        switch.visibility = if (kind == Widget.SWITCH) View.VISIBLE else View.GONE
        chevron.visibility = if (kind == Widget.CHEVRON) View.VISIBLE else View.GONE
        if (kind == Widget.SWITCH) {
            // Tapping anywhere on the row flips the switch, like Samsung's own settings.
            setOnClickListener { switch.toggle() }
        }
        return this
    }
}
