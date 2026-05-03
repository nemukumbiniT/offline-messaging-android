// ChatsScreen - Activity presenting recent conversations with filtering and search.
// Created by Siyabonga Popela. Edited by Thanyani Nemukumbini.
// Date: 2025-09-18
package ui

import DataLayer.DatabaseRepository
import DataLayer.PeerProfile
import DataLayer.models.PeerVisibility
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nexausingnearby.Nexa
import com.example.nexausingnearby.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ui.components.adapters.ChatListAdapter
import ui.components.models.Chat
import utils.SessionManager
import java.util.Locale

class ChatsScreen : AppCompatActivity(), ChatListAdapter.OnChatClickListener {

    private lateinit var rvChats: RecyclerView
    private lateinit var chatAdapter: ChatListAdapter
    private lateinit var etSearch: TextInputEditText
    private lateinit var searchCard: MaterialCardView
    private lateinit var ivSearch: ImageView
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var chipChatFilters: ChipGroup
    private lateinit var chipChatAll: Chip
    private lateinit var chipChatConnected: Chip
    private lateinit var chipChatTrusted: Chip
    private lateinit var chipChatOpen: Chip
    private lateinit var chipChatBlocked: Chip

    private val databaseRepository by lazy { DatabaseRepository(applicationContext) }
    private val sessionManager by lazy { SessionManager(applicationContext) }
    private val nearbyService by lazy { Nexa.getNearbyServiceInstance() }
    private val TAG = "ChatsScreen"

    private lateinit var ownerUserId: String
    private val allChatItems = mutableListOf<Chat>()
    private val visibleChatItems = mutableListOf<Chat>()
    private var currentSearchQuery: String = ""
    private var activeChatFilter = ChatFilter.ALL

    private enum class ChatFilter { ALL, CONNECTED, TRUSTED, OPEN, BLOCKED }

    // onCreate: binds views, configures adapters, and kicks off roster observers.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chats)

        rvChats = findViewById(R.id.rvChats)
        etSearch = findViewById(R.id.etSearch)
        searchCard = findViewById(R.id.searchCard)
        ivSearch = findViewById(R.id.ivSearch)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        chipChatFilters = findViewById(R.id.chipChatFilters)
        chipChatAll = findViewById(R.id.chipChatAll)
        chipChatConnected = findViewById(R.id.chipChatConnected)
        chipChatTrusted = findViewById(R.id.chipChatTrusted)
        chipChatOpen = findViewById(R.id.chipChatOpen)
        chipChatBlocked = findViewById(R.id.chipChatBlocked)

        chatAdapter = ChatListAdapter(visibleChatItems, this)
        rvChats.layoutManager = LinearLayoutManager(this)
        rvChats.adapter = chatAdapter

        chipChatAll.isChecked = true

        setupSearchFunctionality()
        setupChatFilters()
        ivSearch.setOnClickListener { etSearch.requestFocus() }
        setupBottomNavigation()

        val resolvedOwner = resolveOwnerUserId()
        if (resolvedOwner.isNullOrEmpty()) {
            Log.e(TAG, "Unable to resolve active user")
            finish()
            return
        }
        ownerUserId = resolvedOwner

