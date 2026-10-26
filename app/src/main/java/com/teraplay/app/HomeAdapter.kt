package com.teraplay.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

sealed class Row {
    class Header(val title: String) : Row()
    class Video(val e: LibEntry, val progress: Boolean) : Row()
    /** kind: "folder" | "playlist" | "fav" */
    class Group(val title: String, val count: Int, val kind: String, val name: String) : Row()
}

class HomeAdapter(
    private val onVideo: (LibEntry) -> Unit,
    private val onMenu: (View, LibEntry) -> Unit,
    private val onGroup: (Row.Group) -> Unit,
    private val onGroupLong: (Row.Group) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<Row> = emptyList()

    fun submit(r: List<Row>) {
        rows = r
        notifyDataSetChanged()
    }

    fun isFullSpan(position: Int): Boolean = rows.getOrNull(position) !is Row.Video

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> 0
        is Row.Video -> 1
        is Row.Group -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = when (viewType) {
            0 -> R.layout.item_header
            1 -> R.layout.item_video
            else -> R.layout.item_group
        }
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return object : RecyclerView.ViewHolder(v) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is Row.Header -> (holder.itemView as TextView).text = r.title
            is Row.Video -> {
                Ui.bindCard(holder.itemView, r.e, r.progress)
                holder.itemView.setOnClickListener { onVideo(r.e) }
                holder.itemView.findViewById<View?>(R.id.menuBtn)?.setOnClickListener { onMenu(it, r.e) }
            }
            is Row.Group -> {
                val tv = holder.itemView as TextView
                tv.text = S.str(R.string.s045, r.title, r.count)
                tv.setOnClickListener { onGroup(r) }
                tv.setOnLongClickListener {
                    onGroupLong(r)
                    true
                }
            }
        }
    }
}
