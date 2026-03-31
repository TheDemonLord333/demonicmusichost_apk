package com.demonicmusichost.app.ui.session

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.demonicmusichost.app.R
import com.demonicmusichost.app.data.model.Participant
import com.demonicmusichost.app.databinding.ItemParticipantBinding

class ParticipantAdapter(
    private val onKickClick: (Participant) -> Unit
) : ListAdapter<Participant, ParticipantAdapter.PVH>(DIFF) {

    var isHostMode: Boolean = false

    inner class PVH(private val b: ItemParticipantBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(p: Participant) {
            b.tvUsername.text = p.username
            b.tvAvatar.text = p.username.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            b.tvRole.text = if (p.isHost) b.root.context.getString(R.string.host_label)
            else b.root.context.getString(R.string.guest_label)
            b.btnKick.visibility =
                if (isHostMode && !p.isHost) android.view.View.VISIBLE else android.view.View.GONE
            b.btnKick.setOnClickListener { onKickClick(p) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        PVH(ItemParticipantBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: PVH, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Participant>() {
            override fun areItemsTheSame(a: Participant, b: Participant) = a.socketId == b.socketId
            override fun areContentsTheSame(a: Participant, b: Participant) = a == b
        }
    }
}
