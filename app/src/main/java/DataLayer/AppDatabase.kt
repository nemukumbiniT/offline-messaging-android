// AppDatabase - Room database setup for core persistence entities.
// Created by Thanyani Nemukumbini.
// Date: 2025-08-17
package DataLayer

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteException
import DataLayer.models.PeerVisibility
import DataLayer.models.StoredMessageEntity
import DataLayer.models.PendingBundleEntity
import java.util.Locale

/**
 * Lightweight SQLite helper that persists local accounts and discovered peers.
 */
class AppDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        private const val DATABASE_NAME = "nexa_local.db"
        private const val DATABASE_VERSION = 8

        private const val TABLE_USERS = "users"
        private const val COLUMN_ID = "id"
        private const val COLUMN_USERNAME = "username"
        private const val COLUMN_EMAIL = "email"
        private const val COLUMN_PASSWORD_HASH = "password_hash"
        private const val COLUMN_PROFILE_PICTURE = "profile_picture"
        private const val COLUMN_CREATED_AT = "created_at"
        private const val COLUMN_LAST_SEEN = "last_seen"
        private const val COLUMN_IS_CURRENT = "is_current"
        private const val COLUMN_DEVICE_INFO = "device_info"

        private const val TABLE_PEERS = "peers"
        private const val COLUMN_PEER_DEVICE_ID = "device_id"
        private const val COLUMN_PEER_DISPLAY_NAME = "display_name"
        private const val COLUMN_PEER_LAST_ENDPOINT = "last_endpoint"
        private const val COLUMN_PEER_LAST_SEEN = "last_seen"
        private const val COLUMN_PEER_VISIBILITY = "visibility"
        private const val COLUMN_PEER_TRUSTED = "trusted"
        private const val COLUMN_PEER_HANDSHAKE_TS = "handshake_ts"
        private const val COLUMN_PEER_PUBLIC_KEY = "public_key"
        private const val COLUMN_PEER_SERVICE_ID = "service_id"
        private const val COLUMN_PEER_IDENTITY_HASH = "identity_hash"
        private const val COLUMN_PEER_OWNER_USER_ID = "owner_user_id"
        private const val COLUMN_PEER_LAST_INTERACTION = "last_interaction"
        private const val COLUMN_PEER_LAST_RELAY_ATTEMPT = "last_relay_attempt"

        private const val TABLE_MESSAGES = "messages"
        private const val COLUMN_MESSAGE_ID = "message_id"
        private const val COLUMN_MESSAGE_SENDER_ID = "sender_id"
        private const val COLUMN_MESSAGE_RECEIVER_ID = "receiver_id"
        private const val COLUMN_MESSAGE_PAYLOAD_TYPE = "payload_type"
        private const val COLUMN_MESSAGE_CONTENT = "content"
        private const val COLUMN_MESSAGE_TIMESTAMP = "timestamp"
        private const val COLUMN_MESSAGE_IS_LOCAL = "is_local"
        private const val COLUMN_MESSAGE_OWNER_USER_ID = "owner_user_id"

        private const val TABLE_DTN_PENDING = "dtn_pending"
        private const val COLUMN_PENDING_BUNDLE_ID = "bundle_id"
        private const val COLUMN_PENDING_SERIALIZED = "serialized_bundle"
        private const val COLUMN_PENDING_TARGET = "target_device_id"
        private const val COLUMN_PENDING_MODE = "mode"
        private const val COLUMN_PENDING_EXPIRY = "expiry_at"
        private const val COLUMN_PENDING_HOP_COUNT = "hop_count"
        private const val COLUMN_PENDING_LAST_ATTEMPT = "last_attempt_at"
        private const val COLUMN_PENDING_ATTEMPTED = "attempted_peers"
        private const val COLUMN_PENDING_CREATED_AT = "created_at"
        private const val MAX_STORED_MESSAGES = 500

        @Volatile
        private var instance: AppDatabase? = null

        // getDatabase: returns the singleton helper, creating it once in a thread-safe manner.
        fun getDatabase(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: AppDatabase(context).also { instance = it }
            }
        }
    }

    // onCreate: builds initial schema for users, peers, messages, and DTN pending tables.
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_USERS (
                $COLUMN_ID TEXT PRIMARY KEY,
                $COLUMN_USERNAME TEXT NOT NULL UNIQUE,
                $COLUMN_EMAIL TEXT NOT NULL,
                $COLUMN_PASSWORD_HASH TEXT NOT NULL,
                $COLUMN_PROFILE_PICTURE BLOB,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_LAST_SEEN INTEGER NOT NULL,
                $COLUMN_IS_CURRENT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_DEVICE_INFO TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_users_username ON $TABLE_USERS($COLUMN_USERNAME)")

        db.execSQL(
            """
            CREATE TABLE $TABLE_PEERS (
                $COLUMN_PEER_DEVICE_ID TEXT PRIMARY KEY,
                $COLUMN_PEER_DISPLAY_NAME TEXT,
                $COLUMN_PEER_LAST_ENDPOINT TEXT,
                $COLUMN_PEER_LAST_SEEN INTEGER NOT NULL,
                $COLUMN_PEER_VISIBILITY TEXT NOT NULL DEFAULT 'OPEN',
                $COLUMN_PEER_HANDSHAKE_TS INTEGER,
                $COLUMN_PEER_PUBLIC_KEY BLOB,
                $COLUMN_PEER_SERVICE_ID TEXT,
                $COLUMN_PEER_IDENTITY_HASH TEXT,
                $COLUMN_PEER_OWNER_USER_ID TEXT NOT NULL,
                $COLUMN_PEER_LAST_INTERACTION INTEGER NOT NULL DEFAULT 0,
                $COLUMN_PEER_LAST_RELAY_ATTEMPT INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_peers_owner_lookup ON $TABLE_PEERS($COLUMN_PEER_OWNER_USER_ID, $COLUMN_PEER_LAST_SEEN)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_peers_owner_interaction ON $TABLE_PEERS($COLUMN_PEER_OWNER_USER_ID, $COLUMN_PEER_LAST_INTERACTION)")

        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGES (
                $COLUMN_MESSAGE_ID TEXT PRIMARY KEY,
                $COLUMN_MESSAGE_SENDER_ID TEXT NOT NULL,
                $COLUMN_MESSAGE_RECEIVER_ID TEXT NOT NULL,
                $COLUMN_MESSAGE_PAYLOAD_TYPE INTEGER NOT NULL,
                $COLUMN_MESSAGE_CONTENT BLOB NOT NULL,
                $COLUMN_MESSAGE_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_MESSAGE_IS_LOCAL INTEGER NOT NULL,
                $COLUMN_MESSAGE_OWNER_USER_ID TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_conversation ON $TABLE_MESSAGES($COLUMN_MESSAGE_SENDER_ID, $COLUMN_MESSAGE_RECEIVER_ID, $COLUMN_MESSAGE_TIMESTAMP)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_owner_scope ON $TABLE_MESSAGES($COLUMN_MESSAGE_OWNER_USER_ID, $COLUMN_MESSAGE_SENDER_ID, $COLUMN_MESSAGE_RECEIVER_ID, $COLUMN_MESSAGE_TIMESTAMP)")
        db.execSQL(
            """
            CREATE TABLE $TABLE_DTN_PENDING (
                $COLUMN_PENDING_BUNDLE_ID TEXT PRIMARY KEY,
                $COLUMN_PENDING_SERIALIZED BLOB NOT NULL,
                $COLUMN_PENDING_TARGET TEXT,
                $COLUMN_PENDING_MODE TEXT NOT NULL,
                $COLUMN_PENDING_EXPIRY INTEGER NOT NULL,
                $COLUMN_PENDING_HOP_COUNT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_PENDING_LAST_ATTEMPT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_PENDING_ATTEMPTED TEXT,
                $COLUMN_PENDING_CREATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dtn_pending_expiry ON $TABLE_DTN_PENDING($COLUMN_PENDING_EXPIRY)")
    }

    // onUpgrade: applies sequential migrations to reach whichever version we shipped.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var currentVersion = oldVersion
        if (currentVersion < 6) {
            migrateToVersion6(db)
            currentVersion = 6
        }
        if (currentVersion < 7) {
            migrateToVersion7(db)
            currentVersion = 7
        }
        if (currentVersion < 8) {
            migrateToVersion8(db)
        }
    }

    // migrateToVersion6: adds DTN pending table introduced in version 6 while preserving data.
    private fun migrateToVersion6(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            addColumnIfMissing(db, TABLE_MESSAGES, COLUMN_MESSAGE_OWNER_USER_ID, "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, TABLE_PEERS, COLUMN_PEER_OWNER_USER_ID, "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, TABLE_PEERS, COLUMN_PEER_LAST_INTERACTION, "INTEGER NOT NULL DEFAULT 0")

            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_owner_scope ON $TABLE_MESSAGES($COLUMN_MESSAGE_OWNER_USER_ID, $COLUMN_MESSAGE_SENDER_ID, $COLUMN_MESSAGE_RECEIVER_ID, $COLUMN_MESSAGE_TIMESTAMP)")
        db.execSQL(
            """
            CREATE TABLE $TABLE_DTN_PENDING (
                $COLUMN_PENDING_BUNDLE_ID TEXT PRIMARY KEY,
                $COLUMN_PENDING_SERIALIZED BLOB NOT NULL,
                $COLUMN_PENDING_TARGET TEXT,
                $COLUMN_PENDING_MODE TEXT NOT NULL,
                $COLUMN_PENDING_EXPIRY INTEGER NOT NULL,
                $COLUMN_PENDING_HOP_COUNT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_PENDING_LAST_ATTEMPT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_PENDING_ATTEMPTED TEXT,
                $COLUMN_PENDING_CREATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dtn_pending_expiry ON $TABLE_DTN_PENDING($COLUMN_PENDING_EXPIRY)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_peers_owner_lookup ON $TABLE_PEERS($COLUMN_PEER_OWNER_USER_ID, $COLUMN_PEER_LAST_SEEN)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_peers_owner_interaction ON $TABLE_PEERS($COLUMN_PEER_OWNER_USER_ID, $COLUMN_PEER_LAST_INTERACTION)")

            backfillLegacyMessageOwners(db)
            backfillLegacyPeerOwners(db)

            db.execSQL(
                """
                UPDATE $TABLE_PEERS
                SET $COLUMN_PEER_LAST_INTERACTION = CASE
                    WHEN $COLUMN_PEER_HANDSHAKE_TS IS NOT NULL AND $COLUMN_PEER_HANDSHAKE_TS > $COLUMN_PEER_LAST_SEEN THEN $COLUMN_PEER_HANDSHAKE_TS
                    ELSE $COLUMN_PEER_LAST_SEEN
                END
                WHERE $COLUMN_PEER_LAST_INTERACTION IS NULL OR $COLUMN_PEER_LAST_INTERACTION = 0
                """.trimIndent()
            )

            db.execSQL("DELETE FROM $TABLE_MESSAGES WHERE $COLUMN_MESSAGE_OWNER_USER_ID IS NULL OR $COLUMN_MESSAGE_OWNER_USER_ID = ''")
            db.execSQL("DELETE FROM $TABLE_PEERS WHERE $COLUMN_PEER_OWNER_USER_ID IS NULL OR $COLUMN_PEER_OWNER_USER_ID = ''")

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // migrateToVersion8: ensures peer catalog carries relay metadata and message table tracks owner id.
    private fun migrateToVersion8(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_DTN_PENDING (
                    $COLUMN_PENDING_BUNDLE_ID TEXT PRIMARY KEY,
                    $COLUMN_PENDING_SERIALIZED BLOB NOT NULL,
                    $COLUMN_PENDING_TARGET TEXT,
                    $COLUMN_PENDING_MODE TEXT NOT NULL,
                    $COLUMN_PENDING_EXPIRY INTEGER NOT NULL,
                    $COLUMN_PENDING_HOP_COUNT INTEGER NOT NULL DEFAULT 0,
                    $COLUMN_PENDING_LAST_ATTEMPT INTEGER NOT NULL DEFAULT 0,
                    $COLUMN_PENDING_ATTEMPTED TEXT,
                    $COLUMN_PENDING_CREATED_AT INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_dtn_pending_expiry ON $TABLE_DTN_PENDING($COLUMN_PENDING_EXPIRY)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // migrateToVersion7: backfills visibility defaults and adds missing peer columns for trust features.
    private fun migrateToVersion7(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            addColumnIfMissing(db, TABLE_PEERS, COLUMN_PEER_VISIBILITY, "TEXT NOT NULL DEFAULT 'OPEN'")
            addColumnIfMissing(db, TABLE_PEERS, COLUMN_PEER_LAST_RELAY_ATTEMPT, "INTEGER NOT NULL DEFAULT 0")

            db.execSQL(
                """
                UPDATE $TABLE_PEERS
                SET $COLUMN_PEER_VISIBILITY = CASE
                    WHEN $COLUMN_PEER_VISIBILITY IS NULL OR $COLUMN_PEER_VISIBILITY = '' THEN
                        CASE WHEN $COLUMN_PEER_TRUSTED = 1 THEN 'TRUSTED' ELSE 'OPEN' END
                    ELSE $COLUMN_PEER_VISIBILITY
                END
                """.trimIndent()
            )

            db.execSQL(
                """
                UPDATE $TABLE_PEERS
                SET $COLUMN_PEER_LAST_RELAY_ATTEMPT = 0
                WHERE $COLUMN_PEER_LAST_RELAY_ATTEMPT IS NULL
                """.trimIndent()
            )

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // addColumnIfMissing: helper that inspects pragma info before issuing ALTER TABLE to add column.
    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, definition: String) {
        if (columnExists(db, table, column)) {
            return
        }
        db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }

    // columnExists: checks pragma table_info to see if a column already exists.
    private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean {
        val cursor = db.rawQuery("PRAGMA table_info($table)", null)
        cursor.use {
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex).equals(column, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    // backfillLegacyMessageOwners: populates owner ids for historical messages so multi-user works.
    private fun backfillLegacyMessageOwners(db: SQLiteDatabase) {
        db.execSQL(
            """
            UPDATE $TABLE_MESSAGES
            SET $COLUMN_MESSAGE_OWNER_USER_ID = (
                SELECT $COLUMN_ID
                FROM $TABLE_USERS
                WHERE $TABLE_USERS.$COLUMN_ID = $TABLE_MESSAGES.$COLUMN_MESSAGE_SENDER_ID
                ORDER BY $COLUMN_IS_CURRENT DESC, $COLUMN_LAST_SEEN DESC
                LIMIT 1
            )
            WHERE ($COLUMN_MESSAGE_OWNER_USER_ID IS NULL OR $COLUMN_MESSAGE_OWNER_USER_ID = '')
              AND EXISTS (
                SELECT 1
                FROM $TABLE_USERS
                WHERE $TABLE_USERS.$COLUMN_ID = $TABLE_MESSAGES.$COLUMN_MESSAGE_SENDER_ID
            )
            """.trimIndent()
        )
    }

    // backfillLegacyPeerOwners: assigns owner ids to older peer rows that predate multi-account support.
    private fun backfillLegacyPeerOwners(db: SQLiteDatabase) {
        db.execSQL(
            """
            UPDATE $TABLE_PEERS
            SET $COLUMN_PEER_OWNER_USER_ID = (
                SELECT $COLUMN_ID
                FROM $TABLE_USERS
                ORDER BY $COLUMN_IS_CURRENT DESC, $COLUMN_LAST_SEEN DESC
                LIMIT 1
            )
            WHERE ($COLUMN_PEER_OWNER_USER_ID IS NULL OR $COLUMN_PEER_OWNER_USER_ID = '')
            """.trimIndent()
        )
    }

    // region User operations
    // insertUser: writes a fresh user row and returns success so caller can react.
    fun insertUser(user: UserEntity): Boolean {
        val values = ContentValues().apply {
            put(COLUMN_ID, user.id)
            put(COLUMN_USERNAME, user.username)
            put(COLUMN_EMAIL, user.email)
            put(COLUMN_PASSWORD_HASH, user.passwordHash)
            put(COLUMN_PROFILE_PICTURE, user.profilePicture)
            put(COLUMN_CREATED_AT, user.createdAt)
            put(COLUMN_LAST_SEEN, user.lastSeen)
            put(COLUMN_IS_CURRENT, if (user.isCurrentUser) 1 else 0)
            put(COLUMN_DEVICE_INFO, user.deviceInfo)
        }
        return writableDatabase.insertWithOnConflict(
            TABLE_USERS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    // updateUser: updates mutable columns on an existing user record.
    fun updateUser(user: UserEntity): Boolean {
        val values = ContentValues().apply {
            put(COLUMN_USERNAME, user.username)
            put(COLUMN_EMAIL, user.email)
            put(COLUMN_PASSWORD_HASH, user.passwordHash)
            put(COLUMN_PROFILE_PICTURE, user.profilePicture)
            put(COLUMN_CREATED_AT, user.createdAt)
            put(COLUMN_LAST_SEEN, user.lastSeen)
            put(COLUMN_IS_CURRENT, if (user.isCurrentUser) 1 else 0)
            put(COLUMN_DEVICE_INFO, user.deviceInfo)
        }
        return writableDatabase.update(
            TABLE_USERS,
            values,
            "$COLUMN_ID = ?",
            arrayOf(user.id)
        ) > 0
    }

    // markCurrentUser: flags the given user as active for quick lookups on launch.
    fun markCurrentUser(userId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(TABLE_USERS, ContentValues().apply { put(COLUMN_IS_CURRENT, 0) }, null, null)
            db.update(
                TABLE_USERS,
                ContentValues().apply { put(COLUMN_IS_CURRENT, 1) },
                "$COLUMN_ID = ?",
                arrayOf(userId)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // clearCurrentUser: resets active flag so logout removes session state.
    fun clearCurrentUser() {
        writableDatabase.update(TABLE_USERS, ContentValues().apply { put(COLUMN_IS_CURRENT, 0) }, null, null)
    }

    // updateLastSeen: stamps the last login time to help UI show activity recency.
    fun updateLastSeen(userId: String, timestamp: Long) {
        writableDatabase.update(
            TABLE_USERS,
            ContentValues().apply { put(COLUMN_LAST_SEEN, timestamp) },
            "$COLUMN_ID = ?",
            arrayOf(userId)
        )
    }

    // findUserByUsername: fetches a user row by username for auth validation.
    fun findUserByUsername(username: String): UserEntity? {
        val cursor = readableDatabase.query(
            TABLE_USERS,
            null,
            "$COLUMN_USERNAME = ?",
            arrayOf(username),
            null,
            null,
            null,
            "1"
        )
        cursor.use {
            return if (it.moveToFirst()) cursorToUser(it) else null
        }
    }

    // findUserById: retrieves a user record via primary key.
    fun findUserById(userId: String): UserEntity? {
        val cursor = readableDatabase.query(
            TABLE_USERS,
            null,
            "$COLUMN_ID = ?",
            arrayOf(userId),
            null,
            null,
            null,
            "1"
        )
        cursor.use {
            return if (it.moveToFirst()) cursorToUser(it) else null
        }
    }

    // getCurrentUser: returns whichever user is flagged as currently active.
    fun getCurrentUser(): UserEntity? {
        val cursor = readableDatabase.query(
            TABLE_USERS,
            null,
            "$COLUMN_IS_CURRENT = 1",
            null,
            null,
            null,
            "$COLUMN_LAST_SEEN DESC",
            "1"
        )
        cursor.use {
            return if (it.moveToFirst()) cursorToUser(it) else null
        }
    }
    // endregion

    // region Peer operations
    // upsertPeer: inserts or updates peer profile information for the current owner.
    fun upsertPeer(
        deviceId: String,
        displayName: String?,
        lastEndpoint: String?,
        lastSeen: Long,
        visibility: PeerVisibility,
        handshakeTimestamp: Long?,
        publicKey: ByteArray?,
        serviceId: String?,
        identityHash: String?,
        ownerUserId: String,
        lastInteraction: Long,
        lastRelayAttempt: Long
    ) {
        val values = ContentValues().apply {
            put(COLUMN_PEER_DEVICE_ID, deviceId)
            put(COLUMN_PEER_DISPLAY_NAME, displayName)
            put(COLUMN_PEER_LAST_ENDPOINT, lastEndpoint)
            put(COLUMN_PEER_LAST_SEEN, lastSeen)
            put(COLUMN_PEER_VISIBILITY, visibility.name)
            put(COLUMN_PEER_HANDSHAKE_TS, handshakeTimestamp)
            put(COLUMN_PEER_PUBLIC_KEY, publicKey)
            put(COLUMN_PEER_SERVICE_ID, serviceId)
            put(COLUMN_PEER_IDENTITY_HASH, identityHash)
            put(COLUMN_PEER_OWNER_USER_ID, ownerUserId)
            put(COLUMN_PEER_LAST_INTERACTION, lastInteraction)
            put(COLUMN_PEER_LAST_RELAY_ATTEMPT, lastRelayAttempt)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_PEERS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // getPeer: looks up a single peer by device id scoped to an owner.
    fun getPeer(deviceId: String, ownerUserId: String): PeerProfile? {
        val cursor = readableDatabase.query(
            TABLE_PEERS,
            null,
            "$COLUMN_PEER_DEVICE_ID = ? AND $COLUMN_PEER_OWNER_USER_ID = ?",
            arrayOf(deviceId, ownerUserId),
            null,
            null,
            null,
            "1"
        )
        cursor.use {
            return if (it.moveToFirst()) cursorToPeer(it) else null
        }
    }

    // getPeers: returns all peers cached for the specified owner id.
    fun getPeers(ownerUserId: String): List<PeerProfile> {
        val cursor = readableDatabase.query(
            TABLE_PEERS,
            null,
            "$COLUMN_PEER_OWNER_USER_ID = ?",
            arrayOf(ownerUserId),
            null,
            null,
            "$COLUMN_PEER_LAST_INTERACTION DESC"
        )
        cursor.use {
            val results = mutableListOf<PeerProfile>()
            while (it.moveToNext()) {
                results.add(cursorToPeer(it))
            }
            return results
        }
    }

    // prunePeersForOwner: trims peer table to a maximum size by removing oldest entries.
    fun prunePeersForOwner(ownerUserId: String, maxCount: Int) {
        val db = writableDatabase
        val sql = """
            DELETE FROM $TABLE_PEERS
            WHERE $COLUMN_PEER_OWNER_USER_ID = ?
              AND $COLUMN_PEER_DEVICE_ID NOT IN (
                SELECT $COLUMN_PEER_DEVICE_ID
                FROM $TABLE_PEERS
                WHERE $COLUMN_PEER_OWNER_USER_ID = ?
                ORDER BY $COLUMN_PEER_LAST_INTERACTION DESC
                LIMIT ?
              )
        """.trimIndent()
        db.execSQL(sql, arrayOf(ownerUserId, ownerUserId, maxCount))
    }
    // endregion

    // region Message operations
    // insertMessage: persists chat message payload for history and returns success status.
    fun insertMessage(message: StoredMessageEntity): Boolean {
        val values = ContentValues().apply {
            put(COLUMN_MESSAGE_ID, message.messageId)
            put(COLUMN_MESSAGE_SENDER_ID, message.senderId)
            put(COLUMN_MESSAGE_RECEIVER_ID, message.receiverId)
            put(COLUMN_MESSAGE_PAYLOAD_TYPE, message.payloadType)
            put(COLUMN_MESSAGE_CONTENT, message.content)
            put(COLUMN_MESSAGE_TIMESTAMP, message.timestamp)
            put(COLUMN_MESSAGE_IS_LOCAL, if (message.isSentByLocalDevice) 1 else 0)
            put(COLUMN_MESSAGE_OWNER_USER_ID, message.ownerUserId)
        }
        return writableDatabase.insertWithOnConflict(
            TABLE_MESSAGES,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L
    }

    // getConversationMessages: fetches stored messages between local device and target peer, optionally limited.
    fun getConversationMessages(
        localDeviceId: String,
        peerDeviceId: String,
        ownerUserId: String,
        limit: Int?
    ): List<StoredMessageEntity> {
        val selection = "((${COLUMN_MESSAGE_SENDER_ID} = ? AND ${COLUMN_MESSAGE_RECEIVER_ID} = ?) OR (${COLUMN_MESSAGE_SENDER_ID} = ? AND ${COLUMN_MESSAGE_RECEIVER_ID} = ?)) AND $COLUMN_MESSAGE_OWNER_USER_ID = ?"
        val args = arrayOf(localDeviceId, peerDeviceId, peerDeviceId, localDeviceId, ownerUserId)
        val limitClause = limit?.takeIf { it > 0 }?.toString()

        val cursor = readableDatabase.query(
            TABLE_MESSAGES,
            null,
            selection,
            args,
            null,
            null,
            "$COLUMN_MESSAGE_TIMESTAMP ASC",
            limitClause
        )

        cursor.use {
            val messages = mutableListOf<StoredMessageEntity>()
            while (it.moveToNext()) {
                messages.add(cursorToStoredMessage(it))
            }
            return messages
        }
    }

    // pruneMessagesForOwner: deletes oldest messages when per-owner store exceeds cap.
    fun pruneMessagesForOwner(ownerUserId: String, localDeviceId: String) {
        val sql = """
            DELETE FROM $TABLE_MESSAGES
            WHERE $COLUMN_MESSAGE_OWNER_USER_ID = ?
              AND $COLUMN_MESSAGE_ID NOT IN (
                  SELECT $COLUMN_MESSAGE_ID
                  FROM $TABLE_MESSAGES
                  WHERE $COLUMN_MESSAGE_OWNER_USER_ID = ?
                    AND ($COLUMN_MESSAGE_SENDER_ID = ? OR $COLUMN_MESSAGE_RECEIVER_ID = ?)
                  ORDER BY $COLUMN_MESSAGE_TIMESTAMP DESC
                  LIMIT $MAX_STORED_MESSAGES
              )
        """.trimIndent()
        writableDatabase.execSQL(sql, arrayOf(ownerUserId, ownerUserId, localDeviceId, localDeviceId))
    }
    // endregion

    // region DTN pending operations
    // upsertPendingBundle: updates or inserts DTN pending record to drive retry logic.
    fun upsertPendingBundle(entity: PendingBundleEntity) {
        val values = ContentValues().apply {
            put(COLUMN_PENDING_BUNDLE_ID, entity.bundleId)
            put(COLUMN_PENDING_SERIALIZED, entity.serializedBundle)
            put(COLUMN_PENDING_TARGET, entity.targetDeviceId)
            put(COLUMN_PENDING_MODE, entity.mode)
            put(COLUMN_PENDING_EXPIRY, entity.expiryAt)
            put(COLUMN_PENDING_HOP_COUNT, entity.hopCount)
            put(COLUMN_PENDING_LAST_ATTEMPT, entity.lastAttemptAt)
            put(COLUMN_PENDING_ATTEMPTED, entity.attemptedPeersCsv)
            put(COLUMN_PENDING_CREATED_AT, entity.createdAt)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_DTN_PENDING,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // getPendingBundles: loads all outstanding bundles waiting for delivery.
    fun getPendingBundles(): List<PendingBundleEntity> {
        val cursor = readableDatabase.query(
            TABLE_DTN_PENDING,
            null,
            null,
            null,
            null,
            null,
            null
        )
        cursor.use {
            val bundles = mutableListOf<PendingBundleEntity>()
            while (it.moveToNext()) {
                bundles.add(cursorToPendingBundle(it))
            }
            return bundles
        }
    }

    // deletePendingBundle: removes pending entry once delivery succeeds or expires.
    fun deletePendingBundle(bundleId: String) {
        writableDatabase.delete(
            TABLE_DTN_PENDING,
            "$COLUMN_PENDING_BUNDLE_ID = ?",
            arrayOf(bundleId)
        )
    }

    // updatePendingBundleAttempt: records attempt metadata so retry scheduler can back off intelligently.
    fun updatePendingBundleAttempt(bundleId: String, attemptedPeersCsv: String, lastAttemptAt: Long, hopCount: Int) {
        val values = ContentValues().apply {
            put(COLUMN_PENDING_ATTEMPTED, attemptedPeersCsv)
            put(COLUMN_PENDING_LAST_ATTEMPT, lastAttemptAt)
            put(COLUMN_PENDING_HOP_COUNT, hopCount)
        }
        writableDatabase.update(
            TABLE_DTN_PENDING,
            values,
            "$COLUMN_PENDING_BUNDLE_ID = ?",
            arrayOf(bundleId)
        )
    }

    // cleanupExpiredPendingBundles: purges pending bundles past expiry and returns count of deletions.
    fun cleanupExpiredPendingBundles(now: Long): Int {
        return writableDatabase.delete(
            TABLE_DTN_PENDING,
            "$COLUMN_PENDING_EXPIRY < ?",
            arrayOf(now.toString())
        )
    }
    // endregion

    // cursorToStoredMessage: maps cursor row into StoredMessageEntity for reuse by callers.
    private fun cursorToStoredMessage(cursor: Cursor): StoredMessageEntity = StoredMessageEntity(
        messageId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE_ID)),
        senderId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE_SENDER_ID)),
        receiverId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE_RECEIVER_ID)),
        payloadType = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE_PAYLOAD_TYPE)),
        content = cursor.getBlob(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE_CONTENT)),
        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE_TIMESTAMP)),
        isSentByLocalDevice = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE_IS_LOCAL)) == 1,
        ownerUserId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE_OWNER_USER_ID))
    )

    // cursorToPendingBundle: builds PendingBundleEntity from serialized row data.
    private fun cursorToPendingBundle(cursor: Cursor): PendingBundleEntity = PendingBundleEntity(
        bundleId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PENDING_BUNDLE_ID)),
        serializedBundle = cursor.getBlob(cursor.getColumnIndexOrThrow(COLUMN_PENDING_SERIALIZED)),
        targetDeviceId = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COLUMN_PENDING_TARGET)),
        mode = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PENDING_MODE)),
        expiryAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_PENDING_EXPIRY)),
        hopCount = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_PENDING_HOP_COUNT)),
        lastAttemptAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_PENDING_LAST_ATTEMPT)),
        attemptedPeersCsv = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COLUMN_PENDING_ATTEMPTED)) ?: "",
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_PENDING_CREATED_AT))
    )

    // cursorToUser: converts cursor columns into UserEntity including optional fields.
    private fun cursorToUser(cursor: Cursor): UserEntity = UserEntity(
        id = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ID)),
        username = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_USERNAME)),
        email = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EMAIL)),
        passwordHash = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PASSWORD_HASH)),
        profilePicture = cursor.getBlobOrNull(cursor.getColumnIndexOrThrow(COLUMN_PROFILE_PICTURE)),
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)),
        lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_LAST_SEEN)),
        isCurrentUser = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_IS_CURRENT)) == 1,
        deviceInfo = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COLUMN_DEVICE_INFO))
    )

    // cursorToPeer: hydrates PeerProfile from row while decoding nullable blobs.
    private fun cursorToPeer(cursor: Cursor): PeerProfile {
        val deviceId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PEER_DEVICE_ID))
        val lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_PEER_LAST_SEEN))
        val handshakeTimestamp = cursor.getLongOrNull(cursor.getColumnIndexOrThrow(COLUMN_PEER_HANDSHAKE_TS))
        val storedInteraction = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_PEER_LAST_INTERACTION))
        val resolvedInteraction = when {
            storedInteraction > 0L -> storedInteraction
            handshakeTimestamp != null -> kotlin.math.max(lastSeen, handshakeTimestamp)
            else -> lastSeen
        }

        val visibilityIndex = cursor.getColumnIndex(COLUMN_PEER_VISIBILITY)
        val visibility = if (visibilityIndex >= 0) {
            val raw = if (cursor.isNull(visibilityIndex)) null else cursor.getString(visibilityIndex)
            PeerVisibility.fromDatabaseValue(raw)
        } else {
            val legacyIndex = cursor.getColumnIndex(COLUMN_PEER_TRUSTED)
            if (legacyIndex >= 0 && cursor.getInt(legacyIndex) == 1) {
                PeerVisibility.TRUSTED
            } else {
                PeerVisibility.OPEN
            }
        }

        val relayIndex = cursor.getColumnIndex(COLUMN_PEER_LAST_RELAY_ATTEMPT)
        val lastRelayAttempt = if (relayIndex >= 0 && !cursor.isNull(relayIndex)) {
            cursor.getLong(relayIndex)
        } else {
            0L
        }

        return PeerProfile(
            deviceId = deviceId,
            displayName = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COLUMN_PEER_DISPLAY_NAME)),
            lastEndpointId = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COLUMN_PEER_LAST_ENDPOINT)),
            lastSeen = lastSeen,
            visibility = visibility,
            handshakeTimestamp = handshakeTimestamp,
            publicKey = cursor.getBlobOrNull(cursor.getColumnIndexOrThrow(COLUMN_PEER_PUBLIC_KEY)),
            serviceId = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COLUMN_PEER_SERVICE_ID)),
            identityHash = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COLUMN_PEER_IDENTITY_HASH)),
            ownerUserId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PEER_OWNER_USER_ID)),
            lastInteraction = resolvedInteraction,
            lastRelayAttempt = lastRelayAttempt
        )
    }

    // Cursor.getBlobOrNull: helper returning blob or null for convenience.
    private fun Cursor.getBlobOrNull(columnIndex: Int): ByteArray? =
        if (isNull(columnIndex)) null else getBlob(columnIndex)

    // Cursor.getStringOrNull: safe string extractor returning null when column is absent.
    private fun Cursor.getStringOrNull(columnIndex: Int): String? =
        if (isNull(columnIndex)) null else getString(columnIndex)

    // Cursor.getLongOrNull: wraps getLong with null handling for missing values.
    private fun Cursor.getLongOrNull(columnIndex: Int): Long? =
        if (isNull(columnIndex)) null else getLong(columnIndex)
}





