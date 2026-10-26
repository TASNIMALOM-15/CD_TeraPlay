package com.teraplay.app

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import java.io.File
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
    private lateinit var overlay: Glass.Overlay
    private val insetListener: () -> Unit = { if (viewReady) overlay.update() }

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
                if (g.kind == "deleteAll") {
                    confirmDeleteAll()
                } else {
                    openKind = g.kind
                    openName = g.name
                    openTitle = g.title
                    render()
                    recycler.scrollToPosition(0)
                }
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
        overlay = Glass.Overlay(view.findViewById(R.id.plTop), recycler, Glass.dp(view, 8))
        Glass.hideNavOnScroll(recycler)
        Insets.add(insetListener)
        overlay.update()

        title.setOnClickListener { if (openKind != null) closeList() }
        view.findViewById<View>(R.id.newPlaylistBtn).setOnClickListener {
            Actions.askPlaylistName(requireContext()) { n ->
                store.createPlaylist(n)
                render()
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCb)
        viewReady = true
        Downloads.onChange = { activity?.runOnUiThread { if (viewReady) render() } }
        render()
    }

    private fun closeList() {
        Downloads.errors.clear()
        openKind = null
        render()
    }

    /** Videos of the opened list, in playlist order. */
    private fun currentVideos(): List<LibEntry> {
        val all = store.load()
        return if (openKind == "fav") {
            all.filter { it.favorite && !it.disliked }
        } else if (openKind == "downloads") {
            all.filter { Downloads.isDownloaded(it) && !it.disliked }
        } else if (openKind == "dislike") {
            all.filter { it.disliked }
        } else {
            val keys = store.loadPlaylists()[openName] ?: mutableListOf()
            keys.mapNotNull { k -> all.firstOrNull { it.key == k && !it.disliked } }
        }
    }

    // ------------------------------------------------------------ Dislike playlist: delete everything for good

    private fun confirmDeleteAll() {
        val items = currentVideos()
        if (items.isEmpty()) return
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setMessage(S.str(R.string.dis_confirm1, items.size))
            .setPositiveButton(S.str(R.string.dis_continue)) { _, _ ->
                AlertDialog.Builder(ctx)
                    .setMessage(S.str(R.string.dis_confirm2))
                    .setPositiveButton(S.str(R.string.dis_confirm_yes)) { _, _ -> deleteAll(items) }
                    .setNegativeButton(S.str(R.string.s017), null)
                    .show()
            }
            .setNegativeButton(S.str(R.string.s014), null)
            .show()
    }

    private fun deleteAll(items: List<LibEntry>) {
        val app = requireContext().applicationContext
        Toast.makeText(app, S.str(R.string.dis_deleting), Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val failed = withContext(Dispatchers.IO) { purge(app, items) }
            Downloads.remoteChanged = true
            Toast.makeText(app, S.str(R.string.dis_done, items.size - failed, failed), Toast.LENGTH_LONG).show()
            render()
        }
    }

    /** Removes the videos from their Terabox account(s), from the phone and from the library. Returns how many failed. */
    private fun purge(app: Context, items: List<LibEntry>): Int {
        val store = LibraryStore(app)
        val accounts = AccountStore(app).load()
        var failed = 0
        val removed = mutableListOf<LibEntry>()
        val byAcc = items.groupBy { e -> accounts.firstOrNull { it.id == e.accountId }?.id ?: accounts.firstOrNull()?.id ?: "" }
        for ((accId, list) in byAcc) {
            removed.addAll(list.filter { it.remotePath.isEmpty() })   // never copied: nothing on Terabox to delete
            val remote = list.filter { it.remotePath.isNotEmpty() }
            if (remote.isEmpty()) continue
            val acc = accounts.firstOrNull { it.id == accId }
            if (acc == null) {
                failed += remote.size
                continue
            }
            val client = TeraBoxClient(acc.cookie) { }
            for (chunk in remote.chunked(40)) {
                try {
                    client.deletePaths(chunk.map { it.remotePath })
                    removed.addAll(chunk)
                } catch (ex: Exception) {
                    failed += chunk.size
                }
            }
        }
        removed.forEach { if (it.localPath.isNotEmpty()) File(it.localPath).delete() }
        store.removeAll(removed)
        return failed
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
            val vis = all.filter { !it.disliked }
            val favCount = vis.count { it.favorite }
            val pls = store.loadPlaylists()
            rows.add(Row.Group(S.str(R.string.dl_title), vis.count { Downloads.isDownloaded(it) }, "downloads", ""))
            rows.add(Row.Group(S.str(R.string.dis_title), all.count { it.disliked }, "dislike", ""))
            if (favCount > 0) rows.add(Row.Group(S.str(R.string.s054), favCount, "fav", ""))
            pls.forEach { (name, keys) ->
                rows.add(Row.Group(name, vis.count { it.key in keys }, "playlist", name))
            }
            empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        } else {
            val items = currentVideos()
            title.text = "← ${openTitle} (${items.size})"
            if (kind == "downloads") {
                val all = store.load()
                Downloads.progress.forEach { (k, p) ->
                    rows.add(Row.Header(S.str(R.string.dl_row, all.firstOrNull { it.key == k }?.name ?: k, p)))
                }
                Downloads.errors.forEach { (k, m) ->
                    rows.add(Row.Header(S.str(R.string.dl_err_row, all.firstOrNull { it.key == k }?.name ?: k, m)))
                }
            }
            if (kind == "dislike" && items.isNotEmpty()) {
                // the one and only "delete everything" button: it is deliberately not offered in other playlists
                rows.add(Row.Group(S.str(R.string.dis_delete_all), items.size, "deleteAll", ""))
            }
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
        Insets.remove(insetListener)
        Downloads.onChange = null
        super.onDestroyView()
    }
}
