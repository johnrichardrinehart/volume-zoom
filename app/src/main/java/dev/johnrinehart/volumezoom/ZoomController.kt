package dev.johnrinehart.volumezoom

/**
 * Volume-key state machine. Pure Kotlin (no Android types) so it can be unit tested.
 *
 * Normal mode: key presses pass through to the system untouched, except a volume-down
 * press while the media stream sits at its lowest audible index (the "floor"):
 *  - released before [Settings.holdMs]: replayed as an ordinary volume-down (mute);
 *  - held for [Settings.holdMs]: zoom in at fine step 0 (0 dB, i.e. plain floor
 *    volume). The hold only switches modes; it does not step further.
 *
 * Zoomed (fine) mode, step 0..[Settings.steps], step k = k * maxAttenuation / steps dB down:
 *  - down: one step quieter, repeating while held. A fresh press on the last step mutes
 *    and leaves fine mode; holding never mutes;
 *  - up: one step louder, repeating while held, stopping at step 0. A fresh up press at
 *    step 0 zooms out: fine mode ends and the press raises the system volume as usual;
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

        /** Apply software gain in dB (< 0); null removes the effect entirely. */
        fun setAttenuation(db: Float?)

        /** Show fine-mode feedback for [step] (0 = zoomed in, no attenuation yet). */
        fun showFine(step: Int, settings: Settings)

        /** Fine mode ended. */
        fun hideFine()

        fun schedule(delayMs: Long, action: () -> Unit): Cancellable

        /** Monotonic clock in milliseconds. */
        fun nowMs(): Long
    }

    var zoomed: Boolean = false
        private set
    var fineStep: Int = 0
        private set

    /** Key currently held down that we consumed, if any. */
    private var held: Key? = null

    /** Held floor press that has not yet turned into a zoom. */
    private var floorPending = false
    private var timer: Cancellable? = null
    private val lastRelease = mutableMapOf<Key, Long>()

    val isEngaged: Boolean get() = zoomed || floorPending

    /**
     * Handle a key-down edge. Returns true if consumed; the caller must then also
     * deliver the matching key-up to [onKeyUp].
     */
    fun onKeyDown(key: Key): Boolean {
        if (held == key) return true // duplicate down edge while held
        if (!host.mediaKeysActive(isEngaged)) return false
        // Contact bounce: a press right after a release of the same key is not a new press.
        val bounced = lastRelease[key]?.let { host.nowMs() - it < BOUNCE_MS } ?: false
        if (bounced && isEngaged) return true

        val s = host.settings()
        if (zoomed) {
            when (key) {
                Key.DOWN ->
                    if (fineStep < s.steps) {
                        setStep(fineStep + 1, s)
                        hold(key, s)
                    } else {
                        muteFromFine() // past the last fine step: silence
                    }
                Key.UP ->
                    if (fineStep > 0) {
                        setStep(fineStep - 1, s)
                        hold(key, s)
                    } else {
                        exitFine()
                        return false // zoom out: the system raises the volume
                    }
            }
            return true
        }

        if (key == Key.DOWN && host.streamIndex() == host.floorIndex()) {
            held = key
            floorPending = true
            timer = host.schedule(s.holdMs) {
                timer = null
                floorPending = false
                zoomed = true
                setStep(0, host.settings())
            }
            return true
        }
        return false
    }

    /** Handle a key-up edge for a key whose down edge we consumed. */
    fun onKeyUp(key: Key) {
        lastRelease[key] = host.nowMs()
        if (held != key) return
        held = null
        stopTimer()
        if (floorPending) {
            // Short press at the floor: behave like a normal volume-down.
            floorPending = false
            host.adjustStream(Key.DOWN)
        }
    }

    /** Media stream index changed (from any source). */
    fun onStreamIndexChanged(index: Int) {
        if (zoomed && index != host.floorIndex()) exitFine()
    }

    /** Settings changed while zoomed: re-apply the current step. */
    fun onSettingsChanged() {
        if (zoomed) {
            val s = host.settings()
            setStep(fineStep.coerceAtMost(s.steps), s)
        }
    }

    /** Drop all state and remove the effect (service teardown). */
    fun reset() {
        stopTimer()
        held = null
        floorPending = false
        zoomed = false
        fineStep = 0
        host.setAttenuation(null)
    }

    private fun hold(key: Key, s: Settings) {
        held = key
        stopTimer()
        timer = host.schedule(s.holdMs) { repeat(key) }
    }

    /** Auto-repeat while [key] stays held; stops at either end of the fine range. */
    private fun repeat(key: Key) {
        timer = null
        if (held != key || !zoomed) return
        val s = host.settings()
        val next = if (key == Key.DOWN) fineStep + 1 else fineStep - 1
        if (next < 0 || next > s.steps) return
        setStep(next, s)
        timer = host.schedule(s.repeatMs) { repeat(key) }
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    private fun setStep(step: Int, s: Settings) {
        fineStep = step
        host.setAttenuation(if (step == 0) null else s.attenuationDb(step))
        host.showFine(step, s)
    }

    private fun muteFromFine() {
        host.adjustStream(Key.DOWN)
        exitFine()
        held = Key.DOWN // swallow the rest of this press
    }

    private fun exitFine() {
        stopTimer()
        zoomed = false
        fineStep = 0
        host.setAttenuation(null)
        host.hideFine()
    }

    companion object {
        /** Presses closer than this to the previous release are treated as key bounce. */
        const val BOUNCE_MS = 40L
    }
}
