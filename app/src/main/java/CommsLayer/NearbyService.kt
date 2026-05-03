// NearbyService - Manages Nearby peer discovery, connections, and data transport.
// Created by Tasima Hapazari. Edited by Thanyani Nemukumbini.
// Date: 2025-08-22
package CommsLayer

import CommsLayer.DTN.DTNMessageManager
import DataLayer.PeerEntity
import DataLayer.PeerProfile
import DataLayer.models.PeerVisibility
import DataLayer.DatabaseRepository
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.annotation.MainThread
import com.google.android.gms.common.api.ApiException
import com.example.nexausingnearby.Nexa
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import utils.KeyManager
import utils.ProfileUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Central wrapper around Nearby Connections. Handles discovery, connection lifecycle,
 * identity handshake, and acts as a bridge between endpoint IDs (Nearby) and
 * long-lived device IDs used throughout the mesh/DTN layers.
 */
class NearbyService(
    private val context: Context,
    private val serviceId: String
) {

    private companion object {
        private const val TAG = "NearbyService"
        private const val HANDSHAKE_PREFIX = "NEXA_HANDSHAKE|"
        private const val HEARTBEAT_PREFIX = "NEXA_HEARTBEAT|"
        private const val KEEP_PEER_MS = 5 * 60_000L
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val MIN_TOUCH_UPDATE_MS = 1_000L
    }

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val strategy = Strategy.P2P_CLUSTER
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyManager = KeyManager(context)
    private val databaseRepository by lazy { DatabaseRepository(context) }

    private val endpointPeers = mutableMapOf<String, PeerEntity>()
    private val endpointToDevice = mutableMapOf<String, String>()
    private val deviceToEndpoint = mutableMapOf<String, String>()
    private val connectedEndpoints = mutableSetOf<String>()

    @Volatile private var cachedLocalDisplayName: String? = null
    @Volatile private var currentAdvertisingName: String? = null

    @Volatile private var isAdvertising = false
    @Volatile private var isDiscovering = false
    private var heartbeatJob: Job? = null

    private val peerCatalog = ConcurrentHashMap<String, PeerProfile>()
    @Volatile private var cachedOwnerUserId: String? = null
    private val _peerContacts = MutableStateFlow<Map<String, PeerProfile>>(emptyMap())
    val peerContacts: StateFlow<Map<String, PeerProfile>> = _peerContacts
    @Deprecated("Use peerContacts instead")
    val trustedContacts: StateFlow<Map<String, PeerProfile>> = peerContacts

    private val _discoveredPeers = MutableStateFlow<Map<String, PeerEntity>>(emptyMap())
    val discoveredPeers: StateFlow<Map<String, PeerEntity>> = _discoveredPeers

    private var dtnMessageManager: DTNMessageManager? = null
    @Volatile var onHandshakeCompleted: ((peerDeviceId: String, timestamp: Long) -> Unit)? = null

    init {
        scope.launch {
            cachedLocalDisplayName = resolveLocalDisplayName()
            preloadPersistedPeers()
        }
    }


    private suspend fun resolveOwnerUserId(): String? {
        val appOwnerId = Nexa.getInstance().getActiveUserId()
        val resolved = appOwnerId ?: databaseRepository.getActiveOwnerUserId()
        if (resolved != cachedOwnerUserId) {
            cachedOwnerUserId = resolved
            peerCatalog.clear()
            refreshPeerContactsState()
            resolved?.let { loadPeersForOwner(it) }
        }
        return resolved
    }

    // showToast: marshals toast display onto main thread to keep UX responsive during background operations.
    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        mainHandler.post {
            Toast.makeText(context.applicationContext, message, duration).show()
        }
    }

    // endpointLabel: builds human-friendly label combining endpoint and cached device id.
    private fun endpointLabel(endpointId: String): String {
        val peer = endpointPeers[endpointId]
        return peer?.deviceDisplayName
            ?: peer?.endpointName
            ?: endpointId
    }

    private val deviceId: String get() = Nexa.currentDeviceID

    private val deviceDisplayName: String get() = cachedLocalDisplayName ?: Build.MODEL

    /** Attach the DTN manager so payloads can be delegated once the service is ready. */
    // attachDTNManager: wires DTN bridge so routing callbacks can leverage Nearby events.
    fun attachDTNManager(manager: DTNMessageManager) {
        // Tasima: called once post-permission; DTN manager drives long-lived routing decisions.
        dtnMessageManager = manager
        syncPeerCatalogWithDtn()
    }

    // ---------- Nearby callbacks ----------

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Endpoint found: $endpointId (${info.endpointName})")
            val peer = PeerEntity(
                endpointId = endpointId,
                endpointName = info.endpointName,
                serviceId = serviceId
            )
            updatePeer(peer)
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Endpoint lost: $endpointId")
            removePeer(endpointId)
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.d(TAG, "Connection initiated from $endpointId (${connectionInfo.endpointName}). Auto-accepting.")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.d(TAG, "Connected: $endpointId")
                    connectedEndpoints.add(endpointId)
                    touchPeer(endpointId)
                    ensureHeartbeatLoop()
                    sendHandshake(endpointId)
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.w(TAG, "Connection rejected: $endpointId")
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    Log.e(TAG, "Connection error: $endpointId -> ${result.status}")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected: $endpointId")
            connectedEndpoints.remove(endpointId)
            val deviceId = endpointToDevice.remove(endpointId)
            if (deviceId != null) {
                deviceToEndpoint.remove(deviceId)
            }
            endpointPeers.remove(endpointId)
            pushPeerUpdate()
            cancelHeartbeatLoopIfIdle()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            Log.d(TAG, "Payload received from $endpointId, type=${payload.type}")
            if (payload.type != Payload.Type.BYTES) {
                Log.w(TAG, "Unsupported payload type from $endpointId: ${payload.type}")
                return
            }

            touchPeer(endpointId)

            val bytes = payload.asBytes() ?: return
            val text = bytes.toString(Charsets.UTF_8)
            when {
                text.startsWith(HANDSHAKE_PREFIX) -> {
                    handleHandshake(endpointId, text.removePrefix(HANDSHAKE_PREFIX))
                    return
                }
                text.startsWith(HEARTBEAT_PREFIX) -> {
                    handleHeartbeat(endpointId, text.removePrefix(HEARTBEAT_PREFIX))
                    return
                }
            }

            val manager = dtnMessageManager
            if (manager != null) {
                scope.launch {
                    try {
                        manager.handleIncomingPayload(endpointId, payload)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to delegate payload to DTN layer", t)
                    }
                }
            } else {
                Log.w(TAG, "DTN manager not attached; dropping payload from $endpointId")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            Log.v(TAG, "Payload transfer update from $endpointId: $update")
        }
    }

    // ---------- Public API ----------

    @MainThread
    // startAdvertising: kicks off Nearby advertising with adaptive strategy and updates local catalog.
    fun startAdvertising(
        deviceName: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        if (isAdvertising) {
            if (currentAdvertisingName == deviceName) {
                Log.d(TAG, "Advertising already active as $deviceName")
                onSuccess?.invoke()
                return
            }
            Log.d(TAG, "Restarting advertising with updated name: $deviceName")
            stopAdvertising()
        }

        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(deviceName, serviceId, lifecycleCallback, options)
            .addOnSuccessListener(executor) {
                Log.d(TAG, "Advertising started as $deviceName")
                isAdvertising = true
                currentAdvertisingName = deviceName
                onSuccess?.invoke()
            }
            .addOnFailureListener(executor) { e ->
                val apiException = e as? ApiException
                if (apiException?.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) {
                    Log.w(TAG, "Already advertising; updating cached name to $deviceName")
                    isAdvertising = true
                    currentAdvertisingName = deviceName
                    onSuccess?.invoke()
                } else {
                    Log.e(TAG, "Advertising failed", e)
                    isAdvertising = false
                    currentAdvertisingName = null
                    onFailure?.invoke(Exception(e))
                }
            }
    }
