package com.morsebt.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val text: String,
    val morse: String,
    val outgoing: Boolean,
    val delivered: Boolean,
    val time: Long = System.currentTimeMillis()
)

class ChatAdapter(
    private val items: List<ChatMessage>,
    private val onTap: (ChatMessage) -> Unit
) : RecyclerView.Adapter<ChatAdapter.VH>() {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.msgText)
        val morse: TextView = v.findViewById(R.id.msgMorse)
        val meta: TextView = v.findViewById(R.id.msgMeta)
        val bubble: View = v.findViewById(R.id.bubble)
    }

    override fun getItemViewType(position: Int) = if (items[position].outgoing) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == 1) R.layout.item_message_out else R.layout.item_message_in
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.text.text = m.text
        holder.morse.text = MorseCode.pretty(m.morse)
        val who = when {
            !m.outgoing -> "Recibido"
            m.delivered -> "Enviado"
            else -> "Solo local"
        }
        holder.meta.text = "$who · ${timeFormat.format(Date(m.time))} · ▶ toca para escuchar"
        holder.bubble.setOnClickListener { onTap(m) }
    }

    override fun getItemCount() = items.size
}
