// NearbyPeerItem - Model encapsulating peer discovery attributes.
// Created by Siyabonga Popela Edited by Tasima Hapazari.
// Date: 2025-09-02
package ui.components.models

import DataLayer.models.PeerVisibility

data class NearbyPeerItem(
    val endpointId: String?,
    val deviceId: String?,
    val displayName: String,
    val details: String?,
    val isConnected: Boolean,
    val visibility: PeerVisibility,
    val lastSeen: Long?,
    val isDiscoveredOnly: Boolean
)

