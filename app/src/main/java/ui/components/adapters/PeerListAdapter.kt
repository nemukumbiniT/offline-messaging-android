// PeerListAdapter - Recycler adapter used for peer selection lists.
// Created by Siyabonga Popela Edited by Thanyani Nemukumbini.
// Date: 2025-09-09
// PeerListAdapter.kt

/*
PeerListAdapter is going to be used to display ""nearby peers" (Connected endpoints) in a ListView.
Use on Home Screen (to help show automatic discovery)
 */
package ui.components.adapters

import android.R
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import DataLayer.PeerEntity as Peer

class PeerListAdapter(
    context: Context,
    private val peers: MutableList<Peer>
) : ArrayAdapter<Peer>(context, 0, peers) {

    fun updatePeers(newPeers: Collection<Peer>) {
        peers.clear()
        peers.addAll(newPeers)
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val peer = getItem(position)
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.simple_list_item_2, parent, false)

        val title = view.findViewById<TextView>(R.id.text1)
        val subtitle = view.findViewById<TextView>(R.id.text2)

        title.text = peer?.endpointName ?: "Unknown"
        subtitle.text = peer?.endpointId.toString()

        return view
    }
}



