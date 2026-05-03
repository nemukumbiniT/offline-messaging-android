// ChatListAdapter - Recycler adapter rendering conversation summaries.
// Created by Siyabonga Popela Edited by Thanyani Nemukumbini.
// Date: 2025-09-06
package ui.components.adapters

import DataLayer.models.PeerVisibility
import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.nexausingnearby.R
import com.google.android.material.chip.Chip
import ui.components.models.Chat

class ChatListAdapter(
    private val chats: List<Chat>,
    private val listener: OnChatClickListener
) : RecyclerView.Adapter<ChatListAdapter.ChatViewHolder>() {

    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvChatName)
        val tvLastMessage: TextView = itemView.findViewById(R.id.tvLastMessage)
        val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        val tvUnreadCount: TextView = itemView.findViewById(R.id.tvUnreadCount)
        val ivStatus: ImageView? = itemView.findViewById(R.id.ivConnectionStatus)
        val chipVisibility: Chip = itemView.findViewById(R.id.chipVisibility)
    }

    interface OnChatClickListener {
        // onChatClick: callback invoked when a chat row is selected.
        fun onChatClick(chat: Chat)
    }

    // onCreateViewHolder: inflates chat list row layout and wraps it in a view holder.

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false)
        return ChatViewHolder(view)
    }

    // onBindViewHolder: binds chat summary details to the provided view holder.

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val chat = chats[position]
        val context = holder.itemView.context

        holder.tvName.text = chat.name

        val lastMessageText = chat.lastMessage?.takeIf { it.isNotBlank() }
            // Siyabonga: fall back to status copy so list never looks empty.
            ?: statusDescription(context, chat)
        holder.tvLastMessage.text = lastMessageText

        holder.tvTime.text = chat.lastTimestamp?.let { timestamp ->
            DateUtils.getRelativeTimeSpanString(
                timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            ).toString()
        } ?: ""

        if (chat.unreadCount > 0) {
            holder.tvUnreadCount.isVisible = true
            holder.tvUnreadCount.text = chat.unreadCount.toString()
        } else {
            holder.tvUnreadCount.isVisible = false
        }

        holder.ivStatus?.isVisible = true
        val statusIcon = if (chat.isConnected) R.drawable.ic_status_connected else R.drawable.ic_status_disconnected
        holder.ivStatus?.setImageResource(statusIcon)

        holder.chipVisibility.isVisible = true
        holder.chipVisibility.text = visibilityLabel(context, chat.visibility)
        // Thanyani: chips echo settings screen colours so state stays consistent.
        val (background, foreground) = visibilityColors(context, chat.visibility)
        holder.chipVisibility.chipBackgroundColor = ColorStateList.valueOf(background)
        holder.chipVisibility.setTextColor(foreground)

        holder.itemView.setOnClickListener { listener.onChatClick(chat) }
    }

    // getItemCount: reports total chat entries available for recycler.

    override fun getItemCount(): Int = chats.size

    companion object {
        private fun statusDescription(context: android.content.Context, chat: Chat): String {
            return when {
                chat.isConnected -> context.getString(R.string.peer_status_connected)
                chat.lastSeen != null -> {
                    val relative = DateUtils.getRelativeTimeSpanString(
                        chat.lastSeen,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE
                    )
                    context.getString(R.string.peer_status_last_seen, relative)
                }
                else -> context.getString(R.string.chat_no_messages_placeholder)
            }
        }

        private fun visibilityLabel(context: android.content.Context, visibility: PeerVisibility): String {
            return when (visibility) {
                PeerVisibility.TRUSTED -> context.getString(R.string.peer_status_trusted)
                PeerVisibility.BLOCKED -> context.getString(R.string.peer_status_blocked)
                PeerVisibility.OPEN -> context.getString(R.string.peer_status_open)
            }
        }

        private fun visibilityColors(
            context: android.content.Context,
            visibility: PeerVisibility
        ): Pair<Int, Int> {
            val backgroundRes = when (visibility) {
                PeerVisibility.TRUSTED -> R.color.visibility_trusted_bg
                PeerVisibility.OPEN -> R.color.visibility_open_bg
                PeerVisibility.BLOCKED -> R.color.visibility_blocked_bg
            }
            val foregroundRes = when (visibility) {
                PeerVisibility.TRUSTED -> R.color.visibility_trusted_fg
                PeerVisibility.OPEN -> R.color.visibility_open_fg
                PeerVisibility.BLOCKED -> R.color.visibility_blocked_fg
            }
            return ContextCompat.getColor(context, backgroundRes) to
                ContextCompat.getColor(context, foregroundRes)
        }
    }
}



