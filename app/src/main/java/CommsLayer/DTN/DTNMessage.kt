// DTNMessage - Represents a message packet handled by the DTN layer.
// Created by Tasima Hapazari.
// Date: 2025-08-23
package CommsLayer.DTN

import DataLayer.models.PeerVisibility
import java.util.UUID

enum class MessageMode{
    DIRECT_P2P, //Direct (use NearbyConncetions)
    DTN_P2P, //P2P via mesh routing
    GROUP_MESH //Group message via mesh
}

enum class DTNMessageState {
    PENDING,        // Waiting to be sent
    IN_TRANSIT,     // Actively being transmitted
    IN_MESH,        // Successfully injected into mesh
    DELIVERED,      // Confirmed delivery
    FAILED          // All attempts failed
}

data class DTNBundle(
    val bundleID: String = UUID.randomUUID().toString(), //random message ID
    val messageType: Int,
    val sourceID: String,
    val destinationID: String,  //final receiver
    val content: ByteArray?,
    val timestamp: Long = System.currentTimeMillis(),
    val hopCount: Int = 0,
    val copyCount: Int = 0, //for spray-and-wait (used with mesh P2P)
    val maxHops: Int = DTNConfig.MAX_HOPS,
    val messageTTL: Long = DTNConfig.MESSAGE_TTL_MS,
    val routingPath: List<String> = emptyList(), //Track routing path
    val metadata: BundleMetadata = BundleMetadata()
){
    // incrementHops: returns a new bundle with hop count bumped so routing can track traversal.
    fun incrementHops() : DTNBundle = copy(hopCount = hopCount + 1)
    // incrementCopies: creates a copy with bumped copy counter for spray-and-wait logic.
    fun incrementCopies(): DTNBundle = copy(copyCount = copyCount + 1)
    // isExpired: evaluates TTL and hop budget to decide whether bundle should be discarded.
    fun isExpired(): Boolean = (System.currentTimeMillis() - timestamp) > messageTTL || hopCount >= maxHops
    // addToPath: appends current relay to history so diagnostics reveal routing decisions.
    fun addToPath(nodeId: String): DTNBundle = copy(
        routingPath = routingPath + nodeId,
        metadata = metadata.copy(previousHopDeviceId = nodeId)
    )
    // withMetadata: attaches freshly computed metadata while preserving existing payload.
    fun withMetadata(metadata: BundleMetadata): DTNBundle = copy(metadata = metadata)

    // equals: treat bundles as identical when IDs match to simplify de-duplication.
    override fun equals(other: Any?): Boolean {
        if(this == other){
            return true
        }
        if(javaClass != other?.javaClass){
            return false
        }
        other as DTNBundle
        return bundleID == other.bundleID
    }

    // hashCode: delegate to bundle ID so hash-based collections stay aligned with equals.
    override fun hashCode(): Int {
        return bundleID.hashCode()
    }

    data class BundleMetadata(
        val requiredVisibility: PeerVisibility = PeerVisibility.OPEN,
        val originIdentityHash: String? = null,
        val originServiceId: String? = null,
        val previousHopDeviceId: String? = null
    )

    data class PendingMessage(
        var bundle: DTNBundle,
        var state: DTNMessageState = DTNMessageState.PENDING,
        val attemptedPeers: MutableSet<String> = mutableSetOf(),
        var lastAttempt: Long = 0L,
        val mode: MessageMode
    )

    data class GroupMembership(
        val groupID: String,
        val memberDeviceIDs: MutableSet<String> = mutableSetOf(),
        val lastUpdated: Long = System.currentTimeMillis()
    )


}



