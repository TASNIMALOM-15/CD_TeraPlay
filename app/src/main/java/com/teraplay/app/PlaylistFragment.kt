package com.teraplay.app

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Playlist tab: favourites + user playlists; tap one to see / play its videos. */
class PlaylistFragment : Fragment(R.layout.fragment_playlist) {

    private lateinit var store: LibraryStore
    private lateinit var adapter: HomeAdapter
    private lateinit var grid: GridLayoutManager
    private lateinit var recycler: RecyclerView
    private lateinit var title: TextView
    private lateinit var empty: TextView
    private var openKind: String? = null   // null = list of playlists, else "fav" | "playlist"
    private var openName = ""
    private var openTitle = ""
    private var viewReady = false

    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closeList()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        store = LibraryStore(ctx)
        val cols = ctx.getSharedPreferences("teraplay", Context.MODE_PRIVATE).getInt("cols", 2).coerceIn(1, 3)

        recycler = view.findViewById(R.id.plRecycler)
        title = view.findViewById(R.id.plTitle)
        empty = view.findViewById(R.id.plEmpty)

        adapter = HomeAdapter(
            onVideo = { playFromList(it) },
            onMenu = { anchor, e ->
                val pl = if (openKind == "playlist") openName else null
                Actions.showVideoMenu(requireContext(), anchor, e, store, pl) { render() }
            },
            onGroup = { g ->
                openKind = g.kind
                openName = g.name
                openTitle = g.title
                render()
                recycler.scrollToPosition(0)
            },
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

        title.setOnClickListener { if (openKind != null) closeList() }
        view.findViewById<View>(R.id.newPlaylistBtn).setOnClickListener {
            Actions.askPlaylistName(requireContext()) { n ->
                store.createPlaylist(n)
                render()
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCb)
        viewReady = true
        render()
    }

    private fun closeList() {
        openKind = null
        render()
    }

    /** Videos of the opened list, in playlist order. */
    private fun currentVideos(): List<LibEntry> {
        val all = store.load()
        return if (openKind == "fav") {
            all.filter { it.favorite }
        } else {
            val keys = store.loadPlaylists()[openName] ?: mutableListOf()
            keys.mapNotNull { k -> all.firstOrNull { it.key == k } }
        }
    }

    private fun playFromList(e: LibEntry) {
        Actions.openPlayer(requireContext(), currentVideos(), e)
    }

    private fun render() {
        if (!viewReady) return
        val rows = mutableListOf<Row>()
        val kind = openKind
        if (kind == null) {
            title.text = S.str(R.string.s082)
            val all = store.load()
            val favCount = all.count { it.favorite }
            val pls = store.loadPlaylists()
            if (favCount > 0) rows.add(Row.Group(S.str(R.string.s054), favCount, "fav", ""))
            pls.forEach { (name, keys) ->
                rows.add(Row.Group(name, all.count { it.key in keys }, "playlist", name))
            }
            empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        } else {
            val items = currentVideos()
            title.text = "← ${openTitle} (${items.size})"
            items.forEach { rows.add(Row.Video(it, true)) }
            empty.visibility = View.GONE
        }
        adapter.submit(rows)
        backCb.isEnabled = !isHidden && openKind != null
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (viewReady) {
            if (!hidden) render()
            backCb.isEnabled = !hidden && openKind != null
        }
    }

    override fun onResume() {
        super.onResume()
        if (viewReady && !isHidden) render()
    }

    override fun onDestroyView() {
        viewReady = false
        super.onDestroyView()
    }
}
