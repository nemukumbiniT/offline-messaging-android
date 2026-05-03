// PeerVisibility - Visibility states available for peers.
// Created by Thanyani Nemukumbini.
// Date: 2025-08-18
package DataLayer.models

enum class PeerVisibility {
    OPEN,
    TRUSTED,
    BLOCKED;

    companion object {
        fun fromDatabaseValue(value: String?): PeerVisibility {
            return when (value?.uppercase()) {
                "TRUSTED" -> TRUSTED
                "BLOCKED" -> BLOCKED
                else -> OPEN
            }
        }

        fun toDatabaseValue(visibility: PeerVisibility): String {
            return visibility.name
        }
    }
}

