package com.gpm.virtual.xposed.overlay

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.gpm.virtual.BuildConfig

/**
 * Memutar suara notifikasi orderan bawaan Grab (new_job_custom).
 */
object GrabSoundPlayer {

    private const val TAG = "GMP:GrabSound"
    private var soundPool: SoundPool? = null
    private var soundId: Int = 0
    private var isLoaded = false

    fun init(context: Context) {
        if (soundPool != null) return

        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            soundPool = SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(audioAttributes)
                .build()

            // Mencari resource ID secara dinamis berdasarkan nama string
            val resId = context.resources.getIdentifier(
                "new_job_custom", "raw", context.packageName
            )

            if (resId != 0) {
                soundId = soundPool?.load(context, resId, 1) ?: 0
                soundPool?.setOnLoadCompleteListener { _, _, status ->
                    if (status == 0) {
                        isLoaded = true
                        if (BuildConfig.DEBUG) Log.d(TAG, "✅ Sound new_job_custom loaded")
                    }
                }
            } else {
                if (BuildConfig.DEBUG) Log.w(TAG, "⚠️ Resource new_job_custom not found")
            }
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Error init SoundPool: ${t.message}")
        }
    }

    fun play() {
        if (isLoaded && soundId != 0) {
            soundPool?.play(soundId, 1f, 1f, 1, 0, 1f)
            if (BuildConfig.DEBUG) Log.i(TAG, "🔊 Playing new_job_custom sound")
        }
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        isLoaded = false
    }
}