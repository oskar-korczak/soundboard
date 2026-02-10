package com.soundboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer

class SoundPlayer(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private val lock = Object()

    companion object {
        private const val TARGET_VOLUME = 10
    }

    fun play(url: String) {
        synchronized(lock) {
            stop()
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, TARGET_VOLUME.coerceAtMost(maxVolume), 0)

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
