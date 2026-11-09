package com.bassplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class SongAdapter(
    private val onClick: (Int) -> Unit,
    private val onFavoriteClick: (Song) -> Unit
) : RecyclerView.Adapter<SongAdapter.SongHolder>() {

    private var items: List<Song> = emptyList()
    private var selectedId = -1L
    private var favorites: Set<Long> = emptySet()
    private var english = false

    fun setEnglish(enabled: Boolean) {
        english = enabled
        notifyDataSetChanged()
    }

    fun submit(list: List<Song>, favoriteIds: Set<Long>) {
        items = list
        favorites = favoriteIds
        notifyDataSetChanged()
    }

    fun setSelectedId(id: Long) {
        if (selectedId == id) return
        val previous = items.indexOfFirst { it.id == selectedId }
        selectedId = id
        val current = items.indexOfFirst { it.id == id }
        if (previous >= 0) notifyItemChanged(previous)
        if (current >= 0) notifyItemChanged(current)
    }

    fun notifyFavoriteChanged(id: Long) {
        val position = items.indexOfFirst { it.id == id }
        if (position >= 0) notifyItemChanged(position)
    }

    fun clearSelection() {
        val previous = items.indexOfFirst { it.id == selectedId }
        selectedId = -1L
        if (previous >= 0) notifyItemChanged(previous)
    }

    class SongHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view
        val cover: ImageView = view.findViewById(R.id.songCover)
        val title: TextView = view.findViewById(R.id.songTitle)
        val subtitle: TextView = view.findViewById(R.id.songArtist)
        val duration: TextView = view.findViewById(R.id.songDuration)
        val favorite: ImageButton = view.findViewById(R.id.songFavorite)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return SongHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: SongHolder, position: Int) {
        val song = items[position]
        holder.title.text = song.title
        holder.subtitle.text = if (song.album.isNotBlank()) {
            "${song.artist} · ${song.album}"
        } else {
            song.artist
        }
        holder.duration.text = formatTime(song.durationMs)
        holder.root.isActivated = song.id == selectedId
        AlbumArtLoader.bind(holder.cover, song, 320)

        val isFavorite = favorites.contains(song.id)
        val context = holder.itemView.context
        holder.favorite.setImageResource(
            if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star
        )
        holder.favorite.setColorFilter(
            ContextCompat.getColor(
                context,
                if (isFavorite) R.color.green_bright else R.color.subtext
            )
        )
        holder.favorite.contentDescription = if (english) {
            if (isFavorite) "Remove from favorites" else "Add to favorites"
        } else {
            if (isFavorite) "Eltávolítás a kedvencekből" else "Hozzáadás a kedvencekhez"
        }
        holder.favorite.setOnClickListener { onFavoriteClick(song) }

        holder.root.setOnClickListener { onClick(position) }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
