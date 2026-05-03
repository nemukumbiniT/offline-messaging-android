// SessionManager - Persists session and remember-me preferences.
// Created by Thanyani Nemukumbini. Edited by Siyabonga Popela.
// Date: 2025-08-25
package utils

import android.content.Context
import androidx.core.content.edit
import kotlin.jvm.Volatile

/**
 * Simple wrapper around shared preferences to track session and messaging security preferences.
 */
class SessionManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    @Volatile private var activeUserIdCache: String? = prefs.getString(KEY_ACTIVE_USER_ID, null)

    // isRememberMeEnabled: returns whether remember-me preference is currently enabled.
    fun isRememberMeEnabled(): Boolean = prefs.getBoolean(KEY_REMEMBER_ME, true)

    // setRememberMeEnabled: updates remember-me preference flag in secure storage.
    fun setRememberMeEnabled(enabled: Boolean) {
        // Thanyani: mirror checkbox instantly to prefs so login screen reflects intent.
        prefs.edit { putBoolean(KEY_REMEMBER_ME, enabled) }
    }

    // rememberUser: stores the remembered user id for future auto-login attempts.
    fun rememberUser(userId: String) {
        // Siyabonga: storing explicit id lets auto-login skip extra DB lookups.
        prefs.edit { putString(KEY_REMEMBERED_USER_ID, userId) }
    }

    // getRememberedUserId: retrieves the stored remembered user id if any.
    fun getRememberedUserId(): String? = prefs.getString(KEY_REMEMBERED_USER_ID, null)

    // clearRememberedUser: removes remembered user id from storage.
    fun clearRememberedUser() {
        prefs.edit { remove(KEY_REMEMBERED_USER_ID) }
    }

    // setActiveUserId: persists active user id for session tracking.
    fun setActiveUserId(userId: String?) {
        activeUserIdCache = userId?.takeIf { it.isNotBlank() }
        prefs.edit {
            if (activeUserIdCache == null) {
                remove(KEY_ACTIVE_USER_ID)
            } else {
                putString(KEY_ACTIVE_USER_ID, activeUserIdCache)
            }
        }
    }

    // getActiveUserId: fetches currently stored active user id or null.
    fun getActiveUserId(): String? {
        val cached = activeUserIdCache
        if (cached != null) {
            return cached
        }
        val persisted = prefs.getString(KEY_ACTIVE_USER_ID, null)
        activeUserIdCache = persisted
        return persisted
    }

    // shouldAutoLogin: determines if app should attempt automatic login at startup.
    fun shouldAutoLogin(): Boolean = isRememberMeEnabled() && !getRememberedUserId().isNullOrEmpty()

    // clearSession: wipes all session-related keys from preferences.
    fun clearSession() {
        activeUserIdCache = null
        prefs.edit {
            remove(KEY_REMEMBERED_USER_ID)
            remove(KEY_ACTIVE_USER_ID)
            remove(KEY_MESSAGING_SECURITY_MODE)
            putBoolean(KEY_REMEMBER_ME, false)
        }
    }

    // getMessagingSecurityMode: reads the saved messaging security mode.
    fun getMessagingSecurityMode(): MessagingSecurityMode {
        val raw = prefs.getString(KEY_MESSAGING_SECURITY_MODE, MessagingSecurityMode.OPEN.name)
        return MessagingSecurityMode.fromPreference(raw)
    }

    // setMessagingSecurityMode: writes messaging mode preference for later use.
    fun setMessagingSecurityMode(mode: MessagingSecurityMode) {
        prefs.edit { putString(KEY_MESSAGING_SECURITY_MODE, mode.name) }
    }

    // isTrustedMessagingRequired: returns true when trusted messaging mode is active.
    fun isTrustedMessagingRequired(): Boolean =
        getMessagingSecurityMode() == MessagingSecurityMode.REQUIRE_TRUSTED

    companion object {
        private const val PREF_NAME = "nexa_session"
        private const val KEY_REMEMBER_ME = "remember_me"
        private const val KEY_REMEMBERED_USER_ID = "remembered_user_id"
        private const val KEY_ACTIVE_USER_ID = "active_user_id"
        private const val KEY_MESSAGING_SECURITY_MODE = "messaging_security_mode"
    }
}







