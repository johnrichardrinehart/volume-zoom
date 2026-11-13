package dev.johnrinehart.volumezoom

import dev.johnrinehart.volumezoom.ZoomController.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ZoomControllerTest {

    private class FakeHost : ZoomController.Host {
        var index = 5
        var floor = 1
        var media = true
        var mode = true // MODE_NORMAL
        var settings = ZoomController.Settings()
        var gain: Float? = null
        val shown = mutableListOf<Int>()
        var now = 0L
        private val timers = mutableListOf<Pair<Long, () -> Unit>>()
        lateinit var controller: ZoomController

        override fun streamIndex() = index
        override fun floorIndex() = floor
        override fun mediaKeysActive(engaged: Boolean) = mode && (engaged || media)
        override fun settings() = settings
        override fun adjustStream(key: Key) {
            index = (index + if (key == Key.UP) 1 else -1).coerceIn(0, 25)
            controller.onStreamIndexChanged(index)
        }
        override fun setAttenuation(db: Float?) {
            gain = db
        }
        override fun showFine(step: Int, settings: ZoomController.Settings) {
            shown += step
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

        val pendingTimers get() = timers.size
    }

    private lateinit var host: FakeHost
    private lateinit var c: ZoomController

    @Before
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
        assertTrue(press(Key.DOWN, holdMs = 450)) // 400 ms hold -> step 1, no repeat yet
        assertEquals(1, c.fineStep)
    }

    @Test
    fun keysAboveFloorPassThrough() {
        assertFalse(press(Key.DOWN))
        assertFalse(press(Key.UP))
        assertEquals(5, host.index)
        assertNull(host.gain)
    }

    @Test
    fun shortPressAtFloorMutesOnRelease() {
        host.index = 1
        assertTrue(c.onKeyDown(Key.DOWN))
        host.advance(100)
        assertEquals(1, host.index) // nothing yet while held
        c.onKeyUp(Key.DOWN)
        assertEquals(0, host.index)
        assertEquals(0, c.fineStep)
        assertNull(host.gain)
        assertEquals(0, host.pendingTimers)
    }

    @Test
    fun walkingDownToFloorThenHoldZooms() {
        host.index = 2
        assertFalse(press(Key.DOWN)) // 2 -> 1 by the system
        assertEquals(1, host.index)
        assertTrue(c.onKeyDown(Key.DOWN))
        host.advance(399)
        assertEquals(0, c.fineStep)
        host.advance(1)
        assertEquals(1, c.fineStep)
        assertEquals(-3f, host.gain!!, 1e-6f)
        assertEquals(1, host.index)
        c.onKeyUp(Key.DOWN)
        assertEquals(1, host.index) // release after zoom does not mute
        assertEquals(1, c.fineStep)
    }

    @Test
    fun holdingKeepsSteppingAndNeverMutes() {
        host.index = 1
        c.onKeyDown(Key.DOWN)
        host.advance(400) // step 1
        host.advance(250 * 4L) // steps 2..5
        assertEquals(5, c.fineStep)
        host.advance(250 * 20L)
        assertEquals(10, c.fineStep)
        assertEquals(-30f, host.gain!!, 1e-6f)
        assertEquals(1, host.index)
        c.onKeyUp(Key.DOWN)
        assertEquals(10, c.fineStep)
        assertEquals(1, host.index)
    }

    @Test
    fun freshPressOnLastStepMutes() {
        zoomIn()
        repeat(9) { assertTrue(press(Key.DOWN)) }
        assertEquals(10, c.fineStep)
        assertTrue(press(Key.DOWN))
        assertEquals(0, c.fineStep)
        assertEquals(0, host.index)
        assertNull(host.gain)
    }

    @Test
    fun fineDownPressesStepOnceUnlessHeld() {
        zoomIn()
        assertTrue(press(Key.DOWN, holdMs = 300))
        assertEquals(2, c.fineStep)
        assertTrue(press(Key.DOWN, holdMs = 400 + 250 + 10)) // +1, repeat at 400, +1 at 650
        assertEquals(5, c.fineStep)
    }

    @Test
    fun upStepsBackOutOfFineMode() {
        zoomIn()
        press(Key.DOWN)
        press(Key.DOWN)
        assertEquals(3, c.fineStep)
        assertTrue(press(Key.UP))
        assertTrue(press(Key.UP))
        assertEquals(1, c.fineStep)
        assertTrue(press(Key.UP))
        assertEquals(0, c.fineStep)
        assertNull(host.gain)
        assertEquals(1, host.index)
        assertEquals(0, host.shown.last())
        assertFalse(press(Key.UP)) // ordinary again
        assertEquals(2, host.index)
    }

    @Test
    fun holdingUpStopsAtFloorVolume() {
        zoomIn()
        repeat(4) { press(Key.DOWN) }
        assertEquals(5, c.fineStep)
        assertTrue(press(Key.UP, holdMs = 5000))
        assertEquals(0, c.fineStep)
        assertEquals(1, host.index) // did not continue into system volume
        assertNull(host.gain)
    }

    @Test
    fun externalVolumeChangeLeavesFineMode() {
        zoomIn()
        press(Key.DOWN)
        host.index = 4
        c.onStreamIndexChanged(4)
        assertEquals(0, c.fineStep)
        assertNull(host.gain)
    }

    @Test
    fun pausedPlayerKeepsControlWhileZoomed() {
        zoomIn()
        host.media = false
        assertTrue(press(Key.DOWN))
        assertEquals(2, c.fineStep)
    }

    @Test
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

    @Test
    fun bounceAfterReleaseIsIgnoredInFineMode() {
        zoomIn()
        assertTrue(c.onKeyDown(Key.DOWN))
        host.advance(80)
        c.onKeyUp(Key.DOWN)
        host.advance(5)
        assertTrue(c.onKeyDown(Key.DOWN)) // bounce: consumed, no step
        c.onKeyUp(Key.DOWN)
        assertEquals(2, c.fineStep)
    }

    @Test
    fun settingsChangeReappliesGain() {
        zoomIn()
        press(Key.DOWN)
        host.settings = ZoomController.Settings(maxAttenuationDb = 50f)
        c.onSettingsChanged()
        assertEquals(-10f, host.gain!!, 1e-6f)
    }
}
