package dev.johnrinehart.volumezoom

import android.media.audiofx.DynamicsProcessing
import android.util.Log

/**
 * Flat software gain on the global output mix (audio session 0) via the input-gain stage of
 * [DynamicsProcessing]. The effect only exists while fine mode is engaged, so normal playback keeps
 * its untouched (and offload-capable) audio path.
 */
class Attenuator(private val onControlLost: () -> Unit) {

    private var effect: DynamicsProcessing? = null

    /** Apply [db] (<= 0). Returns false if the effect could not be created. */
    fun set(db: Float): Boolean {
        val dp = effect ?: create() ?: return false
        return try {
            dp.setInputGainAllChannelsTo(db)
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "setInputGain($db) failed", e)
            false
        }
    }

    fun release() {
        effect?.let {
            runCatching { it.enabled = false }
            it.release()
        }
        effect = null
    }

    private fun create(): DynamicsProcessing? =
        try {
            val config =
                DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        CHANNELS,
                        /* preEqInUse */ false,
                        0,
                        /* mbcInUse */ false,
                        0,
                        /* postEqInUse */ false,
                        0,
                        /* limiterInUse */ false,
                    )
                    .build()
            DynamicsProcessing(PRIORITY, GLOBAL_SESSION, config).also { dp ->
                dp.enabled = true
                dp.setControlStatusListener { _, granted -> if (!granted) onControlLost() }
                effect = dp
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "DynamicsProcessing on session 0 unavailable", e)
            null
        }

    private companion object {
        const val TAG = "VolumeZoom"
        const val GLOBAL_SESSION = 0
        const val PRIORITY = Int.MAX_VALUE
        const val CHANNELS = 2
    }
}
