package com.teraplay.app

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

/** Hosts the four tabs (Home, Files, Playlist, Settings) behind a floating glass tab bar. */
class MainActivity : AppCompatActivity() {

    private val tags = arrayOf("home", "files", "playlist", "settings")
    private lateinit var nav: GlassNavBar
    private var current = 0

    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            select(0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        S.res = resources
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Glass.setup(this, findViewById(R.id.content))

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
        nav.setup(
            intArrayOf(R.drawable.ic_o_home, R.drawable.ic_o_files, R.drawable.ic_o_playlist, R.drawable.ic_o_settings),
            intArrayOf(R.drawable.ic_home, R.drawable.ic_files, R.drawable.ic_playlist, R.drawable.ic_settings),
            arrayOf("Home", "Files", "Playlist", "Settings")
        ) { i -> showTab(i) }
        nav.selectIndex(current, false)

        onBackPressedDispatcher.addCallback(this, backCb)
        backCb.isEnabled = current != 0
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
