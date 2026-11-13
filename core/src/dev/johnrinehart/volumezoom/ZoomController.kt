package dev.johnrinehart.volumezoom

/**
 * Volume-key state machine. Pure Kotlin (no Android types) so it can be unit tested.
 *
 * Two zoom zones extend the media volume range in software:
 * - [Zone.FINE], below the lowest audible index (the floor): attenuation;
 * - [Zone.BOOST], above the highest index (the ceiling): gain.
 *
 * Normal mode: key presses pass through to the system untouched, except a press that starts at the
 * edge of the range in the outward direction (volume-down at the floor, volume-up at the ceiling):
 * - released before [Settings.holdMs]: replayed as an ordinary press;
 * - held for [Settings.holdMs]: zoom in at step 0 (0 dB). The hold only switches modes; it does not
 *   step further.
 *
 * A hold that starts away from the edge never zooms: the filter sees only its down edge, which
 * passes through, and the system's own auto-repeat carries the volume to the edge.
 *
 * Zoomed, step 0..steps, outward key = down in FINE, up in BOOST:
 * - outward: one step further, repeating while held. In FINE a fresh press on the last step mutes
 *   and leaves the zone; in BOOST the last step is the limit. Holding never leaves the zone;
 * - inward: one step back, repeating while held, stopping at step 0. A fresh inward press at step 0
 *   zooms out: the zone ends and the press reaches the system;
 * - any external change of the stream index away from the zone's edge leaves the zone.
 *
 * Accessibility key filters see only the physical down/up edges (no auto-repeat), so holds and
 * repeats are timed here.
 */
class ZoomController(private val host: Host) {

    enum class Key {
        UP,
        DOWN,
    }

    enum class Zone(val outward: Key) {
        FINE(Key.DOWN),
        BOOST(Key.UP),
    }

    data class Settings(
        val steps: Int = 10,
        val maxAttenuationDb: Float = 30f,
        /** Gain at the last boost step; 0 disables boost. */
        val maxBoostDb: Float = 12f,
        val holdMs: Long = 400,
        val repeatMs: Long = 250,
    ) {
        /** Gain in dB at [step] of [zone]: negative in FINE, positive in BOOST. */
        fun gainDb(zone: Zone, step: Int): Float =
            when (zone) {
                Zone.FINE -> -maxAttenuationDb * step / steps
                Zone.BOOST -> maxBoostDb * step / steps
            }
    }

    interface Cancellable {
        fun cancel()
    }

    interface Host {
        /** Current media stream index. */
        fun streamIndex(): Int

        /** Lowest audible media stream index. */
        fun floorIndex(): Int

        /** Highest media stream index. */
        fun ceilingIndex(): Int

        /**
         * Whether volume keys currently target the media stream. [engaged] is true while a zone (or
         * a pending edge hold) is active.
         */
        fun mediaKeysActive(engaged: Boolean): Boolean

        fun settings(): Settings

        /** Ordinary one-step adjustment of the media stream, with system UI. */
        fun adjustStream(key: Key)

        /** Apply software gain in dB; null removes the effect entirely. */
        fun setGain(db: Float?)

        /** Show zone feedback for [step] (0 = zoomed in, no gain yet). */
        fun showZone(zone: Zone, step: Int, settings: Settings)

        /** The zone ended. */
        fun hideZone()

        fun schedule(delayMs: Long, action: () -> Unit): Cancellable

        /** Monotonic clock in milliseconds. */
        fun nowMs(): Long
    }

    /** Active zone, or null in normal mode. */
    var zone: Zone? = null
        private set

    var step: Int = 0
        private set

    /** Key currently held down that we consumed, if any. */
    private var held: Key? = null

    /** Held edge press that has not yet turned into a zoom. */
    private var pending: Zone? = null
    private var timer: Cancellable? = null
    private val lastRelease = mutableMapOf<Key, Long>()

    val isEngaged: Boolean
        get() = zone != null || pending != null

