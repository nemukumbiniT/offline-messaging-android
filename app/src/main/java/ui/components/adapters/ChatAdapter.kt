// ChatAdapter - Recycler adapter binding in-chat messages for legacy threads.
// Created by Siyabonga Popela Edited by Thanyani Nemukumbini.
// Date: 2025-09-05
package ui.components.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.example.nexausingnearby.R
import kotlin.text.removePrefix
import kotlin.text.startsWith

/** ChatAdapter:
 * - Decides row layout by prefix ("Me: " vs "Peer: ").
 * - Inflates bubble layouts and sets the text inside.
 */
class ChatAdapter(private val messages: List<String>) : BaseAdapter() {
    // getCount: returns total messages so list view knows how many rows to draw.
    override fun getCount(): Int = messages.size
    // getItem: retrieves the chat message at the requested position.
    override fun getItem(position: Int): Any = messages[position]
    // getItemId: uses position as stable id for compatibility with ListView.
    override fun getItemId(position: Int): Long = position.toLong()

    // getView: inflates or reuses row view and binds message text/time for display.

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val msg = messages[position]
        val isSent = msg.startsWith("Me: ")

        val inflater = LayoutInflater.from(parent.context)
        val view = if (isSent) {
            inflater.inflate(R.layout.item_message_sent, parent, false)
        } else {
            inflater.inflate(R.layout.item_message_received, parent, false)
        }
        val tv = view.findViewById<TextView>(R.id.messageText) //check if good replacement for tvmessages
        tv.text = msg.removePrefix("Me: ").removePrefix("Peer: ")
        return view


    }

}


