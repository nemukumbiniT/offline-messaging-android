// ProfileUtils - Helpers for profile data formatting and defaults.
// Created by Thanyani Nemukumbini. Edited by Siyabonga Popela.
// Date: 2025-08-24
package utils

import DataLayer.UserEntity
import org.json.JSONObject

/**
 * Helper methods for working with profile metadata stored in [UserEntity].
 */
object ProfileUtils {

    /**
     * Build the preferred display name for a user.
     * First tries the profile names in deviceInfo, then falls back to the username.
     */
    // buildDisplayName: constructs a user-friendly display name from stored profile data.
fun buildDisplayName(user: UserEntity): String {
        // Siyabonga: prefer full name but fall back to username to avoid blank labels.
        val (firstName, lastName) = extractProfileNames(user.deviceInfo)
        val combined = listOfNotNull(firstName, lastName)
            .joinToString(separator = " ")
            .trim()

        return if (combined.isNotEmpty()) combined else user.username
    }

    /**
     * Extract first/last name values from the JSON deviceInfo blob.
     */
    // extractProfileNames: parses stored device info JSON into first and last name values.
fun extractProfileNames(deviceInfo: String?): Pair<String?, String?> {
        if (deviceInfo.isNullOrBlank()) return null to null

        return try {
            val json = JSONObject(deviceInfo)
            val first = json.optString("firstName").takeIf { it.isNotBlank() }
            val last = json.optString("lastName").takeIf { it.isNotBlank() }
            first to last
        } catch (_: Exception) {
            null to null
        }
    }
}



