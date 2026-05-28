package com.junior.assistant.ui.main

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.junior.assistant.model.ChatMessage
import com.junior.assistant.model.SenderType

class ChatAdapter(private val messages: List<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(val layout: FrameLayout, val textVal: TextView, val labelVal: TextView) :
        RecyclerView.ViewHolder(layout)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val context = parent.context
        val rootLayout = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(16, 6, 16, 6)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }

        val label = TextView(context).apply {
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 4)
        }

        val messageText = TextView(context).apply {
            textSize = 14f
            setLineSpacing(4f, 1.1f)
        }

        container.addView(label)
        container.addView(messageText)
        rootLayout.addView(container)

        return ChatViewHolder(rootLayout, messageText, label)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val msg = messages[position]
        holder.textVal.text = msg.text

        val container = holder.textVal.parent as LinearLayout
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            // Margin around bubbles
            leftMargin = 16
            rightMargin = 16
        }

        // Apply a premium programmatic styled drawable with rounded corners and a border
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 32f // Soft, modern curved corners
            if (msg.sender == SenderType.USER) {
                setColor(0xFF18181B.toInt()) // Zinc-900 Elegant Dark background
                setStroke(2, 0x33A1A1AA.toInt()) // Subtle zinc-400 borders
            } else {
                setColor(0xFF0F050A.toInt()) // Very deep magenta-crimson shadow
                setStroke(2, 0x33FF1744.toInt()) // Glowing red border line
            }
        }
        container.background = drawable

        if (msg.sender == SenderType.USER) {
            holder.labelVal.text = "USER"
            holder.labelVal.setTextColor(0xFFFF1744.toInt()) // Bright crimson-red
            holder.textVal.setTextColor(0xFFD4D4D8.toInt()) // Zinc-300 text
            holder.textVal.setTypeface(null, Typeface.NORMAL)
            params.gravity = Gravity.END
            container.layoutParams = params
        } else {
            holder.labelVal.text = "JUNIOR"
            holder.labelVal.setTextColor(0xFFE040FB.toInt()) // Bright magenta
            holder.textVal.setTextColor(0xFFFFFFFF.toInt()) // Pure white
            holder.textVal.setTypeface(null, Typeface.ITALIC) // Elegant conversational italic markup
            params.gravity = Gravity.START
            container.layoutParams = params
        }
    }

    override fun getItemCount(): Int = messages.size
}
