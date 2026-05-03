// NearbyViewModel - Exposes observable nearby peer data to the UI layer.
// Created by Thanyani Nemukumbini. Edited by Siyabonga Popela.
// Date: 2025-09-11
package viewModels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.nexausingnearby.Nexa
import kotlinx.coroutines.flow.StateFlow
import DataLayer.PeerEntity as Peer
import CommsLayer.NearbyService

class NearbyViewModel(application: Application) : AndroidViewModel(application) {

    private val manager: NearbyService = Nexa.getNearbyServiceInstance()

    val peers: StateFlow<Map<String, Peer>> = manager.discoveredPeers

    // startDiscovery: requests NearbyService to begin scanning for peers.

    fun startDiscovery() = manager.startDiscovery()
    // stopDiscovery: tells NearbyService to halt scanning.
    fun stopDiscovery() = manager.stopDiscovery()

    // startAdvertising: begins advertising this device using the supplied name.

    fun startAdvertising(deviceName: String) = manager.startAdvertising(deviceName)
    // stopAdvertising: stops Nearby advertising to save power.
    fun stopAdvertising() = manager.stopAdvertising()

    // requestConnectionTo: delegates connection request to NearbyService with local display name.

    fun requestConnectionTo(endpointId: String, localName: String) {
        manager.requestConnection(endpointId, localName)
    }

    // onCleared: shuts down Nearby service when the ViewModel is disposed.

    override fun onCleared() {
        super.onCleared()
        manager.shutdown()
    }
}