@MainThread
    // stopAdvertising: halts advertising and clears handles to keep battery usage low.
    fun stopAdvertising() {
        if (!isAdvertising) {
            Log.d(TAG, "Advertising already stopped")
            return
        }
        connectionsClient.stopAdvertising()
        isAdvertising = false
        currentAdvertisingName = null
        Log.d(TAG, "Stopped advertising")
    }

    // refreshLocalDisplayName: pushes latest profile name to Nearby so peers see current identity.
    fun refreshLocalDisplayName() {
        cachedLocalDisplayName = null
        scope.launch {
            val latestName = resolveLocalDisplayName()
            if (isAdvertising && currentAdvertisingName != latestName) {
                mainHandler.post {
                    if (isAdvertising) {
                        stopAdvertising()
                    }
                    startAdvertising(latestName)
                }
            }
            val endpoints = synchronized(connectedEndpoints) { connectedEndpoints.toList() }
            endpoints.forEach { sendHandshake(it) }
        }
    }
@MainThread
    // startDiscovery: initiates active scanning and registers discovery callbacks.
    fun startDiscovery(
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        if (isDiscovering) {
            Log.d(TAG, "Discovery already active")
            onSuccess?.invoke()
            return
        }

        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(serviceId, discoveryCallback, options)
            .addOnSuccessListener(executor) {
                Log.d(TAG, "Discovery started")
                isDiscovering = true
                onSuccess?.invoke()
            }
            .addOnFailureListener(executor) { e ->
                val apiException = e as? ApiException
                if (apiException?.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) {
                    Log.w(TAG, "Already discovering; treating as success")
                    isDiscovering = true
                    onSuccess?.invoke()
                } else {
                    Log.e(TAG, "Discovery failed", e)
                    isDiscovering = false
                    onFailure?.invoke(Exception(e))
                    showToast("Discovery failed: ${e.localizedMessage ?: e::class.java.simpleName}", Toast.LENGTH_LONG)
                }
            }
    }

    @MainThread
    // stopDiscovery: cancels discovery session to avoid duplicate results or battery drain.
    fun stopDiscovery() {
        if (!isDiscovering) {
            Log.d(TAG, "Discovery already stopped")
            return
        }
        connectionsClient.stopDiscovery()
        isDiscovering = false
        Log.d(TAG, "Stopped discovery")
    }

    @MainThread
    // requestConnection: sends Nearby connection request with friendly local name.
    fun requestConnection(endpointId: String, localEndpointName: String = Build.MODEL) {
        Log.d(TAG, "Requesting connection to $endpointId")
        connectionsClient.requestConnection(localEndpointName, endpointId, lifecycleCallback)
            .addOnSuccessListener(executor) {
                Log.d(TAG, "Connection request sent to $endpointId")
                showToast("Connection request sent to ${endpointLabel(endpointId)}")
            }
            .addOnFailureListener(executor) { e ->
                Log.e(TAG, "Failed to request connection to $endpointId", e)
                showToast("Connection request to ${endpointLabel(endpointId)} failed: ${e.localizedMessage ?: e::class.java.simpleName}", Toast.LENGTH_LONG)
            }
    }

    // disconnectFromEndpoint: drops active connection and scrubs tracking maps.
    fun disconnectFromEndpoint(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
        lifecycleCallback.onDisconnected(endpointId)
    }

    // shutdown: stops discovery/advertising and clears scheduled work for app exit.
    fun shutdown() {
        stopAdvertising()
        stopDiscovery()
        heartbeatJob?.cancel()
        heartbeatJob = null
        connectedEndpoints.toList().forEach { connectionsClient.disconnectFromEndpoint(it) }
        connectedEndpoints.clear()
        endpointPeers.clear()
        endpointToDevice.clear()
        deviceToEndpoint.clear()
        _discoveredPeers.value = emptyMap()
        scope.cancel()
        executor.shutdown()
    }

    // ---------- Device/endpoint helpers ----------

    // isDeviceConnected: verifies mapping contains active endpoint for given device id.
    fun isDeviceConnected(deviceId: String): Boolean {
        val endpoint = deviceToEndpoint[deviceId] ?: return false
        return connectedEndpoints.contains(endpoint)
    }

    // isEndpointConnected: quick check for active endpoint membership.
    fun isEndpointConnected(endpointId: String): Boolean = connectedEndpoints.contains(endpointId)

    // getConnectedDeviceIds: returns list of devices with healthy connections for UI/reporting.
    fun getConnectedDeviceIds(): List<String> = deviceToEndpoint.keys.filter { isDeviceConnected(it) }

    // resolveDeviceId: maps endpoint back to long-lived device identifier.
    fun resolveDeviceId(endpointId: String): String? = endpointToDevice[endpointId]

    // resolveEndpoint: converts device id to current Nearby endpoint if present.
    fun resolveEndpoint(deviceId: String): String? = deviceToEndpoint[deviceId]

    suspend fun ensureActiveConnection(deviceId: String, timeoutMs: Long = 7_000L): Boolean {
        if (isDeviceConnected(deviceId)) {
            return true
        }

        val candidateEndpoints = LinkedHashSet<String>()
        resolveEndpoint(deviceId)?.let { candidateEndpoints.add(it) }

        synchronized(endpointPeers) {
            endpointPeers.entries.firstOrNull { it.value.deviceId == deviceId }?.key?.let { candidateEndpoints.add(it) }
        }

        if (candidateEndpoints.isEmpty()) {
            val ownerId = resolveOwnerUserId()
            if (ownerId != null) {
                try {
                    databaseRepository.getPeerProfile(deviceId, ownerId)?.lastEndpointId?.let { candidateEndpoints.add(it) }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to lookup peer $deviceId for reconnection", t)
                }
            }
        }

        if (candidateEndpoints.isEmpty()) {
            peerCatalog[deviceId]?.lastEndpointId?.let { candidateEndpoints.add(it) }
        }

        if (candidateEndpoints.isEmpty()) {
            Log.v(TAG, "No endpoint candidates available for $deviceId; cannot ensure connection")
            return false
        }

        for (endpoint in candidateEndpoints) {
            withContext(Dispatchers.Main) {
                try {
                    requestConnection(endpoint)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request connection to $endpoint", e)
                }
            }

            if (awaitConnection(deviceId, timeoutMs)) {
                return true
            }
        }

        return isDeviceConnected(deviceId)
    }

    suspend fun sendPayloadToDevice(deviceId: String, payload: Payload): Boolean {
        val endpointId = resolveEndpoint(deviceId)
        if (endpointId == null) {
            Log.w(TAG, "No active endpoint mapping for device $deviceId")
            return false
        }
        return sendPayloadInternal(endpointId, payload)
    }

    suspend fun sendPayloadToEndpoint(endpointId: String, payload: Payload): Boolean {
        return sendPayloadInternal(endpointId, payload)
    }

    // ---------- Internal helpers ----------

    private suspend fun sendPayloadInternal(endpointId: String, payload: Payload): Boolean {
        val sent = try {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    connectionsClient.sendPayload(endpointId, payload)
                        .addOnSuccessListener { cont.resume(true) }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to send payload to $endpointId", e)
                            cont.resume(false)
                        }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending payload to $endpointId", e)
            false
        }
        if (sent) {
            touchPeer(endpointId)
        }
        return sent
    }

    // touchPeer: refreshes last-seen timestamp so purge/heartbeat logic stays accurate.
    private fun touchPeer(endpointId: String, timestamp: Long = System.currentTimeMillis()) {
        val peer = endpointPeers[endpointId] ?: return
        if (timestamp - peer.lastSeenMillis < MIN_TOUCH_UPDATE_MS) {
            return
        }
        endpointPeers[endpointId] = peer.touch(timestamp)
        pushPeerUpdate()
    }

    // updatePeer: merge incoming peer data into catalog and notify observers.
    private fun updatePeer(peer: PeerEntity) {
        val existing = endpointPeers[peer.endpointId]
        val merged = if (existing != null) {
            existing.copy(
                endpointName = peer.endpointName.takeUnless { it.isBlank() } ?: existing.endpointName,
                serviceId = peer.serviceId,
                deviceId = peer.deviceId ?: existing.deviceId,
                deviceDisplayName = peer.deviceDisplayName ?: existing.deviceDisplayName,
                publicKey = peer.publicKey ?: existing.publicKey
            )
        } else {
            peer
        }

        endpointPeers[peer.endpointId] = merged.touch()
        pushPeerUpdate()
        schedulePurge()
    }

    // removePeer: cleans caches when a connection drops to prevent stale listings.
    private fun removePeer(endpointId: String) {
        endpointPeers.remove(endpointId)
        val deviceId = endpointToDevice.remove(endpointId)
        if (deviceId != null) {
            deviceToEndpoint.remove(deviceId)
            dtnMessageManager?.removePeer(deviceId)
        }
        pushPeerUpdate()
    }

    // pushPeerUpdate: emits current peer snapshot through state flow for UI consumption.
    private fun pushPeerUpdate() {
        _discoveredPeers.value = endpointPeers.toMap()
    }

    private suspend fun preloadPersistedPeers() {
        try {
            val ownerId = resolveOwnerUserId() ?: return
            loadPeersForOwner(ownerId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to preload peers", e)
        }
    }

    private suspend fun loadPeersForOwner(ownerId: String) {
        val persisted = databaseRepository.getPeerProfiles(ownerId)
        peerCatalog.clear()
        persisted.filter { it.ownerUserId == ownerId }.forEach { profile ->
            peerCatalog[profile.deviceId] = profile
        }
        refreshPeerContactsState()
        syncPeerCatalogWithDtn()
    }
    // upsertPeerCatalog: persists profile changes to the DB so state survives restarts.
    private fun upsertPeerCatalog(profile: PeerProfile) {
        val ownerId = cachedOwnerUserId
        if (ownerId != null && profile.ownerUserId == ownerId) {
            peerCatalog[profile.deviceId] = profile
            refreshPeerContactsState()
            notifyDtnAboutPeer(profile)
        }
    }

    // refreshPeerContactsState: recomputes derived contact lists to feed chips and filters.
    private fun refreshPeerContactsState() {
        _peerContacts.value = peerCatalog.toMap()
    }

    // syncPeerCatalogWithDtn: hands peer roster to DTN layer so routing reflects latest connections.
    private fun syncPeerCatalogWithDtn() {
        val manager = dtnMessageManager ?: return
        if (peerCatalog.isEmpty()) {
            manager.replacePeerVisibilities(emptyMap())
            return
        }
        val snapshot = peerCatalog.mapValues { it.value.visibility }
        manager.replacePeerVisibilities(snapshot)
    }

    // notifyDtnAboutPeer: informs DTN manager about peer visibility + endpoint bindings.
    private fun notifyDtnAboutPeer(profile: PeerProfile) {
        val manager = dtnMessageManager ?: return
        manager.updatePeerVisibility(profile.deviceId, profile.visibility)
    }

    private var purgeJobLaunched = false
    // schedulePurge: sets up periodic purge job to clean stale peers.
    private fun schedulePurge() {
        if (purgeJobLaunched) return
        purgeJobLaunched = true
        scope.launch {
            try {
                while (true) {
                    delay(5_000)
                    purgeStalePeers()
                }
            } finally {
                purgeJobLaunched = false
            }
        }
    }

    // ensureHeartbeatLoop: starts heartbeat coroutine to keep link liveness tracked.
    private fun ensureHeartbeatLoop() {
        if (heartbeatJob?.isActive == true) {
            return
        }
        heartbeatJob = scope.launch {
            try {
                Log.d(TAG, "Heartbeat loop started")
                while (isActive) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    val endpoints = connectedEndpoints.toList()
                    if (endpoints.isEmpty()) {
                        continue
                    }
                    for (endpointId in endpoints) {
                        if (!connectedEndpoints.contains(endpointId)) {
                            continue
                        }
                        val success = sendPayloadInternal(
                            endpointId,
                            Payload.fromBytes(buildHeartbeatPayload())
                        )
                        if (!success) {
                            Log.v(TAG, "Heartbeat send failed to $endpointId")
                        }
                    }
                }
            } catch (c: CancellationException) {
                Log.v(TAG, "Heartbeat loop cancelled")
            } catch (t: Throwable) {
                Log.e(TAG, "Heartbeat loop terminated unexpectedly", t)
            } finally {
                Log.d(TAG, "Heartbeat loop stopped")
                heartbeatJob = null
            }
        }
    }

    // cancelHeartbeatLoopIfIdle: stops heartbeat timer when no peers require monitoring.
    private fun cancelHeartbeatLoopIfIdle() {
        if (connectedEndpoints.isNotEmpty()) {
            return
        }
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private suspend fun awaitConnection(deviceId: String, timeoutMs: Long): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            while (!isDeviceConnected(deviceId)) {
                delay(200)
            }
            true
        } ?: false
    }

    // purgeStalePeers: removes peers that have not reported in within the retention window.
    private fun purgeStalePeers() {
        val now = System.currentTimeMillis()
        val iterator = endpointPeers.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val endpointId = entry.key
            val peer = entry.value
            if (connectedEndpoints.contains(endpointId)) {
                continue
            }
            if (now - peer.lastSeenMillis > KEEP_PEER_MS) {
                iterator.remove()
                endpointToDevice.remove(endpointId)?.let { deviceToEndpoint.remove(it) }
                Log.v(TAG, "Removing stale peer $endpointId after ${now - peer.lastSeenMillis}ms of inactivity")
                changed = true
            }
        }
        if (changed) {
            pushPeerUpdate()
        }
    }

    // handleHeartbeat: parses heartbeat payload to refresh timestamps and detect device changes.
    private fun handleHeartbeat(endpointId: String, payload: String) {
        val timestamp = payload.toLongOrNull()
        if (timestamp != null) {
            Log.v(TAG, "Heartbeat received from $endpointId @ $timestamp")
        } else {
            Log.v(TAG, "Heartbeat received from $endpointId")
        }
        touchPeer(endpointId)
    }

    // handleHandshake: applies identity info from handshake and updates DTN/DB state.
    private fun handleHandshake(endpointId: String, payload: String) {
        try {
            val json = JSONObject(payload)
            val remoteDeviceId = json.getString("deviceId")
            val remoteDisplayName = json.optString("displayName", null)?.takeIf { it.isNotBlank() }
            val remoteServiceId = json.optString("serviceId", null)?.takeIf { it.isNotBlank() }
            val publicKeyBase64 = json.optString("publicKey", null)
            val publicKey = publicKeyBase64?.takeIf { it.isNotBlank() }?.let {
                Base64.decode(it, Base64.DEFAULT)
            }

            Log.d(TAG, "Handshake received from $endpointId (device=$remoteDeviceId)")

            val existing = endpointPeers[endpointId]
            val effectiveDisplayName = remoteDisplayName
                ?: existing?.deviceDisplayName
                ?: existing?.endpointName
                ?: endpointId

            val basePeer = existing ?: PeerEntity(
                endpointId = endpointId,
                endpointName = existing?.endpointName ?: endpointId,
                serviceId = serviceId
            )
            val updated = basePeer
                .withDeviceIdentity(remoteDeviceId, effectiveDisplayName, publicKey)
                .copy(serviceId = remoteServiceId ?: basePeer.serviceId)

            endpointPeers[endpointId] = updated
            endpointToDevice[endpointId] = remoteDeviceId
            deviceToEndpoint[remoteDeviceId] = endpointId
            pushPeerUpdate()
            schedulePurge()

            val identityFingerprint = PeerProfile.computeIdentityFingerprint(
                effectiveDisplayName,
                remoteServiceId,
                publicKey
            )

            if (publicKey != null) {
                keyManager.storePeerPublicKey(remoteDeviceId, publicKey)
                keyManager.getDevicePrivateKey()?.let { privateKey ->
                    val sharedKey = keyManager.performKeyExchange(publicKey, privateKey)
                    keyManager.storePeerKey(remoteDeviceId, sharedKey)
                } ?: Log.w(TAG, "Device private key missing; cannot derive shared key for $remoteDeviceId")
            }

            val now = System.currentTimeMillis()
            keyManager.markHandshakeComplete(remoteDeviceId, now)
            onHandshakeCompleted?.invoke(remoteDeviceId, now)

            scope.launch {
                try {
                    val ownerId = this@NearbyService.resolveOwnerUserId() ?: return@launch
                    databaseRepository.upsertPeerProfile(
                        deviceId = remoteDeviceId,
                        displayName = effectiveDisplayName,
                        lastEndpointId = endpointId,
                        lastSeen = now,
                        visibility = PeerVisibility.OPEN,
                        publicKey = publicKey,
                        handshakeTimestamp = now,
                        serviceId = remoteServiceId,
                        identityHash = identityFingerprint,
                        ownerUserId = ownerId
                    )
                    databaseRepository.getPeerProfile(remoteDeviceId, ownerId)?.let { upsertPeerCatalog(it) }
                    databaseRepository.cleanupOldPeers(ownerUserId = ownerId)
                    databaseRepository.cleanupConversationCache(ownerUserId = ownerId, localDeviceId = deviceId)
                } catch (persistError: Exception) {
                    Log.e(TAG, "Failed to persist peer $remoteDeviceId", persistError)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process handshake payload", e)
        }
    }

    // sendHandshake: packages local identity + trust material and sends to new peer.
    private fun sendHandshake(endpointId: String) {
        scope.launch {
            try {
                val handshakeBytes = buildHandshakeMessage()
                val payload = Payload.fromBytes(handshakeBytes)
                val success = sendPayloadInternal(endpointId, payload)
                if (!success) {
                    Log.w(TAG, "Failed to send handshake to $endpointId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending handshake to $endpointId", e)
            }
        }
    }

    // buildHeartbeatPayload: serializes current timestamp so receivers can gauge freshness.
    private fun buildHeartbeatPayload(timestampMillis: Long = System.currentTimeMillis()): ByteArray {
        return (HEARTBEAT_PREFIX + timestampMillis.toString()).toByteArray(Charsets.UTF_8)
    }

    private suspend fun resolveLocalDisplayName(): String {
        cachedLocalDisplayName?.let { return it }

        val resolved = try {
            databaseRepository.getCurrentUser()?.let { ProfileUtils.buildDisplayName(it) }
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to load local display name", t)
            null
        }

        val finalName = resolved?.takeIf { it.isNotBlank() } ?: Build.MODEL
        cachedLocalDisplayName = finalName
        return finalName
    }

    private suspend fun buildHandshakeMessage(): ByteArray {
        val displayName = resolveLocalDisplayName()
        val json = JSONObject().apply {
            put("deviceId", deviceId)
            put("displayName", displayName)
            put("serviceId", serviceId)
            maybeDevicePublicKey()?.let { put("publicKey", Base64.encodeToString(it, Base64.NO_WRAP)) }
        }
        return (HANDSHAKE_PREFIX + json.toString()).toByteArray(Charsets.UTF_8)
    }

    // maybeDevicePublicKey: fetches stored public key when trust mode requires signature exchange.
    private fun maybeDevicePublicKey(): ByteArray? {
        keyManager.getDevicePublicKey()?.let { return it }
        return try {
            keyManager.initializeDeviceKeys()?.first
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to initialize device key pair", t)
            null
        }
    }
}






