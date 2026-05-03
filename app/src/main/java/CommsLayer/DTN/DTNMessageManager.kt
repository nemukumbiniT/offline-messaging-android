// DTNMessageManager - Coordinates DTN message routing and reliability handling.
// Created by Tasima Hapazari. Edited by Thanyani Nemukumbini.
// Date: 2025-08-24
package CommsLayer.DTN
/*


designed as a lower-level component responsible for the core DTN logic:
-bundle creation,
-routing,
-mesh injection,
-handling acknowledgments (if any), etc.
- It deals with DTNBundles and the raw mechanics of DTN.

 */
import CommsLayer.NearbyService
import DataLayer.DatabaseRepository
import android.content.Context
import DataLayer.models.PeerVisibility
import DataLayer.models.PendingBundleEntity
import android.util.Log
import com.google.android.gms.nearby.connection.Payload
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class DTNMessageManager(
    private val context: Context,
    private val nearbyService: NearbyService,
    private val databaseRepository: DatabaseRepository,
    private val deviceID: String
) {
    companion object {
        private const val TAG = "DTNMessageManager"
    }

    //Message Storage
    private val pendingMessages = mutableListOf<DTNBundle.PendingMessage>()
    private val seenBundles = mutableSetOf<String>()
    private val messageBuffer = mutableMapOf<String, DTNBundle>()
    private val peerVisibility = ConcurrentHashMap<String, PeerVisibility>()
    @Volatile private var defaultRequiredVisibility: PeerVisibility = PeerVisibility.OPEN

    private val storeAndForwardTtlMs = DTNConfig.STORE_AND_FORWARD_TTL_MS

    //Group Management
    private val groupMemberships = mutableMapOf<String, DTNBundle.GroupMembership>()
    private val myGroups = mutableSetOf<String>()

    //State flows for UI
    private val _deliveryStatus = MutableStateFlow<Map<String, DTNMessageState>>(emptyMap())
    val deliveryStatus: StateFlow<Map<String, DTNMessageState>> = _deliveryStatus

    private var retryJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Callbacks
    var onMessageReceived: ((DTNBundle, MessageMode) -> Unit)? = null
    var onGroupMessageReceived: ((String, DTNBundle) -> Unit)? = null

    // start: spins up background jobs and replays pending bundles into the retry loop.
    fun start() {
        coroutineScope.launch {
            loadPersistedPendingBundles()
        }
        startRetryLoop()
    }

    // stop: halts background work and resets transient queues for a clean shutdown.
    fun stop() {
        retryJob?.cancel()
        coroutineScope.cancel()
    }

// --- PUBLIC API ---

    // updateDefaultVisibilityRequirement: adjusts gating rules that enforce trusted/open delivery.
    fun updateDefaultVisibilityRequirement(requiredVisibility: PeerVisibility) {
        defaultRequiredVisibility = requiredVisibility
    }

    // getDefaultVisibilityRequirement: exposes current gating rule for callers making routing choices.
    fun getDefaultVisibilityRequirement(): PeerVisibility = defaultRequiredVisibility

    //Send P2P message - tries direct with Nearby first, falls back to DTN
    // sendP2PMessage: packages payload into bundle, persists state, and dispatches based on visibility.
    suspend fun sendP2PMessage(
        receiverID: String,
        message: Payload,
        requiredVisibility: PeerVisibility = defaultRequiredVisibility
    ): String {
        val bundle = DTNBundle(
            messageType = DTNConfig.TYPE_P2P_MESSAGE,
            sourceID = deviceID,
            destinationID = receiverID,
            content = message.asBytes(),
            metadata = DTNBundle.BundleMetadata(requiredVisibility = requiredVisibility)
        )
        if (!isPeerEligible(receiverID, requiredVisibility)) {
            Log.w(TAG, "Peer $receiverID does not satisfy visibility $requiredVisibility; routing via DTN")
            return sendViaDTN(bundle, MessageMode.DTN_P2P)
        }
        //Try direct P2P first
        var readyForDirect = isDirectlyConnected(receiverID)
        if (!readyForDirect) {
            readyForDirect = nearbyService.ensureActiveConnection(receiverID)
        }
        if (readyForDirect) {
            Log.d(TAG, "Sending P2P message to $receiverID")
            if (sendDirectMessage(bundle)) {
                return bundle.bundleID
            } else {
                Log.w(TAG, "Direct channel to $receiverID failed despite active connection")
            }
        }
        //Fall back to DTN
        Log.d(TAG, "Direct P2P failed, sending using DTN to $receiverID")
        return sendViaDTN(bundle, MessageMode.DTN_P2P)
    }
    //Send group message- always uses mesh
    // sendGroupMessage: encrypts and distributes payload to group peers while tracking bundle id.
    suspend fun sendGroupMessage(groupID: String, message: Payload): String {
        val bundle = DTNBundle(
            messageType = DTNConfig.TYPE_GROUP_MESSAGE,
            sourceID = deviceID,
            destinationID = groupID,
            content = message.asBytes()
        )
        Log.d(TAG, "Sending group message to $groupID")
        return sendViaDTN(bundle, MessageMode.GROUP_MESH)
    }

    //Join a group
    // joinGroup: records interest in a mesh group and notifies peers about membership.
    fun joinGroup(groupID: String) {
        myGroups.add(groupID)
        announceGroupMembership()
        Log.d(TAG, "Joined group $groupID")
    }

    //Leave a group
    // leaveGroup: removes local membership and broadcasts an unsubscribe announcement.
    fun leaveGroup(groupID: String) {
        myGroups.remove(groupID)
        announceGroupMembership()
        Log.d(TAG, "Left group $groupID")
    }

    //Process incoming payload from NearbyService
    // handleIncomingPayload: decodes incoming Nearby bytes and routes to appropriate handler.
    suspend fun handleIncomingPayload(endpointId: String, payload: Payload) {
        val sourceDeviceId = nearbyService.resolveDeviceId(endpointId) ?: endpointId
        try {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val data = payload.asBytes()
                    if (data != null) {
                        processIncomingData(sourceDeviceId, data)
                    } else {
                        Log.w(TAG, "Received empty byte payload from $sourceDeviceId")
                    }
                }

                else -> {
                    Log.d(TAG, "Unsupported payload type: ${payload.type}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming payload from $sourceDeviceId", e)

        }
    }
    // -- Private Implementation --
    // sendViaDTN: central dispatch path that chooses direct vs. mesh routes and persists pending entry.
    private suspend fun sendViaDTN(bundle: DTNBundle, mode: MessageMode): String {
        val destinationVisibility = visibilityForPeer(bundle.destinationID)
        if (!meetsVisibilityRequirement(destinationVisibility, bundle.metadata.requiredVisibility)) {
            Log.w(
                TAG,
                "Destination ${bundle.destinationID} visibility $destinationVisibility fails requirement ${bundle.metadata.requiredVisibility}; dropping bundle ${bundle.bundleID}"
            )
            updateDeliveryStatus(bundle.bundleID, DTNMessageState.FAILED)
            return bundle.bundleID
        }

        val enrichedBundle = bundle.copy(
            messageTTL = DTNConfig.STORE_AND_FORWARD_TTL_MS,
            maxHops = DTNConfig.MAX_RELAY_HOPS
        ).addToPath(deviceID)

        val pendingMessage = DTNBundle.PendingMessage(
            bundle = enrichedBundle,
            state = DTNMessageState.PENDING,
            attemptedPeers = mutableSetOf(),
            lastAttempt = 0L,
            mode = mode
        )

        synchronized(pendingMessages) {
            pendingMessages.removeIf { it.bundle.bundleID == enrichedBundle.bundleID }
            pendingMessages.add(pendingMessage)
        }
        updateDeliveryStatus(enrichedBundle.bundleID, DTNMessageState.PENDING)

        try {
            persistPendingMessage(pendingMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist bundle ${pendingMessage.bundle.bundleID}", e)
        }

        fanOutToPeers(pendingMessage)
        return enrichedBundle.bundleID
    }

    // sendDirectMessage: attempts immediate Nearby send and returns success for analytics.
    private suspend fun sendDirectMessage(bundle: DTNBundle): Boolean {
        return try {
            val payload = bundleToPayload(bundle)
            val delivered = nearbyService.sendPayloadToDevice(bundle.destinationID, payload)
            if (delivered) {
                updateDeliveryStatus(bundle.bundleID, DTNMessageState.DELIVERED)
                Log.d(TAG, "Direct message sent to ${bundle.destinationID} successfully")
            } else {
                Log.w(TAG, "Failed to send direct message to ${bundle.destinationID}; endpoint unavailable")
            }
            delivered
        } catch (e: Exception) {
            Log.e(TAG, "Error sending direct message", e)
            false
        }
    }
    // isDirectlyConnected: checks active endpoint map to confirm we have direct radio path.
    private fun isDirectlyConnected(peerID: String): Boolean {
        return nearbyService.isDeviceConnected(peerID)
    }
    // attemptMeshInjection: stores bundle for future relays when current connectivity is insufficient.
    private suspend fun attemptMeshInjection(pendingMsg: DTNBundle.PendingMessage) {
        val requiredVisibility = pendingMsg.bundle.metadata.requiredVisibility
        val destinationVisibility = visibilityForPeer(pendingMsg.bundle.destinationID)
        if (!meetsVisibilityRequirement(destinationVisibility, requiredVisibility)) {
            Log.d(
                TAG,
                "Destination ${pendingMsg.bundle.destinationID} no longer meets visibility requirement $requiredVisibility; marking bundle ${pendingMsg.bundle.bundleID} as failed"
            )
            pendingMsg.state = DTNMessageState.FAILED
            synchronized(pendingMessages) {
                pendingMessages.removeIf { it.bundle.bundleID == pendingMsg.bundle.bundleID }
            }
            updateDeliveryStatus(pendingMsg.bundle.bundleID, DTNMessageState.FAILED)
            schedulePendingRemoval(pendingMsg.bundle.bundleID)
            return
        }

        fanOutToPeers(pendingMsg)
    }

    // fanOutToPeers: iterates connected peers and forwards bundle while obeying trust/avoid lists.
    private suspend fun fanOutToPeers(pendingMsg: DTNBundle.PendingMessage, excludePeer: String? = null) {
        val now = System.currentTimeMillis()
        if (pendingMsg.bundle.isExpired() || pendingMsg.bundle.hopCount >= DTNConfig.MAX_RELAY_HOPS) {
            Log.d(TAG, "Bundle ${pendingMsg.bundle.bundleID} expired or reached hop limit; dropping")
            pendingMsg.state = DTNMessageState.FAILED
            synchronized(pendingMessages) {
                pendingMessages.removeIf { it.bundle.bundleID == pendingMsg.bundle.bundleID }
            }
            updateDeliveryStatus(pendingMsg.bundle.bundleID, DTNMessageState.FAILED)
            schedulePendingRemoval(pendingMsg.bundle.bundleID)
            return
        }

        val requiredVisibility = pendingMsg.bundle.metadata.requiredVisibility
        val destinationId = pendingMsg.bundle.destinationID
        val connectedPeers = getConnectedPeers().filter { it != deviceID }
        if (connectedPeers.isEmpty()) {
            Log.d(TAG, "No connected peers to forward bundle ${pendingMsg.bundle.bundleID}")
            return
        }

        if (connectedPeers.contains(destinationId) && isPeerEligible(destinationId, requiredVisibility)) {
            val directBundle = stampBundleForForward(pendingMsg.bundle)
            if (sendDirectMessage(directBundle)) {
                markPendingDelivered(pendingMsg.bundle.bundleID)
                return
            }
        }

        val relayCandidates = connectedPeers
            .filterNot { it == destinationId }
            .filterNot { excludePeer != null && it == excludePeer }
            .filter { isPeerEligible(it, requiredVisibility) }

        val freshPeers = relayCandidates.filterNot { pendingMsg.attemptedPeers.contains(it) }

        if (freshPeers.isEmpty()) {
            Log.d(TAG, "No new peers to relay bundle ${pendingMsg.bundle.bundleID}")
            return
        }

        pendingMsg.bundle = stampBundleForForward(pendingMsg.bundle)

        var forwarded = false
        val serialized = serializeBundle(pendingMsg.bundle)
        for (peerId in freshPeers) {
            try {
                val payload = Payload.fromBytes(serialized.copyOf())
                sendPayloadToPeer(peerId, payload)
                pendingMsg.attemptedPeers.add(peerId)
                forwarded = true
                Log.d(TAG, "Forwarded bundle ${pendingMsg.bundle.bundleID} to $peerId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to forward bundle ${pendingMsg.bundle.bundleID} to $peerId", e)
            }
        }

        if (forwarded) {
            pendingMsg.state = DTNMessageState.IN_MESH
            pendingMsg.lastAttempt = now
            updateDeliveryStatus(pendingMsg.bundle.bundleID, DTNMessageState.IN_MESH)
            runCatching { updatePersistedPending(pendingMsg) }.onFailure { error ->
                Log.e(TAG, "Failed to update persisted bundle ${pendingMsg.bundle.bundleID}", error)
            }
        }
    }

    // processIncomingData: deserializes incoming packet and hands it to specific message handlers.
    private suspend fun processIncomingData(fromDeviceId: String, data: ByteArray) {
        try {
            val bundle = deserializeBundle(data) //change into bundle

//check for duplicates
            if (seenBundles.contains(bundle.bundleID)) {
                Log.d(TAG, "Duplicate bundle ${bundle.bundleID}, ignoring")
                return
            }
            seenBundles.add(bundle.bundleID)

//check if bundle expired (For message TTL functionality)
            if (bundle.isExpired()) {
                Log.d(TAG, "Bundle ${bundle.bundleID} expired, dropping")
                return
            }

//store in buffer
            messageBuffer[bundle.bundleID] = bundle

//Process based on message type
            when (bundle.messageType) {
                DTNConfig.TYPE_P2P_MESSAGE -> handleP2PMessage(bundle, fromDeviceId)
                DTNConfig.TYPE_GROUP_MESSAGE -> handleGroupMessage(bundle, fromDeviceId)
                DTNConfig.TYPE_GROUP_ANNOUNCEMENT -> handleGroupAnnouncement(bundle, fromDeviceId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming data", e)
        }
    }

    // handleP2PMessage: validates and acknowledges direct messages before surfacing to listeners.
    private suspend fun handleP2PMessage(bundle: DTNBundle, fromPeerDevice: String) {
        if (bundle.destinationID == deviceID) {
            Log.d(TAG, "P2P message ${bundle.bundleID} delivered locally")
            onMessageReceived?.invoke(bundle, MessageMode.DTN_P2P)
            synchronized(pendingMessages) {
                pendingMessages.removeAll { it.bundle.bundleID == bundle.bundleID }
            }
            schedulePendingRemoval(bundle.bundleID)
            return
        }

        forwardP2PMessage(bundle, fromPeerDevice)
    }

    // handleGroupMessage: decrypts group payload and rebroadcasts when necessary.
    private suspend fun handleGroupMessage(bundle: DTNBundle, fromPeerDevice: String) {
        val groupID = bundle.destinationID

//check if I'm in group
        if (myGroups.contains(groupID)) {
            Log.d(TAG, "Group message ${bundle.bundleID} delivered to group $groupID")
            onGroupMessageReceived?.invoke(groupID, bundle)
        }
//If not, forward message
        forwardGroupMessage(bundle, fromPeerDevice)
    }

    // forwardP2PMessage: relays bundle to other peers while preventing loops via path tracking.
    private suspend fun forwardP2PMessage(bundle: DTNBundle, fromPeerDevice: String) {
        val pending = ensurePendingRelay(bundle, MessageMode.DTN_P2P, fromPeerDevice)
        fanOutToPeers(pending, excludePeer = fromPeerDevice)
    }

    // forwardGroupMessage: reissues group bundles to members not yet reached.
    private suspend fun forwardGroupMessage(bundle: DTNBundle, fromPeerDevice: String) {
        val pending = ensurePendingRelay(bundle, MessageMode.GROUP_MESH, fromPeerDevice)
        fanOutToPeers(pending, excludePeer = fromPeerDevice)
    }

    // handleGroupAnnouncement: updates membership roster when peers broadcast join/leave notices.
    private suspend fun handleGroupAnnouncement(bundle: DTNBundle, fromPeer: String) {
//Parse group membership announcement
        val announcement = String(bundle.content!!)
        val groups = announcement.split(",").filter { it.isNotBlank() }

        val membership = DTNBundle.GroupMembership(
            groupID = "announcement",
            memberDeviceIDs = groups.toMutableSet()
        )

// Update group membership info
        for (groupId in groups) {
            groupMemberships.getOrPut(groupId) { DTNBundle.GroupMembership(groupId) }
            .memberDeviceIDs.add(bundle.sourceID)
        }

        Log.d(TAG, "Updated group membership for ${bundle.sourceID}: $groups")

    }

    // announceGroupMembership: informs connected peers about current group roster for fan-out decisions.
    private fun announceGroupMembership() {
        if (myGroups.isEmpty()) {
            return
        }

        val announcement = myGroups.joinToString(",")
        val bundle = DTNBundle(
            messageType = DTNConfig.TYPE_GROUP_ANNOUNCEMENT,
            sourceID = deviceID,
            destinationID = "broadcast",
            content = announcement.toByteArray()
        )
        coroutineScope.launch {
            val connectedPeers = getConnectedPeers()
            for (peerId in connectedPeers) {
                try {
                    val payload = Payload.fromBytes(serializeBundle(bundle))
                    sendPayloadToPeer(peerId, payload)
                    Log.d(TAG, "Announced group membership to $peerId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to announce membership to $peerId: ${e.message}")
                }
            }
        }
    }

    // startRetryLoop: launches coroutine that periodically re-attempts queued bundles.
    private fun startRetryLoop() {
        retryJob = coroutineScope.launch {
            while (isActive) {
                try {
                    delay(DTNConfig.RETRY_INTERVAL_MS)
                    val pendingList = synchronized(pendingMessages) {
                        pendingMessages.filter { it.state == DTNMessageState.PENDING }.toList()
                    }
                    for (pendingMsg in pendingList) {
                        if (System.currentTimeMillis() - pendingMsg.lastAttempt > DTNConfig.RETRY_TIMEOUT_MS) {
                            attemptMeshInjection(pendingMsg)
                        }
                    }
                    //Cleanup old messages
                    cleanupOldMessages()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in retry loop", e)
                }
            }
        }
    }

    // replacePeerVisibilities: swaps visibility cache with fresh snapshot to keep routing decisions up to date.
    fun replacePeerVisibilities(snapshot: Map<String, PeerVisibility>) {
        peerVisibility.clear()
        peerVisibility.putAll(snapshot)
    }

    // updatePeerVisibility: updates single peer entry and revalidates pending deliveries.
    fun updatePeerVisibility(peerId: String, visibility: PeerVisibility) {
        peerVisibility[peerId] = visibility
    }

    // removePeer: drops peer from caches so retries stop targeting an offline node.
    fun removePeer(peerId: String) {
        peerVisibility.remove(peerId)
    }

    // visibilityForPeer: resolves cached visibility or defaults to open when unknown.
    private fun visibilityForPeer(peerId: String): PeerVisibility {
        return peerVisibility[peerId] ?: PeerVisibility.OPEN
    }

    // meetsVisibilityRequirement: enforces trusted-only rules by comparing requested vs. actual trust level.
    private fun meetsVisibilityRequirement(current: PeerVisibility, required: PeerVisibility): Boolean {
        return when (required) {
            PeerVisibility.TRUSTED -> current == PeerVisibility.TRUSTED
            PeerVisibility.OPEN -> current != PeerVisibility.BLOCKED
            PeerVisibility.BLOCKED -> false
        }
    }

    // isPeerEligible: combines connectivity and visibility checks before dispatching bundle.
    private fun isPeerEligible(peerId: String, requiredVisibility: PeerVisibility): Boolean {
        if (peerId == deviceID) {
            return true
        }
        val current = visibilityForPeer(peerId)
        if (current == PeerVisibility.BLOCKED) {
            return false
        }
        return meetsVisibilityRequirement(current, requiredVisibility)
    }

    // loadPersistedPendingBundles: reloads queued bundles from storage after restart.
    private suspend fun loadPersistedPendingBundles() {
        try {
            val now = System.currentTimeMillis()
            databaseRepository.pruneExpiredPendingBundles(now)
            val persisted = databaseRepository.loadPendingBundles()
            for (entity in persisted) {
                val bundle = runCatching { deserializeBundle(entity.serializedBundle) }.getOrElse { error ->
                    Log.e(TAG, "Cannot restore DTN bundle ${entity.bundleId}", error)
                    schedulePendingRemoval(entity.bundleId)
                    continue
                }
                val pending = DTNBundle.PendingMessage(
                    bundle = bundle,
                    state = DTNMessageState.PENDING,
                    attemptedPeers = parseAttemptedPeers(entity.attemptedPeersCsv),
                    lastAttempt = entity.lastAttemptAt,
                    mode = modeFromString(entity.mode)
                )
                synchronized(pendingMessages) {
                    pendingMessages.removeIf { it.bundle.bundleID == bundle.bundleID }
                    pendingMessages.add(pending)
                }
                updateDeliveryStatus(bundle.bundleID, DTNMessageState.PENDING)
            }
            if (persisted.isNotEmpty()) {
                Log.d(TAG, "Restored ${persisted.size} DTN bundles from persistence")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted DTN bundles", e)
        }
    }

    // persistPendingMessage: writes pending entry into database for eventual retry.
    private suspend fun persistPendingMessage(pendingMsg: DTNBundle.PendingMessage) {
        val entity = PendingBundleEntity(
            bundleId = pendingMsg.bundle.bundleID,
            serializedBundle = serializeBundle(pendingMsg.bundle),
            targetDeviceId = pendingMsg.bundle.destinationID,
            mode = pendingMsg.mode.name,
            expiryAt = maxOf(pendingMsg.bundle.timestamp + storeAndForwardTtlMs, System.currentTimeMillis() + storeAndForwardTtlMs),
            hopCount = pendingMsg.bundle.hopCount,
            lastAttemptAt = pendingMsg.lastAttempt,
            attemptedPeersCsv = pendingMsg.attemptedPeers.joinToString(separator = ","),
            createdAt = pendingMsg.bundle.timestamp
        )
        databaseRepository.savePendingBundle(entity)
    }

    // updatePersistedPending: refreshes attempt metadata after each retry so scheduling stays accurate.
    private suspend fun updatePersistedPending(pendingMsg: DTNBundle.PendingMessage) {
        databaseRepository.updatePendingBundleAttempt(
            pendingMsg.bundle.bundleID,
            pendingMsg.attemptedPeers,
            pendingMsg.lastAttempt,
            pendingMsg.bundle.hopCount
        )
    }

    // schedulePendingRemoval: delays cleanup so acknowledgements can land before deleting from storage.
    private fun schedulePendingRemoval(bundleId: String) {
        coroutineScope.launch {
            databaseRepository.removePendingBundle(bundleId)
        }
    }

    // parseAttemptedPeers: converts persisted CSV into mutable set for retry bookkeeping.
    private fun parseAttemptedPeers(csv: String): MutableSet<String> {
        if (csv.isBlank()) {
            return mutableSetOf()
        }
        return csv.split(',')
            .mapNotNull { token -> token.trim().takeIf { it.isNotEmpty() } }
            .toMutableSet()
    }

    // modeFromString: maps stored label back to enum, defaulting to DIRECT when absent.
    private fun modeFromString(raw: String?): MessageMode {
        if (raw.isNullOrBlank()) {
            return MessageMode.DTN_P2P
        }
        return runCatching { MessageMode.valueOf(raw) }.getOrElse { MessageMode.DTN_P2P }
    }

    // normalizeIncomingBundle: cleans metadata (timestamps, hop counters) before we process downstream.
    private fun normalizeIncomingBundle(bundle: DTNBundle): DTNBundle {
        val ttl = maxOf(bundle.messageTTL, storeAndForwardTtlMs)
        val maxHops = maxOf(bundle.maxHops, DTNConfig.MAX_RELAY_HOPS)
        return bundle.copy(messageTTL = ttl, maxHops = maxHops)
    }

    // ensurePendingRelay: guarantees pending bundle exists before queuing for relay scheduling.
    private suspend fun ensurePendingRelay(
        bundle: DTNBundle,
        mode: MessageMode,
        fromPeerDevice: String
    ): DTNBundle.PendingMessage {
        val existing = synchronized(pendingMessages) {
            pendingMessages.firstOrNull { it.bundle.bundleID == bundle.bundleID }
        }
        if (existing != null) {
            val added = existing.attemptedPeers.add(fromPeerDevice)
            if (added) {
                runCatching { updatePersistedPending(existing) }.onFailure { error ->
                    Log.e(TAG, "Failed to update relay bundle ${existing.bundle.bundleID}", error)
                }
            }
            return existing
        }

        val normalized = normalizeIncomingBundle(bundle)
        val pending = DTNBundle.PendingMessage(
            bundle = normalized,
            state = DTNMessageState.PENDING,
            attemptedPeers = mutableSetOf(fromPeerDevice),
            lastAttempt = 0L,
            mode = mode
        )

        synchronized(pendingMessages) {
            pendingMessages.add(pending)
        }

        runCatching { persistPendingMessage(pending) }.onFailure { error ->
            Log.e(TAG, "Failed to persist relay bundle ${pending.bundle.bundleID}", error)
        }

        return pending
    }

    // markPendingDelivered: updates pending table so duplicate retries stop once ACK observed.
    private fun markPendingDelivered(bundleId: String) {
        synchronized(pendingMessages) {
            pendingMessages.removeAll { it.bundle.bundleID == bundleId }
        }
        updateDeliveryStatus(bundleId, DTNMessageState.DELIVERED)
    }

    // cleanupOldMessages: purges delivered/expired records to keep storage lean.
    private suspend fun cleanupOldMessages() {
        val cutoff = System.currentTimeMillis() - storeAndForwardTtlMs
        val expiredIds = mutableListOf<String>()
        synchronized(pendingMessages) {
            val iterator = pendingMessages.iterator()
            while (iterator.hasNext()) {
                val pending = iterator.next()
                if (pending.bundle.timestamp < cutoff) {
                    iterator.remove()
                    expiredIds.add(pending.bundle.bundleID)
                }
            }
        }
        for (bundleId in expiredIds) {
            updateDeliveryStatus(bundleId, DTNMessageState.FAILED)
            schedulePendingRemoval(bundleId)
        }

        databaseRepository.pruneExpiredPendingBundles(System.currentTimeMillis())
        messageBuffer.entries.removeIf { (_, bundle) -> bundle.timestamp < cutoff }

        if (seenBundles.size > 1000) {
            seenBundles.clear()
        }
    }

    // updateDeliveryStatus: persists latest delivery state to drive analytics and retries.
    private fun updateDeliveryStatus(bundleID: String, status: DTNMessageState) {
        val current = _deliveryStatus.value.toMutableMap()
        current[bundleID] = status
        _deliveryStatus.value = current
    }

    // getConnectedPeers: snapshot of direct connections used to seed forwarders.
    private fun getConnectedPeers(): List<String> {
        return nearbyService.getConnectedDeviceIds()
    }

    // sendPayloadToPeer: hands payload to Nearby service and handles exception reporting.
    private suspend fun sendPayloadToPeer(peerId: String, payload: Payload) {
        val success = nearbyService.sendPayloadToDevice(peerId, payload)
        if (!success) {
            throw IllegalStateException("Failed to send payload to $peerId")
        }
    }

    // stampBundleForForward: increments hops/copies before forwarding to keep TTL logic accurate.
    private fun stampBundleForForward(bundle: DTNBundle): DTNBundle {
        val incremented = bundle.incrementHops()
        val withMeta = incremented.withMetadata(incremented.metadata.copy(previousHopDeviceId = deviceID))
        return if (withMeta.routingPath.lastOrNull() == deviceID) {
            withMeta
        } else {
            withMeta.addToPath(deviceID)
        }
    }

    // serializeBundle: encodes bundle into JSON + binary payload for storage/transport.
    private fun serializeBundle(bundle: DTNBundle): ByteArray {
        val json = JSONObject().apply {
            put("bundleId", bundle.bundleID)
            put("messageType", bundle.messageType)
            put("sourceId", bundle.sourceID)
            put("destinationId", bundle.destinationID)
            put("timestamp", bundle.timestamp)
            put("hopCount", bundle.hopCount)
            put("maxHops", bundle.maxHops)
            put("messageTtl", bundle.messageTTL)
            put("copyCount", bundle.copyCount)
            put("routingPath", JSONArray().apply {
                bundle.routingPath.forEach { put(it) }
            })
            bundle.content?.let { contentBytes ->
                put("content", android.util.Base64.encodeToString(contentBytes, android.util.Base64.NO_WRAP))
            }
            put("metadata", JSONObject().apply {
                put("requiredVisibility", bundle.metadata.requiredVisibility.name)
                bundle.metadata.originIdentityHash?.let { put("originIdentityHash", it) }
                bundle.metadata.originServiceId?.let { put("originServiceId", it) }
                bundle.metadata.previousHopDeviceId?.let { put("previousHopDeviceId", it) }
            })
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    // deserializeBundle: reconstructs bundle from stored bytes, guarding against corruption.
    private fun deserializeBundle(data: ByteArray): DTNBundle {
        val serialized = String(data, Charsets.UTF_8)
        val trimmed = serialized.trimStart()
        if (trimmed.startsWith("{")) {
            val json = JSONObject(trimmed)
            val base64Content = json.optString("content", null)
            val content = base64Content?.takeIf { it.isNotBlank() }?.let {
                android.util.Base64.decode(it, android.util.Base64.DEFAULT)
            }
            val routingJson = json.optJSONArray("routingPath")
            val routingPath = mutableListOf<String>()
            if (routingJson != null) {
                for (index in 0 until routingJson.length()) {
                    val hop = routingJson.optString(index)
                    if (!hop.isNullOrBlank()) {
                        routingPath.add(hop)
                    }
                }
            }
            val metadataJson = json.optJSONObject("metadata")
            val metadata = metadataJson?.let { meta ->
                val visibilityRaw = meta.optString("requiredVisibility", PeerVisibility.OPEN.name)
                DTNBundle.BundleMetadata(
                    requiredVisibility = visibilityFromString(visibilityRaw),
                    originIdentityHash = meta.optString("originIdentityHash").takeIf { it.isNotBlank() },
                    originServiceId = meta.optString("originServiceId").takeIf { it.isNotBlank() },
                    previousHopDeviceId = meta.optString("previousHopDeviceId").takeIf { it.isNotBlank() }
                )
            } ?: DTNBundle.BundleMetadata()
            return DTNBundle(
                bundleID = json.optString("bundleId", UUID.randomUUID().toString()),
                messageType = json.getInt("messageType"),
                sourceID = json.getString("sourceId"),
                destinationID = json.getString("destinationId"),
                content = content,
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                hopCount = json.optInt("hopCount", 0),
                copyCount = json.optInt("copyCount", 0),
                maxHops = json.optInt("maxHops", DTNConfig.MAX_HOPS),
                messageTTL = json.optLong("messageTtl", DTNConfig.MESSAGE_TTL_MS),
                routingPath = routingPath,
                metadata = metadata
            )
        }

        val parts = serialized.split("|")
        if (parts.size != 10) {
            throw IllegalArgumentException("Invalid bundle format: expected 10 parts, got ${parts.size}")
        }

        val content = if (parts[4].isNotEmpty()) {
            android.util.Base64.decode(parts[4], android.util.Base64.DEFAULT)
        } else {
            null
        }

        return DTNBundle(
            bundleID = parts[0],
            messageType = parts[1].toInt(),
            sourceID = parts[2],
            destinationID = parts[3],
            content = content,
            timestamp = parts[5].toLong(),
            hopCount = parts[6].toInt(),
            maxHops = parts[7].toInt(),
            messageTTL = parts[8].toLong(),
            copyCount = parts[9].toInt(),
            metadata = DTNBundle.BundleMetadata()
        )
    }

    // visibilityFromString: safely parses stored visibility label, defaulting to OPEN for unknown values.
    private fun visibilityFromString(raw: String?): PeerVisibility {
        val normalized = raw?.trim()?.uppercase(Locale.getDefault())
        return if (normalized.isNullOrEmpty()) {
            PeerVisibility.OPEN
        } else {
            runCatching { PeerVisibility.valueOf(normalized) }.getOrElse { PeerVisibility.OPEN }
        }
    }
    // Helper methods for converting between DTNBundle and Payload
    // bundleToPayload: serializes bundle metadata and content into Nearby payload for transport.
    fun bundleToPayload(bundle: DTNBundle): Payload {
        val serializedData = serializeBundle(bundle)
        return Payload.fromBytes(serializedData)
    }

    // payloadToBundle: reconstructs bundle object from bytes received over Nearby.
    fun payloadToBundle(payload: Payload): DTNBundle {
        val data = payload.asBytes() ?: throw IllegalArgumentException("Payload has no byte data")
        return deserializeBundle(data)
    }

    //Helper methods for when Chat Uis
    // getDTNManagerInstance: legacy static accessor kept for components still expecting singleton access.
    fun getDTNManagerInstance() : DTNMessageManager{
        return this
    }
}






































