// ChatMessage - Model describing message payloads for display.
// Created by Siyabonga Popela Edited by Tasima Hapazari and Thanyani Nemukumbini.
// Date: 2025-09-03
package ui.components.models

data class ChatMessage(
    val sender: String,
    val message: String,
    val isMe: Boolean
)

