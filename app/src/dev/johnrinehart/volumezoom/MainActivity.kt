package dev.johnrinehart.volumezoom

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var restricted: TextView
    private lateinit var appInfo: Button

    private val pad by lazy { (16 * resources.displayMetrics.density).roundToInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs.get(this)

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }

        root.addView(text(getString(R.string.app_name), 28f, bold = true))
        status = text("", 17f).also(root::addView)
        root.addView(
            button(R.string.open_accessibility) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )
        restricted =
            text(getString(R.string.restricted_hint), 14f, secondary = true).also(root::addView)
        appInfo =
            button(R.string.open_app_info) {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", packageName, null))
                    )
                }
                .also(root::addView)

        root.addView(section(R.string.usage_title))
        root.addView(text(getString(R.string.usage), 15f, secondary = true))

        root.addView(section(R.string.settings_title))
        val maxLabel = text("", 15f).also(root::addView)
        fun showMax(v: Int) {
            maxLabel.text = getString(R.string.max_attenuation, v, v.toFloat() / Prefs.STEPS)
        }
        val maxDb = prefs.getInt(Prefs.MAX_DB, Prefs.DEFAULT_MAX_DB)
        root.addView(
            slider(Prefs.MAX_DB_RANGE, maxDb) {
                showMax(it)
                prefs.edit().putInt(Prefs.MAX_DB, it).apply()
            }
        )
        showMax(maxDb)

        val holdLabel = text("", 15f).also(root::addView)
        fun showHold(v: Int) {
            holdLabel.text = getString(R.string.hold_time, v)
        }
        val holdMs = prefs.getInt(Prefs.HOLD_MS, Prefs.DEFAULT_HOLD_MS)
        root.addView(
            slider(Prefs.HOLD_RANGE, holdMs) {
                showHold(it)
                prefs.edit().putInt(Prefs.HOLD_MS, it).apply()
            }
        )
        showHold(holdMs)

        root.addView(
            switch(R.string.show_hud, Prefs.hud(prefs)) {
                prefs.edit().putBoolean(Prefs.HUD, it).apply()
            }
        )
        root.addView(
            switch(R.string.idle_keys_media, Prefs.idleKeysMedia(prefs)) {
                prefs.edit().putBoolean(Prefs.IDLE_KEYS_MEDIA, it).apply()
            }
        )

        val scroll =
            ScrollView(this).apply {
                clipToPadding = false
                addView(root)
            }
        // Android 15+ draws apps edge to edge; keep content clear of the status and
        // navigation bars. Older releases inset the window themselves.
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener { v, insets ->
                val bars =
                    insets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                    )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        val enabled = serviceEnabled()
        status.text = getString(if (enabled) R.string.status_on else R.string.status_off)
        // Apps cannot read whether Android restricted them (the app-op needs a system
        // permission, and the install source changes on updates), so explain the
        // restricted-settings step whenever the service is off.
        val hint = if (enabled) View.GONE else View.VISIBLE
        restricted.visibility = hint
        appInfo.visibility = hint
    }

    private fun text(
        s: String,
        size: Float,
        bold: Boolean = false,
        secondary: Boolean = false,
    ) =
        TextView(this).apply {
            text = s
            textSize = size
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (!secondary) setTextColor(textColorPrimary())
            setPadding(0, pad / 2, 0, pad / 2)
        }

    private fun section(title: Int) =
        text(getString(title), 20f, bold = true).apply { setPadding(0, pad * 3 / 2, 0, pad / 2) }

    private fun button(label: Int, onClick: () -> Unit) =
        Button(this).apply {
            text = getString(label)
            setOnClickListener { onClick() }
        }

    private fun switch(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) =
        Switch(this).apply {
            text = getString(label)
            textSize = 15f
            isChecked = checked
            setPadding(0, pad, 0, pad)
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }

    private fun slider(range: IntRange, value: Int, onChange: (Int) -> Unit): View =
        SeekBar(this).apply {
            min = range.first
            max = range.last
            progress = value.coerceIn(range)
            setPadding(pad / 2, pad / 2, pad / 2, pad)
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

    private fun textColorPrimary(): Int {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        try {
            return a.getColor(0, 0)
        } finally {
            a.recycle()
        }
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
