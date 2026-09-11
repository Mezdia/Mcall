package com.mcall.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.TextView
import com.mcall.app.R
import com.mcall.app.data.Recording
import com.mcall.app.util.directionRes
import com.mcall.app.util.Format

class RecordingAdapter(
    private val ctx: Context,
    private var items: List<Recording>,
    private val onMore: (Recording) -> Unit
) : BaseAdapter() {

    fun update(newItems: List<Recording>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun itemAt(pos: Int): Recording = items[pos]

    override fun getCount(): Int = items.size
    override fun getItem(pos: Int): Any = items[pos]
    override fun getItemId(pos: Int): Long = pos.toLong()

    override fun getView(pos: Int, convert: View?, parent: ViewGroup?): View {
        val v = convert ?: LayoutInflater.from(ctx).inflate(R.layout.item_recording, parent, false)
        val r = items[pos]
        val dirRes = r.directionRes()

        v.findViewById<TextView>(R.id.row_title).text =
            if (r.number.isEmpty()) ctx.getString(dirRes) else r.number
        v.findViewById<TextView>(R.id.row_sub).text =
            "${ctx.getString(dirRes)} · ${Format.time(r.date)}"
        v.findViewById<TextView>(R.id.row_duration).text = Format.size(r.sizeBytes)
        v.findViewById<TextView>(R.id.row_date).text = Format.day(r.date)
        v.findViewById<ImageButton>(R.id.row_more).setOnClickListener { onMore(r) }
        return v
    }
}
