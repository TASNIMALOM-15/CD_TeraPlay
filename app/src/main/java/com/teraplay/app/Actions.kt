package com.teraplay.app

import android.content.Context
import android.content.Intent
import android.view.View
import java.io.File
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu

/** Things several tabs do with a video: open the player, the ⋮ menu, playlist dialogs. */
object Actions {

    fun openPlayer(ctx: Context, queue: List<LibEntry>, e: LibEntry) {
        val keys = ArrayList(queue.map { it.key })
        val i = keys.indexOf(e.key).coerceAtLeast(0)
        ctx.startActivity(
            Intent(ctx, PlayerActivity::class.java)
                .putStringArrayListExtra(PlayerActivity.EXTRA_KEYS, keys)
                .putExtra(PlayerActivity.EXTRA_INDEX, i)
        )
    }

    /** [playlistName] != null adds a "remove from this playlist" item. */
    fun showVideoMenu(
        ctx: Context,
        anchor: View,
        e: LibEntry,
        store: LibraryStore,
        playlistName: String?,
        onChanged: () -> Unit
    ) {
        val m = PopupMenu(ctx, anchor)
        if (e.disliked) m.menu.add(0, 7, 0, S.str(R.string.dis_restore_menu))
        m.menu.add(0, 1, 0, if (e.favorite) S.str(R.string.s018) else S.str(R.string.s019))
        if (e.surl.isNotEmpty()) m.menu.add(0, 2, 1, S.str(R.string.s020))
        val downloaded = Downloads.isDownloaded(e)
        val active = Downloads.isActive(e.key)
        m.menu.add(
            0, 3, 2,
            when {
                downloaded -> S.str(R.string.dl_menu_delete)
                active -> S.str(R.string.dl_menu_active)
                else -> S.str(R.string.s021)
            }
        )
        m.menu.add(0, 4, 3, S.str(R.string.s022))
        if (playlistName != null) m.menu.add(0, 6, 4, S.str(R.string.s023))
        m.menu.add(0, 5, 5, S.str(R.string.s024))
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> {
                    store.update(e.copy(favorite = !e.favorite))
                    onChanged()
                }
                2 -> {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "https://www.terabox.com/s/1${e.surl}")
                    ctx.startActivity(Intent.createChooser(send, S.str(R.string.s025)))
                }
                3 -> {
                    if (Downloads.isDownloaded(e)) {
                        Downloads.delete(store, e)
                        onChanged()
                        Toast.makeText(ctx, S.str(R.string.dl_deleted), Toast.LENGTH_SHORT).show()
                    } else if (!Downloads.isActive(e.key)) {
                        Downloads.enqueue(ctx, e.key)
                        Toast.makeText(ctx, S.str(R.string.dl_started), Toast.LENGTH_SHORT).show()
                    }
                }
                4 -> addToPlaylist(ctx, store, e, onChanged)
                5 -> {
                    if (e.localPath.isNotEmpty()) File(e.localPath).delete()
                    store.remove(e)
                    onChanged()
                    Toast.makeText(ctx, S.str(R.string.s027), Toast.LENGTH_SHORT).show()
                }
                6 -> {
                    store.removeFromPlaylist(playlistName ?: "", e.key)
                    onChanged()
                }
                7 -> {
                    store.update(e.copy(disliked = false))
                    onChanged()
                    Toast.makeText(ctx, S.str(R.string.dis_restored), Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        m.show()
    }

    fun addToPlaylist(ctx: Context, store: LibraryStore, e: LibEntry, onChanged: () -> Unit) {
        val names = store.loadPlaylists().keys.toList()
        val items = (names + S.str(R.string.s028)).toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle(S.str(R.string.s029))
            .setItems(items) { _, which ->
                if (which < names.size) {
                    store.addToPlaylist(names[which], e.key)
                    onChanged()
                } else {
                    askPlaylistName(ctx) { n ->
                        store.addToPlaylist(n, e.key)
                        onChanged()
                    }
                }
            }
            .show()
    }

    fun askPlaylistName(ctx: Context, onName: (String) -> Unit) {
        val input = EditText(ctx)
        input.hint = S.str(R.string.s030)
        input.setSingleLine()
        AlertDialog.Builder(ctx)
            .setTitle(S.str(R.string.s031))
            .setView(input)
            .setPositiveButton(S.str(R.string.s032)) { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) onName(n)
            }
            .setNegativeButton(S.str(R.string.s014), null)
            .show()
    }

    fun confirmDeletePlaylist(ctx: Context, store: LibraryStore, name: String, onChanged: () -> Unit) {
        AlertDialog.Builder(ctx)
            .setMessage(S.str(R.string.s033, name))
            .setPositiveButton(S.str(R.string.s034)) { _, _ ->
                store.deletePlaylist(name)
                onChanged()
            }
            .setNegativeButton(S.str(R.string.s017), null)
            .show()
    }
}
