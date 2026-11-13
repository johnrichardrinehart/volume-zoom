package dev.johnrinehart.volumezoom

import android.media.audiofx.DynamicsProcessing
import android.util.Log

/**
 * Flat software gain on the global output mix (audio session 0) via the input-gain stage of
 * [DynamicsProcessing]. A limiter at the end of the chain catches peaks pushed past full scale by
 * positive gain; attenuated audio never reaches its threshold. The effect only exists while a zone
 * is active, so normal playback keeps its untouched (and offload-capable) audio path.
 */
class Gain(private val onControlLost: () -> Unit) {

    private var effect: DynamicsProcessing? = null

    /** Apply [db]. Returns false if the effect could not be created. */
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

    /**
     * Creates the effect with a limiter, or without one if the device rejects that configuration,
     * so attenuation keeps working either way.
     */
    private fun create(): DynamicsProcessing? =
        (tryCreate(limiter = true) ?: tryCreate(limiter = false))?.also { dp ->
            dp.enabled = true
            dp.setControlStatusListener { _, granted -> if (!granted) onControlLost() }
            effect = dp
        }

    private fun tryCreate(limiter: Boolean): DynamicsProcessing? {
        var dp: DynamicsProcessing? = null
        return try {
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
                        limiter,
                    )
                    .build()
            dp = DynamicsProcessing(PRIORITY, GLOBAL_SESSION, config)
            if (limiter) {
                dp.setLimiterAllChannelsTo(
                    DynamicsProcessing.Limiter(
                        /* inUse */ true,
                        /* enabled */ true,
                        /* linkGroup */ 0,
                        LIMITER_ATTACK_MS,
                        LIMITER_RELEASE_MS,
                        LIMITER_RATIO,
                        LIMITER_THRESHOLD_DB,
                        /* postGain */ 0f,
                    )
                )
            }
            Log.i(TAG, "DynamicsProcessing on session 0, limiter=$limiter")
            dp
        } catch (e: RuntimeException) {
            Log.e(TAG, "DynamicsProcessing on session 0 (limiter=$limiter) unavailable", e)
            dp?.release()
            null
        }
    }

    private companion object {
        const val TAG = "VolumeZoom"
        const val GLOBAL_SESSION = 0
        const val PRIORITY = Int.MAX_VALUE
        const val CHANNELS = 2
        const val LIMITER_ATTACK_MS = 1f
        const val LIMITER_RELEASE_MS = 60f
        const val LIMITER_RATIO = 10f
        const val LIMITER_THRESHOLD_DB = -1f
    }
}
