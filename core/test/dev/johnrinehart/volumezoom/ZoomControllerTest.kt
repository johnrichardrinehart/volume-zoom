package dev.johnrinehart.volumezoom

import dev.johnrinehart.volumezoom.ZoomController.Key
import dev.johnrinehart.volumezoom.ZoomController.Zone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every public no-argument method except [setUp] is a test; see TestMain.kt. */
class ZoomControllerTest {

    private class FakeHost : ZoomController.Host {
        var index = 5
        var floor = 1
        var ceiling = 25
        var media = true
        var mode = true // MODE_NORMAL
        var settings = ZoomController.Settings()
        var applied: Float? = null
        val shown = mutableListOf<Pair<Zone, Int>>()
        var hidden = 0
        var now = 0L
        private val timers = mutableListOf<Pair<Long, () -> Unit>>()
        lateinit var controller: ZoomController

        override fun streamIndex() = index

        override fun floorIndex() = floor

        override fun ceilingIndex() = ceiling

        override fun mediaKeysActive(engaged: Boolean) = mode && (engaged || media)

        override fun settings() = settings

        override fun adjustStream(key: Key) {
            index = (index + if (key == Key.UP) 1 else -1).coerceIn(0, ceiling)
            controller.onStreamIndexChanged(index)
        }

        override fun setGain(db: Float?) {
            applied = db
        }

        override fun showZone(zone: Zone, step: Int, settings: ZoomController.Settings) {
            shown += zone to step
        }

        override fun hideZone() {
            hidden++
        }

        override fun nowMs() = now

