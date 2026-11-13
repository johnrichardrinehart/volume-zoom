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
        val pending = mutableListOf<Pair<Long, () -> Unit>>()

        override fun streamIndex() = index
        override fun floorIndex() = floor
        override fun mediaKeysActive(engaged: Boolean) = mode && (engaged || media)
        override fun settings() = settings
        override fun adjustStream(key: Key) {
            index = (index + if (key == Key.UP) 1 else -1).coerceIn(0, 15)
        }
        override fun setAttenuation(db: Float?) {
            gain = db
        }
        override fun showFine(step: Int, settings: ZoomController.Settings) {
            shown += step
        }
        override fun schedule(delayMs: Long, action: () -> Unit): ZoomController.Cancellable {
            val entry = delayMs to action
            pending += entry
            return object : ZoomController.Cancellable {
                override fun cancel() {
                    pending.remove(entry)
                }
            }
        }

        fun fireTimers() {
            val due = pending.toList()
            pending.clear()
            due.forEach { it.second() }
        }
    }

    private lateinit var host: FakeHost
    private lateinit var c: ZoomController

    @Before
    fun setUp() {
        host = FakeHost()
        c = ZoomController(host)
    }

    /** Simulates the system handling a key the controller did not consume. */
    private fun press(key: Key, repeat: Boolean = false): Boolean {
        val eaten = c.onKeyDown(key, repeat)
        if (!eaten) host.adjustStream(key)
        c.onStreamIndexChanged(host.index)
        return eaten
    }

    private fun zoomIn() {
        host.index = 1
        assertTrue(press(Key.DOWN))
        assertTrue(press(Key.DOWN))
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
    fun singleDownAtFloorIsReplayedAfterWindow() {
        host.index = 1
        assertTrue(press(Key.DOWN))
        assertEquals(1, host.index)
        assertEquals(350L, host.pending.single().first)
        host.fireTimers()
        assertEquals(0, host.index)
        assertEquals(0, c.fineStep)
        assertNull(host.gain)
    }

    @Test
    fun walkingDownToFloorThenDoubleTapZooms() {
        host.index = 2
        assertFalse(press(Key.DOWN)) // 2 -> 1 by the system
        assertEquals(1, host.index)
        assertTrue(press(Key.DOWN)) // held
        assertTrue(press(Key.DOWN)) // second tap: zoom
        assertEquals(1, c.fineStep)
        assertEquals(-3f, host.gain!!, 1e-6f)
        assertTrue(host.pending.isEmpty())
        assertEquals(1, host.index)
    }

    @Test
    fun fineStepsGoDownToLimitThenMute() {
        zoomIn()
        repeat(9) { assertTrue(press(Key.DOWN)) }
        assertEquals(10, c.fineStep)
        assertEquals(-30f, host.gain!!, 1e-6f)
        // Held key at the last step does not fall through to mute.
        assertTrue(press(Key.DOWN, repeat = true))
        assertEquals(10, c.fineStep)
        assertEquals(1, host.index)
        // A fresh press mutes and leaves fine mode.
        assertTrue(press(Key.DOWN))
        assertEquals(0, c.fineStep)
        assertEquals(0, host.index)
        assertNull(host.gain)
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
        // Next up is ordinary again.
        assertFalse(press(Key.UP))
        assertEquals(2, host.index)
    }

    @Test
    fun upWhilePendingReplaysDownThenPassesUp() {
        host.index = 1
        assertTrue(press(Key.DOWN))
        assertFalse(press(Key.UP))
        assertTrue(host.pending.isEmpty())
        assertEquals(1, host.index) // 1 -> 0 -> 1
        assertEquals(0, c.fineStep)
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

        host.index = 1
        host.media = true
        zoomIn()
        host.mode = false // call started
        assertFalse(c.onKeyDown(Key.DOWN, false))
    }

    @Test
    fun repeatWhilePendingIsSwallowed() {
        host.index = 1
        assertTrue(press(Key.DOWN))
        assertTrue(press(Key.DOWN, repeat = true))
        assertEquals(0, c.fineStep)
        host.fireTimers()
        assertEquals(0, host.index)
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
