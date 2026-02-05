package com.soundboard

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class RecentSoundsAdapter(
    private val onSoundClick: (RecentSound) -> Unit
) : RecyclerView.Adapter<RecentSoundsAdapter.ViewHolder>() {

    private var sounds: List<RecentSound> = emptyList()

    fun submitList(newSounds: List<RecentSound>) {
        sounds = newSounds
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sound_button, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(sounds[position])
    }

    override fun getItemCount() = sounds.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val cardView: MaterialCardView = view.findViewById(R.id.cardView)
        private val nameText: TextView = view.findViewById(R.id.soundNameText)

        fun bind(sound: RecentSound) {
            nameText.text = sound.displayName
            cardView.setCardBackgroundColor(Color.parseColor(sound.color))
            cardView.setOnClickListener { onSoundClick(sound) }
        }
    }
}
