package dev.johnrinehart.volumezoom

/**
 * Volume-key state machine. Pure Kotlin (no Android types) so it can be unit tested.
 *
 * Normal mode: key presses pass through to the system untouched, except a volume-down
 * press while the media stream sits at its lowest audible index (the "floor"):
 *  - released before [Settings.holdMs]: replayed as an ordinary volume-down (mute);
 *  - held for [Settings.holdMs]: zoom in. The stream stays at the floor, one fine step of
 *    software attenuation is applied, and further steps follow every
 *    [Settings.repeatMs] while the key stays down.
 *
 * Zoomed (fine) mode, step 1..[Settings.steps]:
 *  - down: one step quieter, repeating while held. A fresh press on the last step mutes
 *    and leaves fine mode; holding never mutes;
 *  - up: one step louder, repeating while held. Reaching step 0 leaves fine mode (back
 *    to plain floor volume) and stops there even if the key is still held;
 *  - any external change of the stream index away from the floor leaves fine mode.
 *
 * Accessibility key filters see only the physical down/up edges (no auto-repeat), so
 * holds and repeats are timed here.
 */
class ZoomController(private val host: Host) {

    enum class Key { UP, DOWN }

    data class Settings(
        val steps: Int = 10,
        val maxAttenuationDb: Float = 30f,
        val holdMs: Long = 400,
        val repeatMs: Long = 250,
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
         * fine mode (or a pending floor hold) is active.
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

        /** Monotonic clock in milliseconds. */
        fun nowMs(): Long
    }

    var fineStep: Int = 0
        private set

    /** Key currently held down that we consumed, if any. */
    private var held: Key? = null

    /** Held floor press that has not yet turned into a zoom. */
    private var floorPending = false
    private var timer: Cancellable? = null
    private val lastRelease = mutableMapOf<Key, Long>()

    val isEngaged: Boolean get() = fineStep > 0 || floorPending

    /**
     * Handle a key-down edge. Returns true if consumed; the caller must then also
     * deliver the matching key-up to [onKeyUp].
     */
    fun onKeyDown(key: Key): Boolean {
        if (held == key) return true // duplicate down edge while held
        if (!host.mediaKeysActive(isEngaged)) return false
        // Contact bounce: a press right after a release of the same key is not a new press.
        val bounced = lastRelease[key]?.let { host.nowMs() - it < BOUNCE_MS } ?: false
        if (bounced && (fineStep > 0 || floorPending)) return true

        val s = host.settings()
        return when (key) {
            Key.DOWN -> when {
                fineStep > 0 -> {
                    if (fineStep < s.steps) {
                        setStep(fineStep + 1, s)
                        hold(key, s)
                    } else {
                        // Past the last fine step: silence, like going below the floor.
                        muteFromFine(s)
                    }
                    true
                }
                host.streamIndex() == host.floorIndex() -> {
                    held = key
                    floorPending = true
                    timer = host.schedule(s.holdMs) {
                        floorPending = false
                        val now = host.settings()
                        setStep(1, now)
                        timer = host.schedule(now.repeatMs) { repeat(key) }
                    }
                    true
                }
                else -> false
            }
            Key.UP -> {
                if (fineStep == 0) return false
                if (fineStep == 1) {
                    exitFine(s)
                    held = key // swallow the rest of this press
                } else {
                    setStep(fineStep - 1, s)
                    hold(key, s)
                }
                true
            }
        }
    }

    /** Handle a key-up edge for a key whose down edge we consumed. */
    fun onKeyUp(key: Key) {
        lastRelease[key] = host.nowMs()
        if (held != key) return
        held = null
        timer?.cancel()
        timer = null
        if (floorPending) {
            // Short press at the floor: behave like a normal volume-down.
            floorPending = false
            host.adjustStream(Key.DOWN)
        }
    }

    /** Media stream index changed (from any source). */
    fun onStreamIndexChanged(index: Int) {
        if (fineStep > 0 && index != host.floorIndex()) {
            stopTimer()
            exitFine(host.settings())
        }
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
        stopTimer()
        held = null
        floorPending = false
        fineStep = 0
        host.setAttenuation(null)
    }

    private fun hold(key: Key, s: Settings) {
        held = key
        timer?.cancel()
        timer = host.schedule(s.holdMs) { repeat(key) }
    }

    /** Auto-repeat while [key] stays held. */
    private fun repeat(key: Key) {
        if (held != key || fineStep == 0) return
        val s = host.settings()
        val next = if (key == Key.DOWN) fineStep + 1 else fineStep - 1
        when {
            next > s.steps -> return // holding never mutes
            next == 0 -> {
                exitFine(s)
                return
            }
            else -> if (next != fineStep) setStep(next, s)
        }
        timer = host.schedule(s.repeatMs) { repeat(key) }
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    private fun setStep(step: Int, s: Settings) {
        fineStep = step
        host.setAttenuation(s.attenuationDb(step))
        host.showFine(step, s)
    }

    private fun muteFromFine(s: Settings) {
        stopTimer()
        fineStep = 0
        held = Key.DOWN
        host.adjustStream(Key.DOWN)
        host.setAttenuation(null)
        host.showFine(0, s)
    }

    private fun exitFine(s: Settings) {
        fineStep = 0
        host.setAttenuation(null)
        host.showFine(0, s)
    }

    companion object {
        /** Presses closer than this to the previous release are treated as key bounce. */
        const val BOUNCE_MS = 40L
    }
}
