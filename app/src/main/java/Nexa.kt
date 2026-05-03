// Nexa - Application entry managing global services and lifecycle.
// Created by Thanyani Nemukumbini. Edited by Tasima Hapazari.
// Date: 2025-08-17
package com.example.nexausingnearby

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import CommsLayer.DTN.DTNMessageManager
import CommsLayer.NearbyService
import DataLayer.DatabaseRepository
import DataLayer.models.PeerVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.libsodium.jni.NaCl
import com.example.nexausingnearby.service.NexaMessagingService
import utils.EncryptionServcice
import utils.KeyManager
import utils.ProfileUtils
import utils.SessionManager
import utils.MessagingSecurityMode
import java.util.UUID

class Nexa : Application() {

    companion object {
        private const val TAG = "NexaApplication"
        private lateinit var instance: Nexa

        fun getInstance(): Nexa = instance

        val currentDeviceID: String
            get() = instance.deviceID

        fun getDTNManagerInstance(): DTNMessageManager = instance.dtnMessageManager

        fun getNearbyServiceInstance(): NearbyService = instance.nearbyService
    }

    private val nearbyServiceId = "com.example.nexausingnearby.SERVICE_ID"

    private var nearbyServiceStarted = false
    private var dtnMessageManagerStarted = false
    private var encryptionInitialized = false
    @Volatile private var messagingServiceStarted = false

    @Volatile private var activeUserId: String? = null

    val deviceID: String by lazy {
        getSharedPreferences("NexaPrefs", MODE_PRIVATE).getString("DEVICE_ID", null)
            ?: UUID.randomUUID().toString().also { generatedId ->
                getSharedPreferences("NexaPrefs", MODE_PRIVATE).edit {
                    putString("DEVICE_ID", generatedId)
                }
                Log.d(TAG, "Generated new device ID: $generatedId")
            }
    }

    val encryptionService: EncryptionServcice by lazy {
        EncryptionServcice().also {
            encryptionInitialized = true
            Log.d(TAG, "Encryption service initialized")
        }
    }

    private val keyManager: KeyManager by lazy {
        KeyManager(applicationContext)
    }

    private val databaseRepository: DatabaseRepository by lazy { DatabaseRepository(applicationContext) }

    private val sessionManager: SessionManager by lazy { SessionManager(applicationContext) }

