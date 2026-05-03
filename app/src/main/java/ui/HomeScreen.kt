// HomeScreen - Activity managing nearby peer discovery and quick actions.
// Created by Siyabonga Popela. Edited by Tasima Hapazari and Thanyani Nemukumbini.
// Date: 2025-09-19
package ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import java.util.Locale
import kotlinx.coroutines.flow.combine
import com.google.android.material.chip.ChipGroup
import com.google.android.material.chip.Chip
import DataLayer.PeerEntity
import android.text.format.DateUtils
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import CommsLayer.NearbyService
import com.example.nexausingnearby.Nexa
import com.example.nexausingnearby.R
import ui.components.adapters.NearbyPeersAdapter
import DataLayer.PeerProfile
import DataLayer.models.PeerVisibility
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import DataLayer.DatabaseRepository
import utils.SessionManager
import utils.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import ui.components.models.NearbyPeerItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HomeScreen : AppCompatActivity(), NearbyPeersAdapter.OnPeerClickListener {

    private lateinit var btnRefresh: MaterialButton
    private lateinit var btnScanQR: MaterialButton
    private lateinit var rvNearbyPeers: RecyclerView
    private lateinit var peersAdapter: NearbyPeersAdapter
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var chipFilterGroup: ChipGroup
    private lateinit var chipAll: Chip
    private lateinit var chipNearby: Chip
    private lateinit var chipTrusted: Chip
    private lateinit var chipOpen: Chip
    private lateinit var chipBlocked: Chip

    private var currentPeerFilter = PeerFilter.ALL
    private val allPeerItems = mutableListOf<NearbyPeerItem>()

    private enum class PeerFilter { ALL, NEARBY, TRUSTED, OPEN, BLOCKED }

    private val databaseRepository by lazy { DatabaseRepository(applicationContext) }
    private val sessionManager by lazy { SessionManager(applicationContext) }

    private var servicesInitialized = false
    private val TAG = "HomeScreen"
    private lateinit var ownerUserId: String

    // Permissions we need
    private val requiredPermissions by lazy { PermissionUtils.getNearbyPermissions() }
    private val autoRefreshIntervalMs = 120_000L
    private var autoRefreshJob: Job? = null

    // onCreate: sets up UI elements, resolves user session, and kicks off permission checks.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        try {
            // Initialize views
            initializeViews()

            val resolvedOwner = resolveOwnerUserId()
        // Thanyani: guard ensures we never start Nearby without an authenticated profile.
            if (resolvedOwner.isNullOrEmpty()) {
                Toast.makeText(this, getString(R.string.profile_not_logged_in), Toast.LENGTH_LONG).show()
                finish()
                return
            }
            ownerUserId = resolvedOwner

            // Set up click listeners
            setupClickListeners()

            // Set up bottom navigation
            setupBottomNavigation()

            // Check and request permissions for nearby services
            checkAndRequestPermissions()

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Failed to initialize HomeScreen: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    // onResume: revalidates session, restarts services if permissions granted, and refreshes peers.
    override fun onResume() {
        super.onResume()
        val resolvedOwner = resolveOwnerUserId()
        // Thanyani: guard ensures we never start Nearby without an authenticated profile.
        if (resolvedOwner.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.profile_not_logged_in), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        ownerUserId = resolvedOwner

        if (hasAllRequiredPermissions()) {
            val nexaInstance = Nexa.getInstance()
            if (servicesInitialized) {
                nexaInstance.startNearbyServices()
                refreshConnections(showFeedback = false)
                scheduleAutoRefresh()
            } else {
                initializeNearbyServices()
            }
        } else {
            checkAndRequestPermissions()
        }
    }

    // onPause: stops auto-refresh to conserve resources when activity is backgrounded.

    override fun onPause() {
        cancelAutoRefresh()
        super.onPause()
    }

    private fun initializeViews() {
        try {
            btnRefresh = findViewById(R.id.btnRefresh)
            btnScanQR = findViewById(R.id.btnScanQR)
            rvNearbyPeers = findViewById(R.id.rvNearbyPeers)
            bottomNavigation = findViewById(R.id.bottomNavigation)
            chipFilterGroup = findViewById(R.id.chipFilterGroup)
            chipAll = findViewById(R.id.chipAll)
            chipNearby = findViewById(R.id.chipNearby)
            chipTrusted = findViewById(R.id.chipTrusted)
            chipOpen = findViewById(R.id.chipOpen)
            chipBlocked = findViewById(R.id.chipBlocked)

            peersAdapter = NearbyPeersAdapter(emptyList(), this)
            rvNearbyPeers.layoutManager = LinearLayoutManager(this)
            rvNearbyPeers.adapter = peersAdapter

            chipAll.isChecked = true
            setupFilterChips()

            Log.d(TAG, "Views initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing views", e)
            throw e
        }
    }

    private fun setupFilterChips() {
        chipFilterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            // Siyabonga: chips drive recycler filtering—keep logic light so animations stay smooth.
            val checkedId = checkedIds.firstOrNull() ?: R.id.chipAll
            currentPeerFilter = when (checkedId) {
                R.id.chipNearby -> PeerFilter.NEARBY
                R.id.chipTrusted -> PeerFilter.TRUSTED
                R.id.chipOpen -> PeerFilter.OPEN
                R.id.chipBlocked -> PeerFilter.BLOCKED
                else -> PeerFilter.ALL
            }
            applyPeerFilter()
        }
    }

    private fun applyPeerFilter() {
        if (!::peersAdapter.isInitialized) {
            return
        }
        val filtered = when (currentPeerFilter) {
            PeerFilter.ALL -> allPeerItems.toList()
            PeerFilter.NEARBY -> allPeerItems.filter { it.isConnected }
            PeerFilter.TRUSTED -> allPeerItems.filter { it.visibility == PeerVisibility.TRUSTED }
            PeerFilter.OPEN -> allPeerItems.filter { it.visibility == PeerVisibility.OPEN }
            PeerFilter.BLOCKED -> allPeerItems.filter { it.visibility == PeerVisibility.BLOCKED }
        }
        peersAdapter.updatePeers(filtered)
    }

    private fun hasAllRequiredPermissions(): Boolean =
        PermissionUtils.hasAllPermissions(this, requiredPermissions)

    private fun checkAndRequestPermissions() {
        try {
            val missingPermissions = PermissionUtils.getMissingPermissions(this, requiredPermissions)
            if (missingPermissions.isEmpty()) {
                Log.d(TAG, "All permissions already granted, initializing services")
                initializeNearbyServices()
            } else {
                Log.w(TAG, "Missing permissions: $missingPermissions")
                showMissingPermissionsDialog()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permissions", e)
            Toast.makeText(this, "Permission check failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showMissingPermissionsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(getString(R.string.permissions_denied_warning))
            .setPositiveButton("Open Settings") { _, _ -> openAppSettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun initializeNearbyServices() {
        if (servicesInitialized) {
            Log.d(TAG, "Services already initialized")
            return
        }

        try {
            Log.d(TAG, "Initializing encryption and Nexa services...")

            val nexaInstance = try {
                Nexa.getInstance()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get Nexa instance", e)
                Toast.makeText(this, "Application not properly initialized", Toast.LENGTH_LONG).show()
                return
            }

            nexaInstance.initializeServicesAfterPermissions()

            lifecycleScope.launch {
                delay(500)

                if (nexaInstance.isSystemReady()) {
                    servicesInitialized = true
                    observeNearbyPeers()
                    scheduleAutoRefresh()
                    withContext(Dispatchers.Main) {
                        Log.d(TAG, "Services initialized successfully")
                        Toast.makeText(this@HomeScreen, "Nearby services started", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.w(TAG, "System not ready after initialization attempt")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@HomeScreen, "Services are still initializing...", Toast.LENGTH_SHORT).show()
                    }

                    delay(2000)
                    if (nexaInstance.isSystemReady()) {
                        servicesInitialized = true
                        observeNearbyPeers()
                        scheduleAutoRefresh()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@HomeScreen, "Services ready", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize services", e)
            val errorMessage = when {
                e.message?.contains("sodium", ignoreCase = true) == true ||
                    e.message?.contains("NaCl", ignoreCase = true) == true ||
                    e.message?.contains("native", ignoreCase = true) == true ->
                    "Failed to initialize encryption. Please ensure your device supports the required security features."
                e.message?.contains("nearby", ignoreCase = true) == true ->
                    "Failed to initialize nearby services. Please check if Google Play Services is available."
                else ->
                    "Failed to start services: ${e.message}"
            }
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
            showEncryptionErrorDialog(e)
        }
    }

    private fun showEncryptionErrorDialog(error: Exception) {
        val message = """
            There was an issue starting the secure messaging system. This may be due to:

            ï¿½ Missing native libraries
            ï¿½ Device compatibility issues
            ï¿½ Security system restrictions
            ï¿½ Google Play Services unavailable

            Error: ${error.message}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Initialization Error")
            .setMessage(message)
            .setPositiveButton("Retry") { _, _ ->
                servicesInitialized = false
                initializeNearbyServices()
            }
            .setNegativeButton("Continue Without Services") { _, _ ->
                Toast.makeText(this, "Warning: Nearby services disabled", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Exit") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun observeNearbyPeers() {
        lifecycleScope.launch {
            try {
                val nearbyService = Nexa.getNearbyServiceInstance()
                val selfDeviceId = Nexa.currentDeviceID

                combine(nearbyService.peerContacts, nearbyService.discoveredPeers) { contacts, discovered ->
                    contacts to discovered
                }.collect { (contacts, discovered) ->
                    val items = withContext(Dispatchers.Default) {
                        buildPeerItems(nearbyService, contacts, discovered, selfDeviceId)
                    }
                    allPeerItems.clear()
                    allPeerItems.addAll(items)
                    applyPeerFilter()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing nearby peers", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@HomeScreen, R.string.peer_observe_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun buildPeerItems(
        nearbyService: NearbyService,
        contacts: Map<String, PeerProfile>,
        discovered: Map<String, PeerEntity>,
        selfDeviceId: String
    ): List<NearbyPeerItem> {
        val items = mutableListOf<NearbyPeerItem>()

        contacts.values.forEach { profile ->
            if (profile.deviceId == selfDeviceId) return@forEach
            val deviceId = profile.deviceId
            val isConnected = nearbyService.isDeviceConnected(deviceId)
            val endpointId = nearbyService.resolveEndpoint(deviceId)
                ?: discovered.values.firstOrNull { it.deviceId == deviceId }?.endpointId
                ?: profile.lastEndpointId
            val displayName = profile.displayName?.takeIf { it.isNotBlank() } ?: deviceId.take(8)

            items += NearbyPeerItem(
                endpointId = endpointId,
                deviceId = deviceId,
                displayName = displayName,
                details = detailForPersisted(isConnected, profile.visibility, profile.lastSeen, deviceId),
                isConnected = isConnected,
                visibility = profile.visibility,
                lastSeen = profile.lastSeen,
                isDiscoveredOnly = false
            )
        }

        discovered.values.forEach { entity ->
            val deviceId = entity.deviceId
            if (deviceId == selfDeviceId) return@forEach
            if (deviceId != null && contacts.containsKey(deviceId)) return@forEach

            val isConnected = nearbyService.isEndpointConnected(entity.endpointId)
            val displayName = entity.deviceDisplayName
                ?: entity.endpointName.takeIf { it.isNotBlank() }
                ?: entity.endpointId

            items += NearbyPeerItem(
                endpointId = entity.endpointId,
                deviceId = deviceId,
                displayName = displayName,
                details = detailForDiscovered(isConnected, entity),
                isConnected = isConnected,
                visibility = PeerVisibility.OPEN,
                lastSeen = entity.lastSeenMillis,
                isDiscoveredOnly = true
            )
        }

        return items.sortedWith(
            compareByDescending<NearbyPeerItem> { it.isConnected }
                .thenByDescending { it.visibility == PeerVisibility.TRUSTED }
                .thenBy { it.displayName.lowercase(Locale.getDefault()) }
        )
    }

    private fun detailForPersisted(
        isConnected: Boolean,
        visibility: PeerVisibility,
        lastSeen: Long?,
        deviceId: String?
    ): String? {
        val segments = mutableListOf<String>()
        if (isConnected) {
            segments += getString(R.string.peer_status_connected)
        }
        segments += when (visibility) {
            PeerVisibility.TRUSTED -> getString(R.string.peer_status_trusted)
            PeerVisibility.OPEN -> getString(R.string.peer_status_open)
            PeerVisibility.BLOCKED -> getString(R.string.peer_status_blocked)
        }
        if (!isConnected && lastSeen != null) {
            val relative = DateUtils.getRelativeTimeSpanString(
                lastSeen,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
            segments += getString(R.string.peer_status_last_seen, relative)
        }
        if (!deviceId.isNullOrBlank()) {
            segments += getString(R.string.peer_identifier, deviceId.take(8))
        }
        return segments.filter { it.isNotBlank() }.joinToString(" ï¿½ ").ifBlank { null }
    }

    private fun detailForDiscovered(isConnected: Boolean, entity: PeerEntity): String {
        val segments = mutableListOf<String>()
        if (isConnected) {
            segments += getString(R.string.peer_status_connected)
        }
        segments += getString(R.string.peer_status_discovered_recently)
        val relative = DateUtils.getRelativeTimeSpanString(
            entity.lastSeenMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
        segments += getString(R.string.peer_status_last_seen, relative)
        val endpointLabel = entity.endpointName.takeIf { it.isNotBlank() } ?: entity.endpointId.take(6)
        segments += endpointLabel
        return segments.joinToString(" ï¿½ ")
    }

    private fun refreshConnections(showFeedback: Boolean) {
        if (!servicesInitialized) {
            initializeNearbyServices()
            return
        }

        lifecycleScope.launch {
            try {
                btnRefresh.isEnabled = false
                if (showFeedback) {
                    Toast.makeText(this@HomeScreen, getString(R.string.refresh_in_progress), Toast.LENGTH_SHORT).show()
                }
                val nexaInstance = Nexa.getInstance()
                nexaInstance.stopNearbyServices()
                delay(250)
                nexaInstance.startNearbyServices()
                Nexa.getNearbyServiceInstance().refreshLocalDisplayName()
                if (showFeedback) {
                    Toast.makeText(this@HomeScreen, getString(R.string.refresh_complete), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh nearby services", e)
                if (showFeedback) {
                    val message = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
                    Toast.makeText(this@HomeScreen, getString(R.string.refresh_failed, message), Toast.LENGTH_LONG).show()
                }
            } finally {
                btnRefresh.isEnabled = true
            }
        }
    }

    private fun scheduleAutoRefresh() {
        cancelAutoRefresh()
        autoRefreshJob = lifecycleScope.launch {
            // Tasima: background loop revives discovery periodically; cancels onPause to conserve battery.
            while (isActive) {
                delay(autoRefreshIntervalMs)
                refreshConnections(showFeedback = false)
            }
        }
    }

    private fun cancelAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    private fun setupClickListeners() {
        try {
            btnRefresh.setOnClickListener {
                refreshConnections(showFeedback = true)
            }

            btnScanQR.setOnClickListener {
                checkCameraPermissionAndOpenCamera()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up click listeners", e)
        }
    }
    private fun setupBottomNavigation() {

        try {
            bottomNavigation.selectedItemId = R.id.navigation_add

            bottomNavigation.setOnItemSelectedListener { item ->
                // Thanyani: only launch a new activity when target differs to avoid back-stack spam.
                when (item.itemId) {
                    R.id.nav_chats -> {
                        navigateToActivity(ChatsScreen::class.java)
                        true
                    }
                    R.id.navigation_add -> true
                    R.id.nav_settings -> {
                        navigateToActivity(SettingsScreen::class.java)
                        true
                    }
                    R.id.nav_qr -> {
                        startActivity(Intent(this, QRCodeActivity::class.java))
                        true
                    }
                    else -> false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up bottom navigation", e)
        }
    }

    private fun navigateToActivity(activityClass: Class<*>) {
        try {
            if (this::class.java != activityClass) {
                val intent = Intent(this, activityClass)
                startActivity(intent)
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to activity", e)
            Toast.makeText(this, "Navigation failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkCameraPermissionAndOpenCamera() {
        val missing = PermissionUtils.getMissingPermissions(this, PermissionUtils.getCameraPermissions())
        if (missing.isEmpty()) {
            openCamera()
        } else {
            Toast.makeText(this, getString(R.string.permissions_denied_warning), Toast.LENGTH_LONG).show()
        }
    }
    private fun resolveOwnerUserId(): String? {
        Nexa.getInstance().getActiveUserId()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        sessionManager.getActiveUserId()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            Nexa.getInstance().setActiveUserId(it)
            return it
        }
        return runBlocking { databaseRepository.getActiveOwnerUserId() }?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun openCamera() {
        val intent = Intent(this, QRCodeActivity::class.java)
        startActivity(intent)
        Toast.makeText(this, "QR Scanner would open here", Toast.LENGTH_SHORT).show()
    }

    override fun onPeerClick(peer: NearbyPeerItem) {
        lifecycleScope.launch {
            try {
                val nearbyService = Nexa.getNearbyServiceInstance()
                val deviceId = ensureConnectionForPeer(nearbyService, peer)

                if (deviceId != null && deviceId != Nexa.currentDeviceID) {
                    withContext(Dispatchers.Main) {
                        openChatWithPeer(peer.displayName, deviceId)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@HomeScreen,
                            getString(R.string.peer_connection_pending, peer.displayName),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling peer click", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@HomeScreen,
                        getString(R.string.peer_connection_failed, peer.displayName),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private suspend fun ensureConnectionForPeer(
        nearbyService: NearbyService,
        peer: NearbyPeerItem
    ): String? {
        peer.deviceId?.let { existingId ->
            if (nearbyService.isDeviceConnected(existingId)) {
                return existingId
            }
            if (nearbyService.ensureActiveConnection(existingId)) {
                return existingId
            }
        }

        val endpointCandidate = peer.endpointId
        if (endpointCandidate.isNullOrBlank()) {
            return peer.deviceId
        }
        val endpointId = endpointCandidate

        withContext(Dispatchers.Main) {
            Toast.makeText(
                this@HomeScreen,
                getString(R.string.peer_connection_request, peer.displayName),
                Toast.LENGTH_SHORT
            ).show()
            nearbyService.requestConnection(endpointId)
        }

        val deviceId = awaitDeviceId(nearbyService, endpointId)
        if (deviceId != null) {
            databaseRepository.upsertPeerProfile(
                deviceId = deviceId,
                displayName = peer.displayName,
                lastEndpointId = endpointId,
                visibility = peer.visibility,
                ownerUserId = ownerUserId
            )
        }
        return deviceId
    }

    private suspend fun awaitDeviceId(
        nearbyService: NearbyService,
        endpointId: String,
        timeoutMs: Long = 7_000
    ): String? = withTimeoutOrNull(timeoutMs) {
        var resolved: String? = null
        while (resolved == null) {
            resolved = nearbyService.resolveDeviceId(endpointId)
            if (resolved == null) {
                delay(250)
            }
        }
        resolved
    }

    private fun openChatWithPeer(peerName: String, peerId: String) {
        try {
            val intent = Intent(this, InChatsActivity::class.java).apply {
                putExtra("FRIEND_NAME", peerName)
                putExtra("FRIEND_ID", peerId)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening chat with peer", e)
            Toast.makeText(this, "Failed to open chat with $peerName", Toast.LENGTH_SHORT).show()
        }
    }

    // onDestroy: cleans up refresh jobs and logs activity teardown.

    override fun onDestroy() {
        cancelAutoRefresh()
        super.onDestroy()
        Log.d(TAG, "HomeScreen destroyed")
    }
}

















































