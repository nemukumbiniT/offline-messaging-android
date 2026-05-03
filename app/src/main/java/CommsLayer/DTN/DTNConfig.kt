// DTNConfig - Defines configuration values for the DTN messaging stack.
// Created by Tasima Hapazari.
// Date: 2025-08-23
package CommsLayer.DTN

object DTNConfig {
    const val MAX_HOPS = 12
    const val MESSAGE_TTL_MS = 3600000L // 1 hour
    const val STORE_AND_FORWARD_TTL_MS = 3600000L
    const val SPRAY_LIMIT = 3 // For spray-and-wait
    const val MAX_RELAY_HOPS = 12
    const val RETRY_INTERVAL_MS = 5000L
    const val MAX_BUFFER_SIZE = 500
    const val RETRY_TIMEOUT_MS = 10000L

    // Message types
    const val TYPE_P2P_MESSAGE = 1
    const val TYPE_GROUP_MESSAGE = 2
    const val TYPE_DTN_BUNDLE = 3
    const val TYPE_GROUP_ANNOUNCEMENT = 4
}

