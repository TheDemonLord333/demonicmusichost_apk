package com.demonicmusichost.app.ui.session

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.demonicmusichost.app.R
import com.demonicmusichost.app.data.model.Track
import com.demonicmusichost.app.databinding.ItemQueueTrackBinding
import java.util.concurrent.TimeUnit

class QueueAdapter(
    private val onRemoveClick: (Int) -> Unit
) : ListAdapter<Track, QueueAdapter.TrackViewHolder>(DIFF) {

    var currentTrackIndex: Int = -1
        set(value) {
            val old = field
            field = value
            if (old != value) {
                if (old >= 0 && old < itemCount) notifyItemChanged(old)
                if (value >= 0 && value < itemCount) notifyItemChanged(value)
            }
        }

    var isHostMode: Boolean = false

    inner class TrackViewHolder(private val binding: ItemQueueTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(track: Track, position: Int) {
            binding.tvTrackTitle.text = track.title.ifBlank { "Unbekannter Titel" }
            binding.tvTrackArtist.text = track.artist.ifBlank { track.addedBy }
            binding.tvAddedBy.text = binding.root.context.getString(R.string.added_by, track.addedBy)
            binding.tvDuration.text = formatDuration(track.duration)

            // Source badge colour
            val badgeColor = when (track.source) {
                "spotify" -> R.color.spotify_green
                "youtube" -> R.color.youtube_red
                else -> R.color.dmh_accent
            }
            binding.viewSourceDot.setBackgroundColor(
                ContextCompat.getColor(binding.root.context, badgeColor)
            )

            // Highlight current track
            val isActive = position == currentTrackIndex
            binding.root.alpha = if (isActive) 1f else 0.75f
            binding.ivNowPlaying.visibility =
                if (isActive) android.view.View.VISIBLE else android.view.View.GONE

            // Thumbnail
            if (track.thumbnail.isNotBlank()) {
                Glide.with(binding.ivThumbnail)
                    .load(track.thumbnail)
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .into(binding.ivThumbnail)
            } else {
                binding.ivThumbnail.setImageResource(R.drawable.ic_music_note)
            }

            // Remove button (host only)
            binding.btnRemove.visibility = if (isHostMode) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnRemove.setOnClickListener { onRemoveClick(position) }
        }

        private fun formatDuration(ms: Long): String {
            if (ms <= 0) return ""
            val min = TimeUnit.MILLISECONDS.toMinutes(ms)
            val sec = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
            return "%d:%02d".format(min, sec)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val binding = ItemQueueTrackBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TrackViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Track>() {
            override fun areItemsTheSame(a: Track, b: Track) = a.id == b.id
            override fun areContentsTheSame(a: Track, b: Track) = a == b
        }
    }
}
