package com.teraplay.app

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

/** Hosts the four tabs (Home, Files, Playlist, Settings) behind a floating glass tab bar. */
class MainActivity : AppCompatActivity() {

    private val tags = arrayOf("home", "files", "playlist", "settings")
    private lateinit var nav: GlassNavBar
    private val notifPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    private var current = 0
    private lateinit var navHolder: View
    private var navShown = true
    private val insetListener: () -> Unit = { navHolder.setPadding(0, 0, 0, Insets.bottom) }

    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            select(0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        S.res = resources
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Glass.setup(this, findViewById(R.id.content), pad = false)

        // registered first = lowest priority: the open Files folder / playlist go back before this one switches to Home
        onBackPressedDispatcher.addCallback(this, backCb)
        val fm = supportFragmentManager
        current = savedInstanceState?.getInt("tab") ?: 0
        if (savedInstanceState == null) {
            val frags = listOf<Fragment>(HomeFragment(), FilesFragment(), PlaylistFragment(), SettingsFragment())
            val t = fm.beginTransaction()
            frags.forEachIndexed { i, f ->
                t.add(R.id.container, f, tags[i])
                if (i != current) t.hide(f)
            }
            t.commitNow()
        }

        nav = findViewById(R.id.nav)
        navHolder = findViewById(R.id.navHolder)
        Insets.add(insetListener)
        navHolder.setPadding(0, 0, 0, Insets.bottom)
        nav.setup(
            intArrayOf(R.drawable.ic_o_home, R.drawable.ic_o_files, R.drawable.ic_o_playlist, R.drawable.ic_o_settings),
            intArrayOf(R.drawable.ic_home, R.drawable.ic_files, R.drawable.ic_playlist, R.drawable.ic_settings),
            arrayOf("Home", "Files", "Playlist", "Settings")
        ) { i -> showTab(i) }
        nav.selectIndex(current, false)

        backCb.isEnabled = current != 0

        // background work: show its progress notification (Android 13+) and continue anything that was cut short
        val prefs = getSharedPreferences("teraplay", MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotif", false) &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean("askedNotif", true).apply()
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        WorkService.resumeIfNeeded(this)
        Downloads.resumeIfNeeded(this)
    }

    /** Called when the user taps a tab (the bar animates itself). */
    private fun showTab(i: Int) {
        if (i == current) return
        val fm = supportFragmentManager
        val t = fm.beginTransaction()
        fm.findFragmentByTag(tags[current])?.let { t.hide(it) }
        fm.findFragmentByTag(tags[i])?.let { t.show(it) }
        t.commitNow()
        current = i
        backCb.isEnabled = current != 0
        setNavVisible(true)
    }

    /** The lists hide the floating tab bar while scrolling down and bring it back when scrolling up. */
    fun setNavVisible(show: Boolean) {
        if (show == navShown) return
        navShown = show
        navHolder.animate()
            .translationY(if (show) 0f else navHolder.height.toFloat())
            .setDuration(220)
            .start()
    }

    override fun onDestroy() {
        Insets.remove(insetListener)
        super.onDestroy()
    }

    private fun select(i: Int) {
        nav.selectIndex(i, true)
        showTab(i)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", current)
    }
}
