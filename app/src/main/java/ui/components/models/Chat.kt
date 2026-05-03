// Chat - Model representing a conversation preview for the UI.
// Created by Siyabonga Popela Edited by Thanyani Nemukumbini.
// Date: 2025-09-03
package ui.components.models

import DataLayer.models.PeerVisibility

data class Chat(
    val deviceId: String,
    val name: String,
    val lastMessage: String?,
    val lastTimestamp: Long?,
    val unreadCount: Int,
    val isConnected: Boolean,
    val visibility: PeerVisibility,
    val lastSeen: Long?
)

