package com.runner.smartplayer.watch.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.runner.smartplayer.watch.R
import com.runner.smartplayer.watch.model.LocalTrack
import com.runner.smartplayer.watch.model.displayTitle

class PlaylistTrackAdapter(
    private val onItemClick: (LocalTrack) -> Unit = {},
) : ListAdapter<PlaylistTrackAdapter.TrackItem, PlaylistTrackAdapter.PlaylistTrackViewHolder>(DIFF) {

    data class TrackItem(
        val track: LocalTrack,
        val index: Int,
        val isCurrent: Boolean,
    )

    fun submitTracks(nextTracks: List<LocalTrack>, playingTrackId: String?) {
        submitList(
            nextTracks.mapIndexed { index, track ->
                TrackItem(
                    track = track,
                    index = index + 1,
                    isCurrent = track.id == playingTrackId,
                )
            }
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistTrackViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_track, parent, false)
        return PlaylistTrackViewHolder(view, onItemClick)
    }

    override fun onBindViewHolder(holder: PlaylistTrackViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class PlaylistTrackViewHolder(
        itemView: View,
        private val onItemClick: (LocalTrack) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val indexView = itemView.findViewById<TextView>(R.id.playlistIndex)
        private val titleView = itemView.findViewById<TextView>(R.id.playlistTitle)

        fun bind(item: TrackItem) {
            indexView.text = item.index.toString().padStart(2, '0')
            titleView.text = item.track.displayTitle()
            itemView.setOnClickListener {
                onItemClick(item.track)
            }

            if (item.isCurrent) {
                itemView.setBackgroundResource(R.drawable.playlist_item_active)
                titleView.setTextColor(ContextCompat.getColor(itemView.context, R.color.mint))
                indexView.setTextColor(ContextCompat.getColor(itemView.context, R.color.mint))
            } else {
                itemView.setBackgroundColor(Color.TRANSPARENT)
                titleView.setTextColor(ContextCompat.getColor(itemView.context, R.color.foam))
                indexView.setTextColor(ContextCompat.getColor(itemView.context, R.color.slate))
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TrackItem>() {
            override fun areItemsTheSame(oldItem: TrackItem, newItem: TrackItem): Boolean {
                return oldItem.track.id == newItem.track.id
            }

            override fun areContentsTheSame(oldItem: TrackItem, newItem: TrackItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