        override fun schedule(delayMs: Long, action: () -> Unit): ZoomController.Cancellable {
            val entry = (now + delayMs) to action
            timers += entry
            return object : ZoomController.Cancellable {
                override fun cancel() {
                    timers.remove(entry)
                }
            }
        }

        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = timers.filter { it.first <= end }.minByOrNull { it.first } ?: break
                timers.remove(next)
                now = next.first
                next.second()
            }
            now = end
        }

        val pendingTimers
            get() = timers.size
    }

    private lateinit var host: FakeHost
    private lateinit var c: ZoomController

    fun setUp() {
        host = FakeHost()
        c = ZoomController(host)
        host.controller = c
    }

    /** Press [key] for [holdMs], with the system handling unconsumed keys. Returns consumed. */
    private fun press(key: Key, holdMs: Long = 80): Boolean {
        val eaten = c.onKeyDown(key)
        if (!eaten) host.adjustStream(key)
        host.advance(holdMs)
        if (eaten) c.onKeyUp(key)
        host.advance(150) // gap before the next press
        return eaten
    }

    private fun zoomIn() {
        host.index = 1
        assertTrue(press(Key.DOWN, holdMs = 2000)) // long hold still only enters
        assertEquals(Zone.FINE, c.zone)
        assertEquals(0, c.step)
        assertNull(host.applied)
    }

    private fun boostIn() {
        host.index = 25
        assertTrue(press(Key.UP, holdMs = 2000))
        assertEquals(Zone.BOOST, c.zone)
        assertEquals(0, c.step)
        assertNull(host.applied)
    }

    // --- fine (below the floor) ---

    fun keysInsideTheRangePassThrough() {
        assertFalse(press(Key.DOWN))
        assertFalse(press(Key.UP))
        assertEquals(5, host.index)
        assertNull(host.applied)
    }

    fun shortPressAtFloorMutesOnRelease() {
        host.index = 1
        assertTrue(c.onKeyDown(Key.DOWN))
        host.advance(100)
        assertEquals(1, host.index) // nothing yet while held
        c.onKeyUp(Key.DOWN)
        assertEquals(0, host.index)
        assertNull(c.zone)
        assertNull(host.applied)
        assertEquals(0, host.pendingTimers)
    }

    fun holdAtFloorEntersAtZeroDb() {
        host.index = 2
        assertFalse(press(Key.DOWN)) // 2 -> 1 by the system
        assertTrue(c.onKeyDown(Key.DOWN))
        host.advance(399)
        assertNull(c.zone)
        host.advance(1)
        assertEquals(Zone.FINE, c.zone)
        assertEquals(listOf(Zone.FINE to 0), host.shown)
        host.advance(5000)
        assertEquals(0, c.step) // the entry hold does not step
        c.onKeyUp(Key.DOWN)
        assertEquals(1, host.index) // release after zoom does not mute
        assertNull(host.applied)
    }

    fun fineDownPressesStepAndHoldRepeats() {
        zoomIn()
        assertTrue(press(Key.DOWN))
        assertEquals(1, c.step)
        assertEquals(-3f, host.applied!!, 1e-6f)
        assertTrue(press(Key.DOWN, holdMs = 400 + 250 + 10)) // +1, repeat at 400, +1 at 650
        assertEquals(4, c.step)
        assertTrue(press(Key.DOWN, holdMs = 10_000)) // holding stops at the last step
        assertEquals(10, c.step)
        assertEquals(-30f, host.applied!!, 1e-6f)
        assertEquals(1, host.index)
    }

    fun freshPressOnLastFineStepMutes() {
        zoomIn()
        repeat(10) { assertTrue(press(Key.DOWN)) }
        assertEquals(10, c.step)
        assertTrue(press(Key.DOWN))
        assertNull(c.zone)
        assertEquals(0, host.index)
        assertNull(host.applied)
    }

    fun fineUpStepsBackThenZoomsOut() {
        zoomIn()
        repeat(3) { press(Key.DOWN) }
        assertEquals(3, c.step)
        repeat(3) { assertTrue(press(Key.UP)) }
        assertEquals(0, c.step)
        assertEquals(Zone.FINE, c.zone)
        assertNull(host.applied)
        // Up at 0 dB zooms out and raises the system volume as usual.
        assertFalse(press(Key.UP))
        assertNull(c.zone)
        assertEquals(2, host.index)
        assertEquals(1, host.hidden)
    }

    fun holdingFineUpStopsAtZeroDb() {
        zoomIn()
        repeat(5) { press(Key.DOWN) }
        assertTrue(press(Key.UP, holdMs = 5000))
        assertEquals(0, c.step)
        assertEquals(Zone.FINE, c.zone)
        assertEquals(1, host.index)
    }

    // --- boost (above the ceiling) ---

    fun shortPressAtCeilingBehavesAsUsual() {
        host.index = 25
        assertTrue(c.onKeyDown(Key.UP))
        host.advance(100)
        c.onKeyUp(Key.UP)
        assertNull(c.zone)
        assertEquals(25, host.index)
        assertNull(host.applied)
    }

    fun holdAtCeilingEntersBoostAtZeroDb() {
        boostIn()
        assertEquals(listOf(Zone.BOOST to 0), host.shown)
        assertEquals(25, host.index)
    }

    fun holdStartedBelowCeilingNeverBoosts() {
        // The filter sees only the down edge at index 24; it passes through and the
        // system's auto-repeat carries the volume to the top without new edges.
        host.index = 24
        assertFalse(c.onKeyDown(Key.UP))
        repeat(3) { host.adjustStream(Key.UP) } // system repeats, clamped at 25
        host.advance(5000)
        assertNull(c.zone)
        assertNull(host.applied)
        assertEquals(25, host.index)
        assertEquals(0, host.pendingTimers)
    }

    fun boostUpPressesAddGainAndStopAtLimit() {
        boostIn()
        assertTrue(press(Key.UP))
        assertEquals(1, c.step)
        assertEquals(1.2f, host.applied!!, 1e-5f)
        assertTrue(press(Key.UP, holdMs = 10_000))
        assertEquals(10, c.step)
        assertEquals(12f, host.applied!!, 1e-5f)
        assertTrue(press(Key.UP)) // fresh press at the limit: swallowed, no change
        assertEquals(10, c.step)
        assertEquals(Zone.BOOST, c.zone)
        assertEquals(25, host.index)
    }

    fun boostDownStepsBackThenZoomsOut() {
        boostIn()
        repeat(2) { press(Key.UP) }
        repeat(2) { assertTrue(press(Key.DOWN)) }
        assertEquals(0, c.step)
        assertNull(host.applied)
        assertFalse(press(Key.DOWN)) // zoom out: the system lowers the volume
        assertNull(c.zone)
        assertEquals(24, host.index)
    }

    fun boostDisabledWhenMaxIsZero() {
        host.settings = ZoomController.Settings(maxBoostDb = 0f)
        host.index = 25
        assertFalse(c.onKeyDown(Key.UP))
        host.advance(2000)
        assertNull(c.zone)
    }

    fun disablingBoostWhileBoostedLeavesTheZone() {
        boostIn()
        press(Key.UP)
        host.settings = ZoomController.Settings(maxBoostDb = 0f)
        c.onSettingsChanged()
        assertNull(c.zone)
        assertNull(host.applied)
    }

    // --- shared ---

    fun externalVolumeChangeLeavesTheZone() {
        zoomIn()
        press(Key.DOWN)
        host.index = 4
        c.onStreamIndexChanged(4)
        assertNull(c.zone)
        assertNull(host.applied)

        boostIn()
        press(Key.UP)
        host.index = 20 // e.g. switched to headphones
        c.onStreamIndexChanged(20)
        assertNull(c.zone)
        assertNull(host.applied)
    }

    fun pausedPlayerKeepsControlWhileZoomed() {
        zoomIn()
        host.media = false
        assertTrue(press(Key.DOWN))
        assertEquals(1, c.step)
    }

    fun nothingInterceptedWhenNotMediaOrInCall() {
        host.index = 1
        host.media = false
        assertFalse(press(Key.DOWN))
        assertEquals(0, host.index)

        host.media = true
        zoomIn()
        host.mode = false // call started
        assertFalse(c.onKeyDown(Key.DOWN))
    }

    fun bounceAfterReleaseIsIgnoredWhileZoomed() {
        zoomIn()
        assertTrue(c.onKeyDown(Key.DOWN))
        host.advance(80)
        c.onKeyUp(Key.DOWN)
        host.advance(5)
        assertTrue(c.onKeyDown(Key.DOWN)) // bounce: consumed, no step
        c.onKeyUp(Key.DOWN)
        assertEquals(1, c.step)
    }

    fun settingsChangeReappliesGain() {
        zoomIn()
        press(Key.DOWN)
        press(Key.DOWN)
        host.settings = ZoomController.Settings(maxAttenuationDb = 50f)
        c.onSettingsChanged()
        assertEquals(-10f, host.applied!!, 1e-6f)
    }
}
