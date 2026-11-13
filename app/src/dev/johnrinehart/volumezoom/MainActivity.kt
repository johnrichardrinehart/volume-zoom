package dev.johnrinehart.volumezoom

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs.get(this)
        val pad = (16 * resources.displayMetrics.density).roundToInt()

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad * 2, pad, pad)
            }
        fun text(s: String, size: Float = 16f) =
            TextView(this)
                .apply {
                    text = s
                    textSize = size
                    setPadding(0, pad / 2, 0, pad / 2)
                }
                .also(root::addView)

        text(getString(R.string.app_name), 24f)
        status = text("", 16f)
        Button(this)
            .apply {
                text = getString(R.string.open_accessibility)
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            .also(root::addView)
        Button(this)
            .apply {
                text = getString(R.string.open_app_info)
                setOnClickListener {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", packageName, null))
                    )
                }
            }
            .also(root::addView)
        text(getString(R.string.restricted_hint), 13f)
        text(getString(R.string.usage), 15f)

        val maxLabel = text("")
        fun showMax(v: Int) {
            maxLabel.text = getString(R.string.max_attenuation, v, v.toFloat() / Prefs.STEPS)
        }
        root.addView(
            slider(Prefs.MAX_DB_RANGE, prefs.getInt(Prefs.MAX_DB, Prefs.DEFAULT_MAX_DB)) {
                showMax(it)
                prefs.edit().putInt(Prefs.MAX_DB, it).apply()
            }
        )
        showMax(prefs.getInt(Prefs.MAX_DB, Prefs.DEFAULT_MAX_DB))

        val winLabel = text("")
        fun showWin(v: Int) {
            winLabel.text = getString(R.string.hold_time, v)
        }
        root.addView(
            slider(Prefs.HOLD_RANGE, prefs.getInt(Prefs.HOLD_MS, Prefs.DEFAULT_HOLD_MS)) {
                showWin(it)
                prefs.edit().putInt(Prefs.HOLD_MS, it).apply()
            }
        )
        showWin(prefs.getInt(Prefs.HOLD_MS, Prefs.DEFAULT_HOLD_MS))

        Switch(this)
            .apply {
                text = getString(R.string.show_hud)
                isChecked = Prefs.hud(prefs)
                setPadding(0, pad, 0, pad)
                setOnCheckedChangeListener { _, on ->
                    prefs.edit().putBoolean(Prefs.HUD, on).apply()
                }
            }
            .also(root::addView)

        Switch(this)
            .apply {
                text = getString(R.string.idle_keys_media)
                isChecked = Prefs.idleKeysMedia(prefs)
                setPadding(0, pad, 0, pad)
                setOnCheckedChangeListener { _, on ->
                    prefs.edit().putBoolean(Prefs.IDLE_KEYS_MEDIA, on).apply()
                }
            }
            .also(root::addView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        status.text = getString(if (serviceEnabled()) R.string.status_on else R.string.status_off)
    }

    private fun slider(range: IntRange, value: Int, onChange: (Int) -> Unit): View =
        SeekBar(this).apply {
            min = range.first
            max = range.last
            progress = value.coerceIn(range)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser) onChange(p)
                    }

                    override fun onStartTrackingTouch(sb: SeekBar) = Unit

                    override fun onStopTrackingTouch(sb: SeekBar) = Unit
                }
            )
        }

    private fun serviceEnabled(): Boolean {
        val me = ComponentName(this, ZoomService::class.java)
        val enabled =
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}
