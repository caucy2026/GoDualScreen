package com.go.dualscreen

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

object SoundFX {

    /** 落子音效开关（默认开启） */
    @JvmField var stoneSoundEnabled = true

    /** 音色选择：0=标准，1=幽默方言 */
    @JvmField var voiceStyle = 0

    /** 播放raw资源语音文件（根据音色自动选择） */
    fun playVoice(context: Context, standardResId: Int) {
        var resId = standardResId
        if (voiceStyle == 1) {
            val resName = context.resources.getResourceEntryName(standardResId)
            val humorId = context.resources.getIdentifier("humor_$resName", "raw", context.packageName)
            if (humorId != 0) resId = humorId
        }
        try {
            val mp = MediaPlayer.create(context, resId)
            mp?.apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                setOnCompletionListener { it.release() }
                start()
            }
        } catch (_: Exception) {}
    }

    /** 臭鸡蛋砸碎音效 */
    fun playEggSplat() {
        Thread {
            try {
                val sr = 22050; val dur = 250; val n = (sr * dur / 1000)
                val buf = ShortArray(n); val rng = Random(System.nanoTime())
                for (i in 0 until n) {
                    val t = i.toDouble() / sr
                    val env = Math.exp(-t * 30.0)
                    val noise = (rng.nextDouble() - 0.5) * 2.0
                    val thud = sin(2.0 * PI * 120.0 * t) * 0.5
                    buf[i] = ((noise * 0.6 + thud) * env * 25000).toInt().coerceIn(-32768, 32767).toShort()
                }
                val at = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(buf.size * 2)
                    .build()
                at.play(); at.write(buf, 0, buf.size)
                Thread.sleep(270); at.stop(); at.release()
            } catch (_: Exception) {}
        }.start()
    }

    /**
     * 模拟棋子落盘音效
     */
    fun playStoneSound() {
        if (!stoneSoundEnabled) return
        Thread {
            try {
                val sampleRate = 22050
                val totalMs = 120
                val totalSamples = (sampleRate * totalMs / 1000)
                val buffer = ShortArray(totalSamples)
                val rng = Random(System.nanoTime())

                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / sampleRate
                    val impactEnv = Math.exp(-t * 200.0)
                    val woodEnv = Math.exp(-t * 25.0) * 0.4
                    val noise = (rng.nextDouble() - 0.5) * 2.0
                    val resonance = sin(2.0 * PI * 400.0 * t) * 0.5 + sin(2.0 * PI * 600.0 * t) * 0.3
                    val signal = noise * impactEnv * 0.7 + resonance * woodEnv
                    buffer[i] = (signal * 30000).toInt().coerceIn(-32768, 32767).toShort()
                }

                val at = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(buffer.size * 2)
                    .build()
                at.play()
                at.write(buffer, 0, buffer.size)
                Thread.sleep(140)
                at.stop(); at.release()
            } catch (_: Exception) {}
        }.start()
    }

    /** 提子音效：短促有力的撞击声 */
    fun playCaptureSound() {
        Thread {
            try {
                val sr = 22050; val dur = 200; val n = (sr * dur / 1000)
                val buf = ShortArray(n); val rng = Random(System.nanoTime())
                for (i in 0 until n) {
                    val t = i.toDouble() / sr
                    val env = Math.exp(-t * 35.0)
                    val click = sin(2.0 * PI * 800.0 * t) * 0.4 + sin(2.0 * PI * 1200.0 * t) * 0.3
                    val noise = (rng.nextDouble() - 0.5) * 0.3
                    buf[i] = ((click + noise) * env * 28000).toInt().coerceIn(-32768, 32767).toShort()
                }
                val at = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(buf.size * 2).build()
                at.play(); at.write(buf, 0, buf.size)
                Thread.sleep(220); at.stop(); at.release()
            } catch (_: Exception) {}
        }.start()
    }

    fun playWinSound() {
        Thread {
            try {
                val sampleRate = 22050
                val durationMs = 400
                val samples = (sampleRate * durationMs / 1000)
                val buffer = ShortArray(samples)
                for (i in 0 until samples) {
                    val t = i.toDouble() / sampleRate
                    val envelope = Math.exp(-t * 4.0)
                    val freq = 523.0 + t * 500.0
                    val signal = envelope * sin(2.0 * PI * freq * t)
                    buffer[i] = (signal * 20000).toInt().coerceIn(-32768, 32767).toShort()
                }
                val at = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(buffer.size * 2)
                    .build()
                at.play()
                at.write(buffer, 0, buffer.size)
                Thread.sleep(420)
                at.stop(); at.release()
            } catch (_: Exception) {}
        }.start()
    }

    fun playLoseSound() {
        Thread {
            try {
                val sampleRate = 22050
                val durationMs = 500
                val samples = (sampleRate * durationMs / 1000)
                val buffer = ShortArray(samples)
                for (i in 0 until samples) {
                    val t = i.toDouble() / sampleRate
                    val envelope = Math.exp(-t * 3.0)
                    val freq = 600.0 - t * 300.0
                    val signal = envelope * sin(2.0 * PI * maxOf(freq, 100.0) * t)
                    buffer[i] = (signal * 20000).toInt().coerceIn(-32768, 32767).toShort()
                }
                val at = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(buffer.size * 2)
                    .build()
                at.play()
                at.write(buffer, 0, buffer.size)
                Thread.sleep(520)
                at.stop(); at.release()
            } catch (_: Exception) {}
        }.start()
    }

    /** 欢快音效（LOGO触摸触发） */
    fun playCheerfulSound() {
        Thread {
            try {
                val sampleRate = 22050
                val durationMs = 3000
                val samples = (sampleRate * durationMs / 1000)
                val buffer = ShortArray(samples)
                val notes = doubleArrayOf(523.0, 587.0, 659.0, 784.0, 880.0, 1047.0)
                for (i in 0 until samples) {
                    val t = i.toDouble() / sampleRate
                    val noteIdx = (t / 0.45).toInt() % notes.size
                    val noteStart = (noteIdx * 0.45)
                    val noteT = t - noteStart
                    val noteEnv = Math.exp(-noteT * 3.0)
                    val masterEnv = Math.exp(-t * 1.2)
                    val f = notes[noteIdx]
                    val sig = sin(2.0 * PI * f * t) * 0.5 +
                            sin(2.0 * PI * f * 2.01 * t) * 0.25 +
                            sin(2.0 * PI * f * 3.02 * t) * 0.12
                    buffer[i] = (sig * noteEnv * masterEnv * 28000).toInt().coerceIn(-32768, 32767).toShort()
                }
                val at = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(buffer.size * 2)
                    .build()
                at.play()
                at.write(buffer, 0, buffer.size)
                Thread.sleep(3100)
                at.stop(); at.release()
            } catch (_: Exception) {}
        }.start()
    }

    fun release() {
        // 每个音效方法使用本地 AudioTrack/MediaPlayer，无需显式释放
    }
}
