package com.teraplay.app

import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings tab: background picture (from the gallery), blur, overlay, glass strength, fit, theme. */
class SettingsFragment : Fragment(R.layout.fragment_settings), Bg.Listener {

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importImage(uri)
    }

    private lateinit var preview: ImageView
    private lateinit var blurLabel: TextView
    private lateinit var dimLabel: TextView
    private lateinit var glassLabel: TextView
    private lateinit var blurSeek: SeekBar
    private lateinit var dimSeek: SeekBar
    private lateinit var glassSeek: SeekBar
    private lateinit var fitViews: Map<String, GlassTextView>
    private lateinit var themeDark: GlassTextView
    private lateinit var themeLight: GlassTextView
    private lateinit var presetRow: LinearLayout
    private lateinit var speedPick: GlassTextView
    private lateinit var bgStatus: TextView
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshControls() }
    private lateinit var langEn: GlassTextView
    private lateinit var langBn: GlassTextView
    private var ready = false
    private lateinit var scroll: View
    private val insetListener: () -> Unit = { applyInsets() }

    private fun applyInsets() {
        if (this::scroll.isInitialized) {
            scroll.setPadding(0, Insets.top, 0, Insets.bottom + Glass.dp(scroll, Glass.NAV_SPACE_DP))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scroll = view.findViewById(R.id.settingsScroll)
        Insets.add(insetListener)
        applyInsets()
        preview = view.findViewById(R.id.bgPreview)
        blurLabel = view.findViewById(R.id.blurLabel)
        dimLabel = view.findViewById(R.id.dimLabel)
        glassLabel = view.findViewById(R.id.glassLabel)
        blurSeek = view.findViewById(R.id.blurSeek)
        dimSeek = view.findViewById(R.id.dimSeek)
        glassSeek = view.findViewById(R.id.glassSeek)
        themeDark = view.findViewById(R.id.themeDark)
        themeLight = view.findViewById(R.id.themeLight)
        presetRow = view.findViewById(R.id.presetRow)
        speedPick = view.findViewById(R.id.speedPick)
        langEn = view.findViewById(R.id.langEn)
        bgStatus = view.findViewById(R.id.bgStatus)
        view.findViewById<View>(R.id.bgNotifBtn).setOnClickListener { askNotifications() }
        view.findViewById<View>(R.id.bgBatteryBtn).setOnClickListener { openBatterySettings() }
        langBn = view.findViewById(R.id.langBn)
        fitViews = mapOf(
            "cover" to view.findViewById<GlassTextView>(R.id.fitCover),
            "contain" to view.findViewById<GlassTextView>(R.id.fitContain),
            "center" to view.findViewById<GlassTextView>(R.id.fitCenter)
        )

        view.findViewById<View>(R.id.pickBtn).setOnClickListener { picker.launch("image/*") }
        view.findViewById<View>(R.id.resetBtn).setOnClickListener {
            Bg.reset()
            refreshControls()
        }

        buildPresets()
        fitViews.forEach { (k, v) ->
            v.setOnClickListener {
                Bg.updateFit(k)
                refreshControls()
            }
        }

        bindSeek(blurSeek, blurLabel, { S.str(R.string.s083, it) }) { Bg.updateBlur(it) }
        bindSeek(dimSeek, dimLabel, { S.str(R.string.s084, it) }) { Bg.updateDim(it) }
        bindSeek(glassSeek, glassLabel, { S.str(R.string.s085, it) }) { Bg.updateGlass(it.coerceAtLeast(5)) }

        themeDark.setOnClickListener { setTheme(true) }
        themeLight.setOnClickListener { setTheme(false) }

        speedPick.setOnClickListener { pickDefaultSpeed() }
        langEn.setOnClickListener { chooseLang("en") }
        langBn.setOnClickListener { chooseLang("bn") }

        Bg.addListener(this)
        ready = true
        refreshControls()
    }

    private fun bindSeek(sb: SeekBar, label: TextView, text: (Int) -> String, onChange: (Int) -> Unit) {
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                label.text = text(p)
                if (fromUser) onChange(p)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun buildPresets() {
        val d = resources.displayMetrics.density
        val size = (42 * d).toInt()
        for (i in 0 until Bg.presetCount) {
            val c = Bg.presetColors(i)
            val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c[0], c[1], c[2]))
            g.shape = GradientDrawable.OVAL
            g.setStroke((1.5f * d).toInt(), 0x66FFFFFF)
            val v = View(requireContext())
            v.background = g
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = (10 * d).toInt()
            v.layoutParams = lp
            v.setOnClickListener {
                Bg.updatePreset(i)
                refreshControls()
            }
            presetRow.addView(v)
        }
    }

    private fun refreshControls() {
        if (!ready) return
        blurSeek.progress = Bg.blur
        dimSeek.progress = Bg.dim
        glassSeek.progress = Bg.glass
        blurLabel.text = S.str(R.string.s083, Bg.blur)
        dimLabel.text = S.str(R.string.s084, Bg.dim)
        glassLabel.text = S.str(R.string.s085, Bg.glass)
        fitViews.forEach { (k, v) -> v.glassSelected = (k == Bg.fit) }
        themeDark.glassSelected = Bg.dark
        themeLight.glassSelected = !Bg.dark
        preview.setImageBitmap(Bg.baseBitmap)
        bgStatus.text = S.str(
            R.string.bg_status,
            if (notificationsAllowed()) "✔" else "✖",
            if (BgSupport.ignoringBatteryOptimizations(requireContext())) "✔" else "✖"
        )
        speedPick.text = S.str(R.string.s086, Fmt.speed(defaultSpeed()))
        val lang = Lang.saved(requireContext())
        langEn.glassSelected = lang == "en"
        langBn.glassSelected = lang == "bn"
    }

    private fun notificationsAllowed(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            requireContext().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun askNotifications() {
        if (notificationsAllowed()) {
            // already allowed: open the system page of this app's notifications
            startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            )
        } else {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Lists the battery-optimisation screen where TeraPlay can be set to "unrestricted". */
    private fun openBatterySettings() {
        try {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:" + requireContext().packageName))
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (ready) refreshControls()
    }

    private fun prefs() = requireContext().getSharedPreferences("teraplay", android.content.Context.MODE_PRIVATE)

    private fun defaultSpeed(): Float = prefs().getFloat("defSpeed", 1f)

    private fun pickDefaultSpeed() {
        val labels = Fmt.SPEEDS.map { Fmt.speed(it) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(S.str(R.string.s087))
            .setItems(labels) { _, which ->
                prefs().edit().putFloat("defSpeed", Fmt.SPEEDS[which]).apply()
                refreshControls()
            }
            .show()
    }

    private fun chooseLang(code: String) {
        if (Lang.saved(requireContext()) == code) return
        Lang.choose(requireContext(), code)
    }

    /** Changes of the background (e.g. the picture finished loading) update the preview. */
    override fun onBgChanged() {
        if (ready) preview.setImageBitmap(Bg.baseBitmap)
    }

    private fun setTheme(dark: Boolean) {
        if (dark == Bg.dark) return
        Bg.updateDark(dark)
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun importImage(uri: Uri) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { Bg.importImage(ctx, uri) }
            if (!ok) {
                Toast.makeText(ctx, S.str(R.string.s088), Toast.LENGTH_SHORT).show()
            } else if (ready) {
                refreshControls()
            }
        }
    }

    override fun onDestroyView() {
        ready = false
        Insets.remove(insetListener)
        Bg.removeListener(this)
        super.onDestroyView()
    }
}
