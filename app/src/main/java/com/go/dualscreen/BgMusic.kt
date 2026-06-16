package com.go.dualscreen

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlin.random.Random

/**
 * V5.6: 背景音乐管理器
 * - 随机播放古曲
 * - SoundPool音效叠加不打断背景音乐
 */
object BgMusic {
    private var mediaPlayer: MediaPlayer? = null
    private var enabled = false
    private var currentIndex = -1

    // V5.6: 高质量古曲 (Pixabay 免版权)
    private val musicResIds = intArrayOf(
        R.raw.music_wangyou,              // 忘憂 3:03
        R.raw.music_whisper_mountains,    // 空山箫语 4:00
        R.raw.mountain_spring             // Mountain Spring 4:26
    )

    @Volatile var volume = 0.5f  // V6.0: 默认50%
    
    fun updateVolume(vol: Float) { 
        volume = vol.coerceIn(0f, 1f)
        mediaPlayer?.setVolume(volume, volume)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        if (on) start(ctx) else stop()
    }

    fun isEnabled() = enabled

    private fun start(ctx: Context) {
        if (musicResIds.isEmpty()) return
        playRandom(ctx)
    }

    private fun playRandom(ctx: Context) {
        if (!enabled) return
        try {
            mediaPlayer?.release()
            // 随机选一首，避免重复
            var idx: Int
            do { idx = Random.nextInt(musicResIds.size) } while (idx == currentIndex && musicResIds.size > 1)
            currentIndex = idx

            mediaPlayer = MediaPlayer.create(ctx, musicResIds[idx])?.apply {
                isLooping = false
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                setVolume(volume, volume)  // 使用可调音量
                setOnCompletionListener {
                    it.release()
                    playRandom(ctx)  // 播完自动切换下一首
                }
                start()
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        currentIndex = -1
    }

    fun pause() {
        try { mediaPlayer?.pause() } catch (_: Exception) {}
    }

    fun resume() {
        try { if (enabled && mediaPlayer?.isPlaying == false) mediaPlayer?.start() } catch (_: Exception) {}
    }
}
