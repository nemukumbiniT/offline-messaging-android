// MessagingSecurityMode - Enum defining messaging trust levels.
// Created by Thanyani Nemukumbini.
// Date: 2025-08-23
package utils

enum class MessagingSecurityMode {
    OPEN,
    REQUIRE_TRUSTED;

    companion object {
        fun fromPreference(raw: String?): MessagingSecurityMode {
            if (raw.isNullOrBlank()) {
                return OPEN
            }
            return runCatching { valueOf(raw) }.getOrElse { OPEN }
        }
    }
}

