// MessageAdapter - Recycler adapter showing inbound and outbound messages.
// Created by Siyabonga Popela Edited by Thanyani Nemukumbini.
// Date: 2025-09-07
package ui.components.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.nexausingnearby.R
import ui.components.models.Message

class MessageAdapter(
    private val messages: List<Message>
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    inner class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
    }

    // getItemViewType: chooses bubble layout based on whether message is sent by user.

    override fun getItemViewType(position: Int): Int {
        // Siyabonga: delegates bubble alignment to layout choice—keeps onBind lean.
        return if (messages[position].isSentByUser) {
            R.layout.item_message_sent
        } else {
            R.layout.item_message_received
        }
    }

    // onCreateViewHolder: inflates the layout associated with the resolved view type.

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        return MessageViewHolder(view)
    }

    // onBindViewHolder: binds message text into the holder for display.

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.messageText.text = messages[position].content
    }

    // getItemCount: returns number of messages currently displayed.

    override fun getItemCount() = messages.size
}



