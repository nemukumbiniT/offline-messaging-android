// InChatsActivity - Activity rendering message threads and interaction controls.
// Created by Siyabonga Popela. Edited by Thanyani Nemukumbini.
// Date: 2025-09-21
package ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.asylo.nexa.data.MessageEntity
import com.example.nexausingnearby.Nexa
import com.example.nexausingnearby.R
import com.example.nexausingnearby.service.NexaMessagingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ui.components.adapters.MessageAdapter
import ui.components.models.Message

class InChatsActivity : AppCompatActivity() {

    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var btnSend: ImageButton

    private lateinit var messageAdapter: MessageAdapter

    private val deviceID = Nexa.currentDeviceID
    private lateinit var recID: String

    private val uiMessages = mutableListOf<Message>()
    private val seenMessageIds = LinkedHashSet<String>()

    private var messagingService: NexaMessagingService? = null
    private var serviceBound = false
    private var messageCollectorJob: Job? = null
    private var historyJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        // onServiceConnected: captures the bound messaging service and starts observers.
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? NexaMessagingService.MessagingBinder ?: return
            attachMessagingService(binder.getService())
        }

        // onServiceDisconnected: clears service references when connection drops.

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            detachMessagingService()
        }
    }

    companion object {
        private const val TAG = "InChatsActivity"
    }

    // onCreate: initializes UI, resolves conversation extras, and binds recycler + actions.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_in_chats)

        NexaMessagingService.start(this)

        val friendName = intent.getStringExtra("FRIEND_NAME") ?: "Friend"
        recID = intent.getStringExtra("FRIEND_ID") ?: ""

        if (recID.isEmpty()) {
            Log.e(TAG, "No recipient ID provided")
            finish()
            return
        }

        findViewById<TextView>(R.id.userName).text = friendName

        val backButton = findViewById<ImageButton>(R.id.btnBack)
        backButton.setOnClickListener { finish() }

        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        messageInput = findViewById(R.id.messageInput)
        btnSend = findViewById(R.id.btnSend)

        messageAdapter = MessageAdapter(uiMessages)
        // Siyabonga: reuse simple adapter since render logic is handled via layouts.
        chatRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@InChatsActivity).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }

        btnSend.setOnClickListener {
            // Thanyani: keep validation lightweight; actual send decides on trusted/open path.
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
            }
        }
    }

    // onStart: binds to messaging service so live updates stream into the screen.

    override fun onStart() {
        super.onStart()
        Intent(this, NexaMessagingService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            serviceBound = true
        }
    }

    // onStop: clears active conversation and unbinds service to avoid leaks.

    override fun onStop() {
        super.onStop()
        messagingService?.setActiveConversation(null)
        // Tasima: clears foreground flag so notifications resume once user leaves the chat.
        messageCollectorJob?.cancel()
        historyJob?.cancel()
        if (serviceBound) {
            runCatching { unbindService(serviceConnection) }
            serviceBound = false
        }
    }

    // onDestroy: cancels running jobs and detaches messaging service on teardown.

    override fun onDestroy() {
        super.onDestroy()
        messageCollectorJob?.cancel()
        historyJob?.cancel()
        detachMessagingService()
    }

    private fun attachMessagingService(service: NexaMessagingService) {
        messagingService = service
        service.setActiveConversation(recID)
        observeIncomingMessages(service)
        loadExistingMessages(service)
    }

    private fun detachMessagingService() {
        messagingService = null
    }

    private fun sendMessage(text: String) {
        val service = messagingService
        // Thanyani: guard for binding delays—UI stays responsive even if service spins up slower.
        if (service == null) {
            Toast.makeText(this, R.string.toast_messaging_service_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val requireTrusted = service.requireTrustedMessaging()
        service.sendTextMessage(recID, text, requireTrusted)
        messageInput.text?.clear()
    }

    private fun observeIncomingMessages(service: NexaMessagingService) {
        messageCollectorJob?.cancel()
        messageCollectorJob = lifecycleScope.launch {
            // Tasima: stream emits for every conversation; filter here to keep load off service.
            service.messageStream().collectLatest { dispatch ->
                if (dispatch.conversationId != recID) {
                    return@collectLatest
                }
                handleIncomingMessage(dispatch.entity)
            }
        }
    }

    private fun handleIncomingMessage(messageEntity: MessageEntity) {
        if (!shouldDisplayMessage(messageEntity)) {
            return
        }
        val messageKey = messageEntity.messageID.ifBlank { "${messageEntity.senderID}-${messageEntity.timestamp}" }
        if (!seenMessageIds.add(messageKey)) {
            return
        }
        val messageContent = decodeMessageContent(messageEntity.content)
        val uiMessage = Message(
            content = messageContent,
            isSentByUser = messageEntity.senderID == deviceID,
            timestamp = messageEntity.timestamp
        )
        uiMessages.add(uiMessage)
        messageAdapter.notifyItemInserted(uiMessages.size - 1)
        chatRecyclerView.scrollToPosition(uiMessages.size - 1)
        Log.d(TAG, "Message added to UI: ${if (uiMessage.isSentByUser) "sent" else "received"}")


    }

    private fun loadExistingMessages(service: NexaMessagingService) {
        if (uiMessages.isNotEmpty()) {
            return
        }
        historyJob?.cancel()
        historyJob = lifecycleScope.launch {
            // Thanyani: hydrate UI once so we do not replay the same history on rotation.
            try {
                val history = service.getConversationHistory(recID)
                if (history.isEmpty()) {
                    Log.d(TAG, "No stored messages for conversation with $recID")
                    return@launch
                }
                history.sortedBy { it.timestamp }.forEach { stored ->
                    val messageKey = stored.messageId.ifBlank { "${stored.senderId}-${stored.timestamp}" }
                    if (seenMessageIds.add(messageKey)) {
                        val uiMessage = Message(
                            content = decodeMessageContent(stored.content),
                            isSentByUser = stored.isSentByLocalDevice,
                            timestamp = stored.timestamp
                        )
                        uiMessages.add(uiMessage)
                    }
                }
                messageAdapter.notifyDataSetChanged()
                chatRecyclerView.scrollToPosition(uiMessages.size - 1)
                Log.d(TAG, "Loaded ${history.size} stored messages for conversation with $recID")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load existing messages", e)
            }
        }
    }

    private fun shouldDisplayMessage(messageEntity: MessageEntity): Boolean {
        if (messageEntity.senderID == recID && messageEntity.receiverID == deviceID) {
            // Siyabonga: only display messages that belong to this dyad; group messages use different screen.
            return true
        }
        if (messageEntity.senderID == deviceID && messageEntity.receiverID == recID) {
            return true
        }
        return false
    }

    private fun decodeMessageContent(content: ByteArray?): String {
        if (content == null) {
            return ""
        }
        return try {
            String(content, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to decode message content", e)
            "[binary data]"
        }
    }
}





