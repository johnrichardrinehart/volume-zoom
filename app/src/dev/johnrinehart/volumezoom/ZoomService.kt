package dev.johnrinehart.volumezoom

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Filters the hardware volume keys (screen on) and drives [ZoomController]. */
class ZoomService : AccessibilityService(), ZoomController.Host {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var prefs: SharedPreferences
    private lateinit var hud: Hud
    private lateinit var controller: ZoomController
    private val gain =
        Gain(
            onControlLost = {
                handler.post {
                    Log.w(TAG, "lost control of session-0 effect")
                    controller.zone?.let { hud.show(it, controller.step, settings(), "lost") }
                }
            }
        )

    /** Key codes whose ACTION_DOWN we consumed; their ACTION_UP must be consumed too. */
    private val consumed = mutableSetOf<Int>()
    private var receiverRegistered = false

    private val volumeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val stream = intent.getIntExtra(EXTRA_STREAM_TYPE, AudioManager.STREAM_MUSIC)
                if (stream == AudioManager.STREAM_MUSIC)
                    controller.onStreamIndexChanged(streamIndex())
            }
        }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        controller.onSettingsChanged()
    }

    override fun onServiceConnected() {
        audio = getSystemService(AudioManager::class.java)
        prefs = Prefs.get(this)
        hud = Hud(this)
        controller = ZoomController(this)

        val filter =
            IntentFilter().apply {
                addAction(ACTION_VOLUME_CHANGED)
                addAction(ACTION_STREAM_DEVICES_CHANGED)
            }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(volumeReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(volumeReceiver, filter)
        }
        receiverRegistered = true
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        Log.i(TAG, "connected; floor=${floorIndex()} index=${streamIndex()}")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val key =
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> ZoomController.Key.UP
                KeyEvent.KEYCODE_VOLUME_DOWN -> ZoomController.Key.DOWN
                else -> return false
            }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Filters normally see no auto-repeat; if one shows up, follow the down edge.
                if (event.repeatCount > 0) return event.keyCode in consumed
                val eaten = controller.onKeyDown(key)
                if (eaten) consumed += event.keyCode else consumed -= event.keyCode
                Log.d(
                    TAG,
                    "down key=$key eaten=$eaten index=${streamIndex()} mode=${audio.mode} " +
                        "music=${audio.isMusicActive} zone=${controller.zone} step=${controller.step}",
                )
                eaten
            }
            KeyEvent.ACTION_UP -> {
                val eaten = consumed.remove(event.keyCode)
                if (eaten) controller.onKeyUp(key)
                eaten
            }
            else -> false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (!::controller.isInitialized) return
        controller.reset()
        hud.detach()
        if (receiverRegistered) {
            unregisterReceiver(volumeReceiver)
            receiverRegistered = false
        }
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    // --- ZoomController.Host ---

    override fun streamIndex() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

    override fun floorIndex() = maxOf(audio.getStreamMinVolume(AudioManager.STREAM_MUSIC), 1)

    override fun ceilingIndex() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    override fun mediaKeysActive(engaged: Boolean) =
        audio.mode == AudioManager.MODE_NORMAL &&
            (engaged || audio.isMusicActive || Prefs.idleKeysMedia(prefs))

    override fun settings() = Prefs.settings(prefs)

    override fun adjustStream(key: ZoomController.Key) {
        val direction =
            when (key) {
                ZoomController.Key.UP -> AudioManager.ADJUST_RAISE
                ZoomController.Key.DOWN -> AudioManager.ADJUST_LOWER
            }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    override fun setGain(db: Float?) {
        Log.i(TAG, "gain=${db ?: "off"}")
        if (db == null) {
            gain.release()
        } else if (!gain.set(db)) {
            controller.zone?.let { hud.show(it, controller.step, settings(), "n/a") }
        }
    }

    override fun showZone(
        zone: ZoomController.Zone,
        step: Int,
        settings: ZoomController.Settings,
    ) {
        Log.i(TAG, "zone=$zone step=$step/${settings.steps}")
        if (Prefs.hud(prefs)) hud.show(zone, step, settings)
    }

    override fun hideZone() {
        Log.i(TAG, "zone off")
        hud.detach()
    }

    override fun schedule(delayMs: Long, action: () -> Unit): ZoomController.Cancellable {
        val r = Runnable(action)
        handler.postDelayed(r, delayMs)
        return object : ZoomController.Cancellable {
            override fun cancel() = handler.removeCallbacks(r)
        }
    }

    override fun nowMs() = SystemClock.uptimeMillis()

    private companion object {
        const val TAG = "VolumeZoom"
        const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val ACTION_STREAM_DEVICES_CHANGED = "android.media.STREAM_DEVICES_CHANGED_ACTION"
        const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    }
}