    /**
     * Handle a key-down edge. Returns true if consumed; the caller must then also deliver the
     * matching key-up to [onKeyUp].
     */
    fun onKeyDown(key: Key): Boolean {
        if (held == key) return true // duplicate down edge while held
        if (!host.mediaKeysActive(isEngaged)) return false
        // Contact bounce: a press right after a release of the same key is not a new press.
        val bounced = lastRelease[key]?.let { host.nowMs() - it < BOUNCE_MS } ?: false
        if (bounced && isEngaged) return true

        val s = host.settings()
        val z = zone
        if (z != null) {
            if (key == z.outward) {
                when {
                    step < s.steps -> {
                        setStep(z, step + 1, s)
                        hold(key, s)
                    }
                    z == Zone.FINE -> muteFromFine()
                    else -> held = key // boost limit: swallow the press
                }
            } else if (step > 0) {
                setStep(z, step - 1, s)
                hold(key, s)
            } else {
                exitZone()
                return false // zoom out: the system handles this press
            }
            return true
        }

        val edge = edgeZone(key, s) ?: return false
        held = key
        pending = edge
        timer =
            host.schedule(s.holdMs) {
                timer = null
                pending = null
                zone = edge
                setStep(edge, 0, host.settings())
            }
        return true
    }

    /** Handle a key-up edge for a key whose down edge we consumed. */
    fun onKeyUp(key: Key) {
        lastRelease[key] = host.nowMs()
        if (held != key) return
        held = null
        stopTimer()
        if (pending != null) {
            // Short press at the edge: behave like a normal press.
            pending = null
            host.adjustStream(key)
        }
    }

    /** Media stream index changed (from any source). */
    fun onStreamIndexChanged(index: Int) {
        val z = zone ?: return
        if (index != edgeIndex(z)) exitZone()
    }

    /** Settings changed while zoomed: re-apply the current step, or leave a disabled zone. */
    fun onSettingsChanged() {
        val z = zone ?: return
        val s = host.settings()
        if (z == Zone.BOOST && s.maxBoostDb <= 0f) exitZone()
        else setStep(z, step.coerceAtMost(s.steps), s)
    }

    /** Drop all state and remove the effect (service teardown). */
    fun reset() {
        stopTimer()
        held = null
        pending = null
        zone = null
        step = 0
        host.setGain(null)
    }

    private fun edgeZone(key: Key, s: Settings): Zone? {
        val index = host.streamIndex()
        return when {
            key == Key.DOWN && index == host.floorIndex() -> Zone.FINE
            key == Key.UP && index == host.ceilingIndex() && s.maxBoostDb > 0f -> Zone.BOOST
            else -> null
        }
    }

    private fun edgeIndex(z: Zone) =
        when (z) {
            Zone.FINE -> host.floorIndex()
            Zone.BOOST -> host.ceilingIndex()
        }

    private fun hold(key: Key, s: Settings) {
        held = key
        stopTimer()
        timer = host.schedule(s.holdMs) { repeat(key) }
    }

    /** Auto-repeat while [key] stays held; stops at either end of the zone. */
    private fun repeat(key: Key) {
        timer = null
        val z = zone
        if (held != key || z == null) return
        val s = host.settings()
        val next = if (key == z.outward) step + 1 else step - 1
        if (next < 0 || next > s.steps) return
        setStep(z, next, s)
        timer = host.schedule(s.repeatMs) { repeat(key) }
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    private fun setStep(z: Zone, step: Int, s: Settings) {
        this.step = step
        host.setGain(if (step == 0) null else s.gainDb(z, step))
        host.showZone(z, step, s)
    }

    private fun muteFromFine() {
        host.adjustStream(Key.DOWN)
        exitZone()
        held = Key.DOWN // swallow the rest of this press
    }

    private fun exitZone() {
        stopTimer()
        zone = null
        step = 0
        host.setGain(null)
        host.hideZone()
    }

    companion object {
        /** Presses closer than this to the previous release are treated as key bounce. */
        const val BOUNCE_MS = 40L
    }
}
