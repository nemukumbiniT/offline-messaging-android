// Message - Basic message model used in adapters.
// Created by Siyabonga Popela Edited by Thanyani Nemukumbini.
// Date: 2025-09-04
package ui.components.models

data class Message(
    val content: String,
    val isSentByUser: Boolean,
    val timestamp: Long
)