    private val nearbyService: NearbyService by lazy {
        NearbyService(applicationContext, nearbyServiceId).also {
            Log.d(TAG, "NearbyService created (not started yet)")
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val dtnMessageManager: DTNMessageManager by lazy {
        DTNMessageManager(applicationContext, nearbyService, databaseRepository, deviceID).also {
            Log.d(TAG, "DTNMessageManager created (not started yet)")
        }
    }

    init {
        instance = this
    }

    override fun onCreate() {
        super.onCreate()
        val initialOwnerId = runBlocking { databaseRepository.getActiveOwnerUserId() }
        setActiveUserId(initialOwnerId)
        Log.d(TAG, "Nexa Application created with device ID: $deviceID")
        Log.d(TAG, "Services will be initialized after permissions are granted")
    }

    private fun initializeEncryption() {
        try {
            NaCl.sodium()
            encryptionService
            keyManager.initializeDeviceKeys()
            Log.d(TAG, "Encryption stack ready")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encryption", e)
        }
    }

    fun getDevicePublicKey(): ByteArray? = keyManager.getDevicePublicKey()

    fun getDevicePrivateKey(): ByteArray? = keyManager.getDevicePrivateKey()

    fun obtainKeyManager(): KeyManager = keyManager

    fun obtainEncryptionService(): EncryptionServcice = encryptionService

    fun obtainSessionManager(): SessionManager = sessionManager

    fun getActiveUserId(): String? {
        activeUserId?.let { return it }
        val sessionUser = sessionManager.getActiveUserId()?.takeIf { it.isNotBlank() }
        if (sessionUser != null) {
            activeUserId = sessionUser
            return sessionUser
        }
        return null
    }

    fun setActiveUserId(userId: String?) {
        val normalized = userId?.takeIf { it.isNotBlank() }
        activeUserId = normalized
        sessionManager.setActiveUserId(normalized)
        databaseRepository.setActiveOwnerUserId(normalized)
    }

    private fun resolveDefaultVisibilityRequirement(): PeerVisibility {
        return if (sessionManager.isTrustedMessagingRequired()) {
            PeerVisibility.TRUSTED
        } else {
            PeerVisibility.OPEN
        }
    }

    fun getMessagingSecurityMode(): MessagingSecurityMode = sessionManager.getMessagingSecurityMode()

    fun applyMessagingSecurityMode(mode: MessagingSecurityMode) {
        val requirement = if (mode == MessagingSecurityMode.REQUIRE_TRUSTED) {
            PeerVisibility.TRUSTED
        } else {
            PeerVisibility.OPEN
        }
        dtnMessageManager.updateDefaultVisibilityRequirement(requirement)
    }

    fun updateMessagingSecurityMode(mode: MessagingSecurityMode) {
        sessionManager.setMessagingSecurityMode(mode)
        applyMessagingSecurityMode(mode)
    }

    private fun ensureMessagingServiceStarted() {
        // Tasima: centralised guard so every caller can safely assume the foreground service is running.
        if (!messagingServiceStarted) {
            NexaMessagingService.start(this)
            messagingServiceStarted = true
        }
    }

    fun initializeServicesAfterPermissions() {
        // Thanyani: permissions gate the expensive Nearby spin-up; keep this queue short to avoid blocking UI threads.
        initializeEncryption()
        if (nearbyServiceStarted) {
            Log.d(TAG, "NearbyService already initialized")
            return
        }

        try {
            // Tasima: attach DTN manager before discovery starts to keep callbacks in sync.
            nearbyService.attachDTNManager(dtnMessageManager)
            nearbyServiceStarted = true
            Log.d(TAG, "NearbyService initialized after permissions granted")

            if (!dtnMessageManagerStarted) {
                dtnMessageManager.start()
                dtnMessageManagerStarted = true
                Log.d(TAG, "DTNMessageManager started after permissions granted")
            }

            startNearbyServices()
            ensureMessagingServiceStarted()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing services after permissions", e)
        }
    }

    fun startNearbyServices(deviceName: String = Build.MODEL) {
        // Siyabonga: wraps every entry point that needs active advertising; avoids duplicate start when activity resumes.
        if (!nearbyServiceStarted) {
            // Tasima: bail early if radios are not initialised yet; start() will run after initialiseServicesAfterPermissions.
            Log.w(TAG, "Services not initialized yet. Call initializeServicesAfterPermissions() first.")
            return
        }

        val advertisingName = if (deviceName == Build.MODEL) {
            runBlocking {
                try {
                    databaseRepository.getCurrentUser()?.let { ProfileUtils.buildDisplayName(it) }
                } catch (t: Throwable) {
                    Log.e(TAG, "Unable to determine local display name", t)
                    null
                }
            }?.takeIf { it.isNotBlank() } ?: deviceName
        } else {
            deviceName
        }

        try {
            nearbyService.startAdvertising(
                advertisingName,
                onSuccess = { Log.d(TAG, "Nearby advertising started successfully") },
                onFailure = { exception -> Log.e(TAG, "Failed to start nearby advertising", exception) }
            )

            nearbyService.startDiscovery(
                onSuccess = { Log.d(TAG, "Nearby discovery started successfully") },
                onFailure = { exception -> Log.e(TAG, "Failed to start nearby discovery", exception) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting nearby services", e)
        }
    }
    fun stopNearbyServices() {
        if (!nearbyServiceStarted) {
            // Tasima: bail early if radios are not initialised yet; start() will run after initialiseServicesAfterPermissions.
            return
        }
        try {
            nearbyService.stopAdvertising()
            nearbyService.stopDiscovery()
            Log.d(TAG, "Nearby services stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping nearby services", e)
        }
    }

    fun isSystemReady(): Boolean = encryptionInitialized && nearbyServiceStarted && dtnMessageManagerStarted

    override fun onTerminate() {
        super.onTerminate()
        try {
            if (dtnMessageManagerStarted) {
                dtnMessageManager.stop()
                dtnMessageManagerStarted = false
                Log.d(TAG, "DTNMessageManager stopped")
            }

            if (nearbyServiceStarted) {
                nearbyService.shutdown()
                nearbyServiceStarted = false
                Log.d(TAG, "NearbyService shutdown")
            }

            Log.d(TAG, "Nexa Application terminated")
        } catch (e: Exception) {
            Log.e(TAG, "Error during application termination", e)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "System is low on memory")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.w(TAG, "Memory trim requested, level: $level")
        val ownerId = activeUserId
        if (!ownerId.isNullOrBlank()) {
            appScope.launch {
                runCatching {
                    databaseRepository.cleanupConversationCache(ownerId, deviceID)
                    databaseRepository.cleanupOldPeers(ownerId)
                }.onFailure { Log.w(TAG, "Scoped cache cleanup failed", it) }
            }
        }
    }
}

















