// NearbyPeersAdapter - Recycler adapter for discovered peers list.
// Created by Siyabonga Popela Edited by Tasima Hapazari and Thanyani Nemukumbini.
// Date: 2025-09-08
package ui.components.adapters

import DataLayer.models.PeerVisibility
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.nexausingnearby.R
import com.google.android.material.chip.Chip
import ui.components.models.NearbyPeerItem

class NearbyPeersAdapter(
    private var peers: List<NearbyPeerItem>,
    private val listener: OnPeerClickListener
) : RecyclerView.Adapter<NearbyPeersAdapter.PeerViewHolder>() {

    interface OnPeerClickListener {
        // onPeerClick: forwards click events to listener for handling.
        fun onPeerClick(peer: NearbyPeerItem)
    }

    inner class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvPeerName: TextView = itemView.findViewById(R.id.tvPeerName)
        val tvPeerDetails: TextView = itemView.findViewById(R.id.tvPeerDetails)
        val ivPeerIcon: ImageView = itemView.findViewById(R.id.ivPeerIcon)
        val chipVisibility: Chip = itemView.findViewById(R.id.chipVisibility)
    }

    // onCreateViewHolder: inflates peer card layout and wraps it in a view holder.

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_nearby_peer, parent, false)
        return PeerViewHolder(view)
    }

    // onBindViewHolder: binds peer details and action button state for each row.

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        val peer = peers[position]
        val context = holder.itemView.context

        holder.tvPeerName.text = peer.displayName
        holder.tvPeerDetails.text = peer.details ?: ""
        holder.tvPeerDetails.isVisible = !peer.details.isNullOrEmpty()

        holder.chipVisibility.isVisible = true
        holder.chipVisibility.text = visibilityLabel(context, peer.visibility)
        val (background, foreground) = visibilityColors(context, peer.visibility)
        holder.chipVisibility.chipBackgroundColor = ColorStateList.valueOf(background)
        holder.chipVisibility.setTextColor(foreground)

        val iconColorRes = when {
            peer.isConnected -> R.color.success_color
            peer.visibility == PeerVisibility.BLOCKED -> R.color.error_color
            else -> R.color.text_secondary
        }
        holder.ivPeerIcon.setColorFilter(ContextCompat.getColor(context, iconColorRes))
        holder.itemView.alpha = if (peer.isConnected) 1f else 0.85f

        holder.itemView.setOnClickListener {
            // Tasima: Trigger hands control back to HomeScreen so connection pipeline stays central.
            listener.onPeerClick(peer)
        }
    }

    // getItemCount: reports number of peers currently presented.

    override fun getItemCount(): Int = peers.size

    // updatePeers: replaces dataset and notifies recycler about data changes.

    fun updatePeers(newPeers: List<NearbyPeerItem>) {
        peers = newPeers
        notifyDataSetChanged()
    }

    companion object {
        private fun visibilityLabel(context: android.content.Context, visibility: PeerVisibility): String {
            return when (visibility) {
                PeerVisibility.TRUSTED -> context.getString(R.string.peer_status_trusted)
                PeerVisibility.BLOCKED -> context.getString(R.string.peer_status_blocked)
                PeerVisibility.OPEN -> context.getString(R.string.peer_status_open)
            }
        }

        private fun visibilityColors(
            context: android.content.Context,
            visibility: PeerVisibility
        ): Pair<Int, Int> {
            val backgroundRes = when (visibility) {
                PeerVisibility.TRUSTED -> R.color.visibility_trusted_bg
                PeerVisibility.OPEN -> R.color.visibility_open_bg
                PeerVisibility.BLOCKED -> R.color.visibility_blocked_bg
            }
            val foregroundRes = when (visibility) {
                PeerVisibility.TRUSTED -> R.color.visibility_trusted_fg
                PeerVisibility.OPEN -> R.color.visibility_open_fg
                PeerVisibility.BLOCKED -> R.color.visibility_blocked_fg
            }
            return ContextCompat.getColor(context, backgroundRes) to
                ContextCompat.getColor(context, foregroundRes)
        }
    }
}



