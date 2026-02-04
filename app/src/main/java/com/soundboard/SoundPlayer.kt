package com.soundboard

import android.media.AudioAttributes
import android.media.MediaPlayer

class SoundPlayer {

    private var mediaPlayer: MediaPlayer? = null
    private val lock = Object()

    fun play(url: String) {
        synchronized(lock) {
            stop()

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(url)
                setOnPreparedListener { mp ->
                    mp.start()
                }
                setOnCompletionListener { mp ->
                    mp.reset()
                }
                setOnErrorListener { mp, what, extra ->
                    mp.reset()
                    true
                }
                prepareAsync()
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.reset()
            }
        }
    }

    fun isPlaying(): Boolean {
        return synchronized(lock) {
            try {
                mediaPlayer?.isPlaying == true
            } catch (e: Exception) {
                false
            }
        }
    }

    fun release() {
        synchronized(lock) {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }
}
