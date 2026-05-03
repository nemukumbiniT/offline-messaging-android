// NexaMessagingService - Foreground service orchestrating messaging notifications and lifecycle callbacks.
// Created by Tasima Hapazari. Edited by Thanyani Nemukumbini.
// Date: 2025-08-21
package com.example.nexausingnearby.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import DataLayer.DatabaseRepository
import DataLayer.models.StoredMessageEntity
import com.asylo.nexa.data.MessageEntity
import com.example.nexausingnearby.Nexa
import com.example.nexausingnearby.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logic.EnhancedMsgHandler
import logic.MessageCenter
import ui.ChatsScreen
import utils.MessagingSecurityMode
import java.util.UUID
import android.os.Binder

class NexaMessagingService : LifecycleService() {

    companion object {
        private const val TAG = "NexaMessagingService"
        private const val CHANNEL_ID = "nexa-messaging"
        private const val NOTIFICATION_ID = 73421

        fun start(context: Context) {
            // Tasima: expose a single entry point so activities do not accidentally spin multiple services.
            val intent = Intent(context.applicationContext, NexaMessagingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Tasima: channel creation lives here so every cold start stays compliant with O+ foreground rules.
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }
    }

    inner class MessagingBinder : Binder() {
        fun getService(): NexaMessagingService = this@NexaMessagingService
    }

    data class MessageDispatch(
        val conversationId: String,
        val entity: MessageEntity
    )

    private val binder = MessagingBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dispatchFlow = MutableSharedFlow<MessageDispatch>(extraBufferCapacity = 64)

    private lateinit var databaseRepository: DatabaseRepository
    private var messageCenter: MessageCenter? = null
    private var enhancedMsgHandler: EnhancedMsgHandler? = null
    private var messageEventsJob: Job? = null
    private var activeConversationId: String? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        databaseRepository = DatabaseRepository(applicationContext)
        ensureMessageCenter()
        startForegroundIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundIfNeeded()
        ensureMessageCenter()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        // Siyabonga: drop collectors when service dies so UI stops observing stale flows.
        messageEventsJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundIfNeeded() {
        if (foregroundStarted) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Tasima: channel creation lives here so every cold start stays compliant with O+ foreground rules.
            val notificationManager = getSystemService(NotificationManager::class.java)
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Background messaging service"
                }
                notificationManager.createNotificationChannel(channel)
            }
        }

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        foregroundStarted = true
    }

    private fun buildNotification(): Notification {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ChatsScreen::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            flags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Messaging service active")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun ensureMessageCenter(): MessageCenter? {
        messageCenter?.let { return it }

        val nexa = Nexa.getInstance()
        val ownerId = resolveOwnerUserId() ?: run {
            Log.w(TAG, "No active user ID available; messaging service idle")
            return null
        }

        val handler = enhancedMsgHandler ?: EnhancedMsgHandler(
            dtnManager = nexa.dtnMessageManager,
            deviceId = nexa.deviceID,
            keyManager = nexa.obtainKeyManager(),
            encryptionService = nexa.obtainEncryptionService()
        ).also { enhancedMsgHandler = it }

        val center = MessageCenter.create(
            // Thanyani: repository + handler build the bridge from DTN to Room; keep this centralized.
            deviceId = nexa.deviceID,
            ownerUserId = ownerId,
            repository = databaseRepository,
            enhancedMsgHandler = handler
        )

        messageCenter = center
        observeMessageEvents(center, nexa.deviceID)
        return center
    }

    private fun observeMessageEvents(center: MessageCenter, deviceId: String) {
        // Siyabonga: drop collectors when service dies so UI stops observing stale flows.
        messageEventsJob?.cancel()
        messageEventsJob = serviceScope.launch {
            // Thanyani: hot shared flow so every consumer sees the update weven if they bind later.
            center.messageEvents.collect { entity ->
                val conversationId = deriveConversationId(deviceId, entity)
                dispatchFlow.emit(MessageDispatch(conversationId, entity))
            }
        }
    }

    private fun deriveConversationId(deviceId: String, entity: MessageEntity): String {
        val sender = entity.senderID.orEmpty()
        val receiver = entity.receiverID.orEmpty()
        return when (deviceId) {
            sender -> receiver
            receiver -> sender
            else -> sender.ifBlank { receiver.ifBlank { UUID.randomUUID().toString() } }
        }
    }

    private fun resolveOwnerUserId(): String? {
        val nexa = Nexa.getInstance()
        nexa.getActiveUserId()?.let { return it }
        nexa.obtainSessionManager().getActiveUserId()?.takeIf { it.isNotBlank() }?.let { return it }
        return runBlocking { databaseRepository.getActiveOwnerUserId() }
    }

    fun messageStream(): SharedFlow<MessageDispatch> = dispatchFlow.asSharedFlow()

    fun setActiveConversation(conversationId: String?) {
        activeConversationId = conversationId
    }

    fun requireTrustedMessaging(): Boolean {
        val mode = Nexa.getInstance().getMessagingSecurityMode()
        return mode == MessagingSecurityMode.REQUIRE_TRUSTED
    }

    fun sendTextMessage(receiverId: String, message: String, requireTrusted: Boolean) {
        val payload = message.toByteArray(Charsets.UTF_8)
        val center = ensureMessageCenter() ?: return
        val handler = enhancedMsgHandler ?: return
        val messageId = center.newLocalMessageId()
        val timestamp = System.currentTimeMillis()
        serviceScope.launch {
            val result = if (requireTrusted) {
                // Tasima: signed messages go through sodium; fallback to open payload when trust not required.
                handler.sendSignedMessage(receiverId, payload)
            } else {
                handler.sendOpenMessage(receiverId, payload)
            }

            if (result != null) {
                center.recordOutgoingMessage(
                    messageId = messageId,
                    receiverId = receiverId,
                    payloadType = 0,
                    content = payload,
                    timestamp = timestamp
                )
            } else {
                Log.w(TAG, "Failed to dispatch message to $receiverId")
            }
        }
    }

    suspend fun getConversationHistory(peerId: String): List<StoredMessageEntity> {
        val center = ensureMessageCenter() ?: return emptyList()
        return center.getConversationHistory(peerId)
    }
}
