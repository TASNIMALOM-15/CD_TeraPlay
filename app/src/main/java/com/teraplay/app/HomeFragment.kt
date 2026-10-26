package com.teraplay.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Home tab: sections (continue watching, recent, playlists, folders) + all videos grid. */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var store: LibraryStore
    private lateinit var accStore: AccountStore
    private lateinit var prefs: SharedPreferences
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: HomeAdapter
    private lateinit var grid: GridLayoutManager
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var importBar: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var searchInput: EditText
    private lateinit var colsBtn: ImageView
    private lateinit var searchBox: View
    private lateinit var topBar: View
    private lateinit var homeTitle: TextView
    private lateinit var accFilter: LinearLayout
    private lateinit var overlay: Glass.Overlay
    private var accounts: List<Account> = emptyList()
    private var selectedAcc = ""   // "" = All
    private val insetListener: () -> Unit = { if (viewReady) overlay.update() }

    private val logLines = ArrayDeque<String>()
    private var entries: List<LibEntry> = emptyList()
    private var cols = 2
    private var sortMode = 0
    private var listTitle: String? = null
    private var listProvider: (() -> List<LibEntry>)? = null
    private var viewReady = false

    private val sortLabels: Array<String> get() = arrayOf(S.str(R.string.s046), S.str(R.string.s047), S.str(R.string.s048), S.str(R.string.s049))

    private val backCb = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                searchBox.visibility == View.VISIBLE -> closeSearch()
                listProvider != null -> {
                    listProvider = null
                    listTitle = null
                    render()
                }
                else -> requireActivity().finish()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        store = LibraryStore(ctx)
        accStore = AccountStore(ctx)
        prefs = ctx.getSharedPreferences("teraplay", Context.MODE_PRIVATE)
        accStore.migrate(prefs)
        cols = prefs.getInt("cols", 2).coerceIn(1, 3)
        sortMode = prefs.getInt("sort", 0).coerceIn(0, 3)

        recycler = view.findViewById(R.id.recycler)
        statusText = view.findViewById(R.id.statusText)
        progressText = view.findViewById(R.id.progressText)
        importBar = view.findViewById(R.id.importBar)
        emptyText = view.findViewById(R.id.emptyText)
        searchInput = view.findViewById(R.id.searchInput)
        colsBtn = view.findViewById(R.id.colsBtn)
        searchBox = view.findViewById(R.id.searchBox)
        topBar = view.findViewById(R.id.topBar)
        homeTitle = view.findViewById(R.id.homeTitle)
        accFilter = view.findViewById(R.id.accFilter)
        selectedAcc = prefs.getString("homeAcc", "") ?: ""
        overlay = Glass.Overlay(topBar, recycler, Glass.dp(view, 8))

        adapter = HomeAdapter(
            onVideo = { openPlayer(it) },
            onMenu = { anchor, e ->
                Actions.showVideoMenu(requireContext(), anchor, e, store, null) { render() }
            },
            onGroup = { openGroup(it) },
            onGroupLong = { g ->
                if (g.kind == "playlist") Actions.confirmDeletePlaylist(requireContext(), store, g.name) { render() }
            }
        )
        grid = GridLayoutManager(ctx, cols)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.isFullSpan(position)) grid.spanCount else 1
        }
        recycler.layoutManager = grid
        recycler.adapter = adapter
        // the title only shows at the very top; scrolling down also hides the floating tab bar
        Glass.hideNavOnScroll(recycler) { atTop -> homeTitle.visibility = if (atTop) View.VISIBLE else View.GONE }
        Insets.add(insetListener)
        overlay.update()
        buildAccFilter()

        view.findViewById<View>(R.id.accountsBtn).setOnClickListener {
            startActivity(Intent(requireContext(), AccountsActivity::class.java))
        }
        view.findViewById<View>(R.id.searchBtn).setOnClickListener { toggleSearch() }
        view.findViewById<View>(R.id.addBtn).setOnClickListener { showAddDialog() }
        view.findViewById<View>(R.id.sortBtn).setOnClickListener { showSortMenu(it) }
        colsBtn.setOnClickListener {
            cols = if (cols >= 3) 1 else cols + 1
            prefs.edit().putInt("cols", cols).apply()
            render()
        }

        searchInput.doAfterTextChanged { text ->
            val q = text?.toString()?.trim().orEmpty()
            if (q.isEmpty()) {
                listProvider = null
                listTitle = null
            } else {
                listTitle = S.str(R.string.s050, q)
                listProvider = { entries.filter { it.name.contains(q, ignoreCase = true) } }
            }
            render()
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCb)
        backCb.isEnabled = !isHidden
        viewReady = true
        render()
        ThumbCache.prefetchAll(ctx, entries)
        DurationFetcher.onUpdate = { activity?.runOnUiThread { if (viewReady) render() } }
        DurationFetcher.start(ctx)
        ImportState.onChange = { activity?.runOnUiThread { renderImportUi() } }
        renderImportUi()
        AccountScanner.onChange = {
            activity?.runOnUiThread {
                if (viewReady) {
                    refreshScanStatus()
                    render()
                }
            }
        }
        refreshScanStatus()
    }

    // ------------------------------------------------------------ account filter line (text only)

    private fun accIdOf(e: LibEntry): String =
        if (e.accountId.isNotEmpty() && accounts.any { it.id == e.accountId }) e.accountId
        else (accounts.firstOrNull()?.id ?: "")

    private fun buildAccFilter() {
        accounts = accStore.load()
        if (selectedAcc.isNotEmpty() && accounts.none { it.id == selectedAcc }) selectedAcc = ""
        accFilter.removeAllViews()
        val d = resources.displayMetrics.density
        val items = listOf("" to S.str(R.string.flt_all)) + accounts.map { it.id to it.name }
        for ((id, name) in items) {
            val tv = TextView(requireContext())
            tv.text = "▶ $name"
            tv.textSize = 13f
            tv.setPadding((8 * d).toInt(), (3 * d).toInt(), (8 * d).toInt(), (3 * d).toInt())
            val sel = id == selectedAcc
            tv.alpha = if (sel) 1f else 0.55f
            tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
            tv.setOnClickListener {
                selectedAcc = id
                prefs.edit().putString("homeAcc", id).apply()
                buildAccFilter()
                render()
            }
            accFilter.addView(tv)
        }
    }

    /** Shows "Scanning Account 1… 120 videos found" while an account is being scanned. */
    private fun refreshScanStatus() {
        if (!viewReady || ImportState.running) return
        val run = AccountScanner.running
        if (run.isEmpty()) {
            if (progressText.visibility == View.VISIBLE) {
                progressText.visibility = View.GONE
                overlay.reset()
            }
            val err = AccountScanner.errors.entries.firstOrNull()
            if (err != null) {
                val name = accStore.load().firstOrNull { it.id == err.key }?.name ?: ""
                showStatus(S.str(R.string.scan_err, name, err.value))
                AccountScanner.errors.clear()
            }
        } else {
            val id = run.keys.first()
            val name = accStore.load().firstOrNull { it.id == id }?.name ?: ""
            progressText.text = S.str(R.string.scan_status, name, run[id] ?: 0)
            progressText.visibility = View.VISIBLE
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (viewReady) {
            backCb.isEnabled = !hidden
            if (!hidden) {
                buildAccFilter()
                render()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (viewReady) {
            buildAccFilter()
            render()
            renderImportUi()
            ThumbCache.prefetchAll(requireContext(), entries)
            DurationFetcher.start(requireContext())
        }
    }

    // ------------------------------------------------------------ rendering

    private fun sorted(l: List<LibEntry>): List<LibEntry> = when (sortMode) {
        1 -> l.sortedBy { Fmt.nat(it.name) }
        2 -> l.sortedByDescending { it.size }
        3 -> l.sortedByDescending { it.duration }
        else -> l.sortedWith(compareByDescending<LibEntry> { it.addedAt }.thenBy { Fmt.nat(it.name) })
    }

    private fun isFinished(e: LibEntry): Boolean =
        e.duration > 0 && e.lastPos >= (e.duration - 10) * 1000L

    private fun render() {
        accounts = accStore.load()
        val all = store.loadVisible()
        entries = if (selectedAcc.isEmpty()) all else all.filter { accIdOf(it) == selectedAcc }
        colsBtn.setImageResource(when (cols) { 1 -> R.drawable.ic_cols1; 3 -> R.drawable.ic_cols3; else -> R.drawable.ic_cols2 })
        grid.spanCount = cols

        val rows = mutableListOf<Row>()
        val provider = listProvider
        if (provider != null) {
            val items = sorted(provider())
            rows.add(Row.Header("${listTitle ?: ""} (${items.size})"))
            items.forEach { rows.add(Row.Video(it, true)) }
        } else {
            val cont = entries.filter { it.lastPos > 5000 && !isFinished(it) }
                .sortedByDescending { it.lastWatched }.take(8)
            if (cont.isNotEmpty()) {
                rows.add(Row.Header(S.str(R.string.s051)))
                cont.forEach { rows.add(Row.Video(it, true)) }
            }

            val recent = entries.filter { it.lastWatched > 0 }
                .sortedByDescending { it.lastWatched }.take(8)
            if (recent.isNotEmpty()) {
                rows.add(Row.Header(S.str(R.string.s052)))
                recent.forEach { rows.add(Row.Video(it, true)) }
            }

            val pls = store.loadPlaylists()
            val favCount = entries.count { it.favorite }
            if (pls.isNotEmpty() || favCount > 0) {
                rows.add(Row.Header(S.str(R.string.s053)))
                if (favCount > 0) rows.add(Row.Group(S.str(R.string.s054), favCount, "fav", ""))
                pls.forEach { (name, keys) ->
                    val c = entries.count { it.key in keys }
                    rows.add(Row.Group(name, c, "playlist", name))
                }
            }

            val folders = entries.groupBy { it.folder }
            if (folders.isNotEmpty()) {
                rows.add(Row.Header(S.str(R.string.s055)))
                folders.toSortedMap().forEach { (f, l) -> rows.add(Row.Group(f, l.size, "folder", f)) }
            }

            if (entries.isNotEmpty()) {
                rows.add(Row.Header(S.str(R.string.s056, entries.size, sortLabels[sortMode])))
                sorted(entries).forEach { rows.add(Row.Video(it, true)) }
            }
        }

        adapter.submit(rows)
        emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------ top bar

    private fun toggleSearch() {
        if (searchBox.visibility == View.VISIBLE) {
            closeSearch()
        } else {
            searchBox.visibility = View.VISIBLE
            overlay.reset()
            searchInput.requestFocus()
        }
    }

    private fun closeSearch() {
        searchInput.setText("")
        searchBox.visibility = View.GONE
        overlay.reset()
        listProvider = null
        listTitle = null
        render()
    }

    private fun showSortMenu(anchor: View) {
        val m = PopupMenu(requireContext(), anchor)
        sortLabels.forEachIndexed { i, l -> m.menu.add(0, i, i, (if (i == sortMode) "✓ " else "") + l) }
        m.setOnMenuItemClickListener {
            sortMode = it.itemId
            prefs.edit().putInt("sort", sortMode).apply()
            render()
            true
        }
        m.show()
    }

    // ------------------------------------------------------------ groups & player

    private fun openGroup(g: Row.Group) {
        listTitle = g.title
        listProvider = when (g.kind) {
            "fav" -> { { entries.filter { it.favorite } } }
            "playlist" -> {
                {
                    val keys = store.loadPlaylists()[g.name] ?: mutableListOf()
                    keys.mapNotNull { k -> entries.firstOrNull { it.key == k } }
                }
            }
            else -> { { entries.filter { it.folder == g.name } } }
        }
        render()
        recycler.scrollToPosition(0)
    }

    private fun openPlayer(e: LibEntry) {
        val provider = listProvider
        val queue: List<LibEntry> = if (provider != null) {
            sorted(provider())
        } else {
            entries.filter { it.folder == e.folder }.sortedBy { Fmt.nat(it.name) }
        }
        Actions.openPlayer(requireContext(), queue, e)
    }

    // ------------------------------------------------------------ add link

    private fun showAddDialog() {
        val ctx = requireContext()
        if (accStore.load().isEmpty()) {
            Toast.makeText(ctx, S.str(R.string.s057), Toast.LENGTH_LONG).show()
            startActivity(Intent(ctx, AccountsActivity::class.java))
            return
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, pad / 2, pad, 0)

        val link = EditText(ctx)
        link.hint = S.str(R.string.s058)
        link.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            if (clip.contains("/s/1") || clip.contains("surl=")) link.setText(clip)
        } catch (ex: Exception) {
            // ignore clipboard problems
        }
        box.addView(link)

        AlertDialog.Builder(ctx)
            .setTitle(S.str(R.string.s059))
            .setMessage(S.str(R.string.s060))
            .setView(box)
            .setPositiveButton(S.str(R.string.s013)) { _, _ ->
                val l = link.text.toString().trim()
                if (l.isNotEmpty()) addLink(l)
            }
            .setNegativeButton(S.str(R.string.s014), null)
            .show()
    }

    private fun showStatus(text: String) {
        logLines.clear()
        logLines.addLast(text)
        statusText.text = text
        statusText.visibility = View.VISIBLE
        overlay.reset()
    }

    /** The copy runs in the background service (keeps going when the app is closed); links queue up. */
    private fun addLink(link: String) {
        WorkService.enqueueImport(requireContext(), link)
        Toast.makeText(requireContext(), S.str(R.string.bg_queued), Toast.LENGTH_SHORT).show()
        renderImportUi()
    }

    private var importShown = false

    /** Shows the progress of the background import (also when the screen is opened again later). */
    private fun renderImportUi() {
        if (!viewReady) return
        if (ImportState.running) {
            importShown = true
            importBar.visibility = View.VISIBLE
            if (ImportState.total > 0) {
                importBar.isIndeterminate = false
                importBar.max = ImportState.total
                importBar.progress = ImportState.done
                progressText.text = S.str(R.string.s063, ImportState.done, ImportState.total, ImportState.text)
            } else {
                importBar.isIndeterminate = true
                progressText.text = S.str(R.string.s062)
            }
            progressText.visibility = View.VISIBLE
            statusText.text = ImportState.lines.joinToString("\n")
            statusText.visibility = if (ImportState.lines.isEmpty()) View.GONE else View.VISIBLE
        } else {
            if (importShown) {
                importShown = false
                importBar.visibility = View.GONE
                progressText.visibility = View.GONE
                overlay.reset()
                render()
            }
            val summary = ImportState.lastSummary
            if (summary != null) {
                ImportState.lastSummary = null
                showStatus(summary)
            }
        }
    }

    override fun onDestroyView() {
        viewReady = false
        DurationFetcher.onUpdate = null
        AccountScanner.onChange = null
        ImportState.onChange = null
        Insets.remove(insetListener)
        super.onDestroyView()
    }
}