        observeChatRoster()
    }

    // onResume: ensures Nearby services stay active whenever the screen is visible.

    override fun onResume() {
        super.onResume()
        try {
            val nexa = Nexa.getInstance()
            if (!nexa.isSystemReady()) {
                nexa.initializeServicesAfterPermissions()
            }
            nexa.startNearbyServices()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to keep Nearby services active", e)
        }
    }

    private fun observeChatRoster() {
        lifecycleScope.launch {
            // Thanyani: collect repository + Nearby flows so UI stays reactive without manual refresh.
            refreshChats()

            launch {
                nearbyService.peerContacts.collectLatest {
                    refreshChats()
                }
            }

            launch {
                nearbyService.discoveredPeers.collectLatest {
                    refreshChats()
                }
            }
        }
    }

    private suspend fun refreshChats() {
        val peers = withContext(Dispatchers.IO) { databaseRepository.getPeerProfiles(ownerUserId) }
        updateChats(peers)
    }

    private suspend fun updateChats(peers: Collection<PeerProfile>) {
        val chats = if (peers.isEmpty()) {
            emptyList()
        } else {
            buildChats(peers)
        }

        allChatItems.clear()
        allChatItems.addAll(chats)
        currentSearchQuery = etSearch.text?.toString().orEmpty()
        applyChatFilters()
    }

    private suspend fun buildChats(peers: Collection<PeerProfile>): List<Chat> = withContext(Dispatchers.IO) {
        val localDeviceId = Nexa.currentDeviceID
        peers
            .filter { it.ownerUserId == ownerUserId }
            .map { profile ->
                val lastStored = databaseRepository
                    .getConversationMessages(localDeviceId, profile.deviceId, ownerUserId, limit = 1)
                    .lastOrNull()

                val lastMessageText = lastStored?.content?.let { decodeMessage(it) }
                val lastTimestamp = lastStored?.timestamp
                val resolvedName = profile.displayName?.takeIf { it.isNotBlank() }
                    ?: profile.deviceId.take(8)
                val isConnected = nearbyService.isDeviceConnected(profile.deviceId)

                Chat(
                    deviceId = profile.deviceId,
                    name = resolvedName,
                    lastMessage = lastMessageText,
                    lastTimestamp = lastTimestamp,
                    unreadCount = 0,
                    isConnected = isConnected,
                    visibility = profile.visibility,
                    lastSeen = profile.lastSeen
                )
            }
            .sortedWith(
                compareByDescending<Chat> { it.lastTimestamp ?: it.lastSeen ?: 0L }
                    .thenBy { it.name.lowercase(Locale.getDefault()) }
            )
    }

    private fun setupSearchFunctionality() {
        etSearch.addTextChangedListener(object : TextWatcher {
            // beforeTextChanged: required TextWatcher callback - unused because we react after changes.
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            // onTextChanged: TextWatcher stub; live filtering happens in afterTextChanged.
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            // afterTextChanged: updates current query and reapplies filters when search input changes.
            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s?.toString().orEmpty()
                applyChatFilters()
            }
        })

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
                true
            } else {
                false
            }
        }
    }

    private fun setupChatFilters() {
        chipChatFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            // Siyabonga: filter toggles share logic with search; we keep it cheap to avoid jank.
            val checkedId = checkedIds.firstOrNull() ?: R.id.chipChatAll
            activeChatFilter = when (checkedId) {
                R.id.chipChatConnected -> ChatFilter.CONNECTED
                R.id.chipChatTrusted -> ChatFilter.TRUSTED
                R.id.chipChatOpen -> ChatFilter.OPEN
                R.id.chipChatBlocked -> ChatFilter.BLOCKED
                else -> ChatFilter.ALL
            }
            applyChatFilters()
        }
    }

    private fun applyChatFilters() {
        val base = when (activeChatFilter) {
            ChatFilter.ALL -> allChatItems.toList()
            ChatFilter.CONNECTED -> allChatItems.filter { it.isConnected }
            ChatFilter.TRUSTED -> allChatItems.filter { it.visibility == PeerVisibility.TRUSTED }
            ChatFilter.OPEN -> allChatItems.filter { it.visibility == PeerVisibility.OPEN }
            ChatFilter.BLOCKED -> allChatItems.filter { it.visibility == PeerVisibility.BLOCKED }
        }

        val query = currentSearchQuery.lowercase(Locale.getDefault())
        // Siyabonga: local search avoids DB round-trips; dataset is already in memory.
        visibleChatItems.clear()
        if (query.isEmpty()) {
            visibleChatItems.addAll(base)
        } else {
            base.filterTo(visibleChatItems) { chat ->
                chat.name.lowercase(Locale.getDefault()).contains(query) ||
                    (chat.lastMessage?.lowercase(Locale.getDefault())?.contains(query) ?: false)
            }
        }

        chatAdapter.notifyDataSetChanged()
        toggleEmptyState(visibleChatItems.isEmpty())
    }

    private fun toggleEmptyState(showEmpty: Boolean) {
        val emptyState = findViewById<LinearLayout>(R.id.emptyState)
        emptyState.visibility = if (showEmpty) View.VISIBLE else View.GONE
        rvChats.visibility = if (showEmpty) View.GONE else View.VISIBLE
    }

    private fun resolveOwnerUserId(): String? {
        Nexa.getInstance().getActiveUserId()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        sessionManager.getActiveUserId()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            Nexa.getInstance().setActiveUserId(it)
            return it
        }
        return runBlocking { databaseRepository.getActiveOwnerUserId() }?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun decodeMessage(raw: ByteArray): String? = try {
        String(raw, Charsets.UTF_8).takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private fun setupBottomNavigation() {
        bottomNavigation.selectedItemId = R.id.nav_chats

        bottomNavigation.setOnItemSelectedListener { item ->
            // Thanyani: short-circuit when already selected to prevent redundant recreate.
            when (item.itemId) {
                R.id.nav_chats -> true
                R.id.navigation_add -> {
                    navigateToActivity(HomeScreen::class.java)
                    true
                }
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
    }

    private fun navigateToActivity(activityClass: Class<*>) {
        if (this::class.java != activityClass) {
            val intent = Intent(this, activityClass)
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    // onBackPressed: clears search if active; otherwise falls back to default behavior.

    override fun onBackPressed() {
        if (etSearch.text?.isNotEmpty() == true) {
            etSearch.text?.clear()
        } else {
            super.onBackPressed()
        }
    }

    // onChatClick: handles recycler row taps by opening the corresponding conversation.

    override fun onChatClick(chat: Chat) {
        openChatWithPeer(chat.name, chat.deviceId)
    }

    private fun openChatWithPeer(peerName: String, peerId: String) {
        val intent = Intent(this, InChatsActivity::class.java).apply {
            putExtra("FRIEND_NAME", peerName)
            putExtra("FRIEND_ID", peerId)
        }
        startActivity(intent)
    }
}










