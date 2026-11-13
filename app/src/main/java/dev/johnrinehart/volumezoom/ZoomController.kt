package dev.johnrinehart.volumezoom

/**
 * Volume-key state machine. Pure Kotlin (no Android types) so it can be unit tested.
 *
 * Normal mode: key presses pass through to the system untouched, except a volume-down
 * press while the media stream sits at its lowest audible index (the "floor"). That press
 * is held for [Settings.doubleTapWindowMs]:
 *  - no second press: it is replayed as an ordinary volume-down (floor -> mute);
 *  - a second volume-down: zoom in. The stream stays at the floor and a software
 *    attenuation of one fine step is applied on top of it;
 *  - a volume-up: the held press is replayed first, then the up passes through.
 *
 * Zoomed (fine) mode, step 1..[Settings.steps]:
 *  - down: one step quieter. A fresh (non-repeat) down on the last step mutes and leaves
 *    fine mode;
 *  - up: one step louder. Up from step 1 leaves fine mode (back to plain floor volume);
 *  - any external change of the stream index away from the floor leaves fine mode.
 */
class ZoomController(private val host: Host) {

    enum class Key { UP, DOWN }

    data class Settings(
        val steps: Int = 10,
        val maxAttenuationDb: Float = 30f,
        val doubleTapWindowMs: Long = 350,
    ) {
        fun attenuationDb(step: Int): Float = -maxAttenuationDb * step / steps
    }

    interface Cancellable {
        fun cancel()
    }

    interface Host {
        /** Current media stream index. */
        fun streamIndex(): Int

        /** Lowest audible media stream index. */
        fun floorIndex(): Int

        /**
         * Whether volume keys currently target the media stream. [engaged] is true while
         * fine mode (or a pending floor press) is active, so a paused player keeps control.
         */
        fun mediaKeysActive(engaged: Boolean): Boolean

        fun settings(): Settings

        /** Ordinary one-step adjustment of the media stream, with system UI. */
        fun adjustStream(key: Key)

        /** Apply software gain in dB (<= 0); null removes the effect entirely. */
        fun setAttenuation(db: Float?)

        /** Show fine-mode feedback; step 0 means fine mode just ended. */
        fun showFine(step: Int, settings: Settings)

        fun schedule(delayMs: Long, action: () -> Unit): Cancellable
    }

    var fineStep: Int = 0
        private set

    private var pendingDown: Cancellable? = null

    val isEngaged: Boolean get() = fineStep > 0 || pendingDown != null

    /**
     * Handle a key-down event. Returns true if the event was consumed (the matching
     * key-up must then be consumed too).
     */
    fun onKeyDown(key: Key, isRepeat: Boolean): Boolean {
        if (!host.mediaKeysActive(isEngaged)) return false
        return when (key) {
            Key.DOWN -> onDown(isRepeat)
            Key.UP -> onUp()
        }
    }

    private fun onDown(isRepeat: Boolean): Boolean {
        val s = host.settings()
        if (fineStep > 0) {
            when {
                fineStep < s.steps -> setStep(fineStep + 1, s)
                !isRepeat -> {
                    // Past the last fine step: silence, like going below the floor normally.
                    fineStep = 0
                    host.adjustStream(Key.DOWN)
                    host.setAttenuation(null)
                    host.showFine(0, s)
                }
            }
            return true
        }

        if (pendingDown != null) {
            if (isRepeat) return true // held key while deciding; ignore
            pendingDown?.cancel()
            pendingDown = null
            setStep(1, s)
            return true
        }

        if (!isRepeat && host.streamIndex() == host.floorIndex()) {
            pendingDown = host.schedule(s.doubleTapWindowMs) {
                pendingDown = null
                host.adjustStream(Key.DOWN)
            }
            return true
        }
        return false
    }

    private fun onUp(): Boolean {
        pendingDown?.let {
            it.cancel()
            pendingDown = null
            host.adjustStream(Key.DOWN)
            return false // let the system apply the up after the replayed down
        }
        if (fineStep > 0) {
            val s = host.settings()
            if (fineStep == 1) exitFine(s) else setStep(fineStep - 1, s)
            return true
        }
        return false
    }

    /** Media stream index changed (from any source). */
    fun onStreamIndexChanged(index: Int) {
        if (fineStep > 0 && index != host.floorIndex()) exitFine(host.settings())
    }

    /** Settings changed while zoomed: re-apply the current step. */
    fun onSettingsChanged() {
        if (fineStep > 0) {
            val s = host.settings()
            setStep(fineStep.coerceAtMost(s.steps), s)
        }
    }

    /** Drop all state and remove the effect (service teardown). */
    fun reset() {
        pendingDown?.cancel()
        pendingDown = null
        fineStep = 0
        host.setAttenuation(null)
    }

    private fun setStep(step: Int, s: Settings) {
        fineStep = step
        host.setAttenuation(s.attenuationDb(step))
        host.showFine(step, s)
    }

    private fun exitFine(s: Settings) {
        fineStep = 0
        host.setAttenuation(null)
        host.showFine(0, s)
    }
}
