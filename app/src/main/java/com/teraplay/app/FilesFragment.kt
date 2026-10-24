package com.teraplay.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Files tab: browse everything stored in each saved Terabox account. */
class FilesFragment : Fragment(R.layout.fragment_files) {

    private lateinit var store: LibraryStore
    private lateinit var accStore: AccountStore
    private lateinit var chips: LinearLayout
    private lateinit var pathText: TextView
    private lateinit var loadingBar: View
    private lateinit var messageText: TextView
    private lateinit var adapter: FilesAdapter

    private var accounts: List<Account> = emptyList()
    private var selected = 0
    private val paths = HashMap<String, String>()
    private var items: List<ShareFile> = emptyList()
    private var loadedKey = ""
    private var loadSeq = 0
    private var viewReady = false

    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            goUp()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        store = LibraryStore(ctx)
        accStore = AccountStore(ctx)
        chips = view.findViewById(R.id.chips)
        pathText = view.findViewById(R.id.pathText)
        loadingBar = view.findViewById(R.id.loadingBar)
        messageText = view.findViewById(R.id.messageText)

        adapter = FilesAdapter({ onItemClick(it) }, { anchor, f -> onItemLong(anchor, f) })
        val list = view.findViewById<RecyclerView>(R.id.fileList)
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = adapter

        view.findViewById<View>(R.id.upBtn).setOnClickListener { goUp() }
        view.findViewById<View>(R.id.refreshBtn).setOnClickListener { loadDir() }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCb)
        viewReady = true
        if (!isHidden) syncAccounts()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (viewReady) {
            if (!hidden) syncAccounts()
            updateBack()
        }
    }

    override fun onResume() {
        super.onResume()
        if (viewReady && !isHidden) syncAccounts()
    }

    override fun onDestroyView() {
        viewReady = false
        super.onDestroyView()
    }

    // ------------------------------------------------------------ accounts & navigation

    private fun curAcc(): Account? = accounts.getOrNull(selected)

    private fun curPath(): String = curAcc()?.let { paths[it.id] } ?: "/"

    private fun syncAccounts() {
        val list = accStore.load()
        val changed = list.map { it.id } != accounts.map { it.id }
        accounts = list
        if (selected >= accounts.size) selected = 0
        buildChips()
        if (changed || loadedKey.isEmpty()) loadDir()
    }

    private fun buildChips() {
        chips.removeAllViews()
        val pad = (10 * resources.displayMetrics.density).toInt()
        accounts.forEachIndexed { i, a ->
            val tv = GlassTextView(requireContext())
            tv.text = a.name
            tv.setPadding(pad * 2, pad, pad * 2, pad)
            tv.glassSelected = i == selected
            tv.setTypeface(null, if (i == selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = pad / 2
            tv.layoutParams = lp
            tv.setOnClickListener {
                if (selected != i) {
                    selected = i
                    buildChips()
                    loadDir()
                }
            }
            chips.addView(tv)
        }
    }

    private fun goUp() {
        val acc = curAcc() ?: return
        val p = curPath()
        if (p == "/") return
        val up = p.substringBeforeLast('/', "").ifEmpty { "/" }
        paths[acc.id] = up
        loadDir()
    }

    private fun updateBack() {
        backCb.isEnabled = viewReady && !isHidden && curPath() != "/"
    }

    private fun showMsg(t: String) {
        messageText.text = t
        messageText.visibility = View.VISIBLE
    }

    private fun loadDir() {
        val acc = curAcc()
        if (acc == null) {
            pathText.text = ""
            items = emptyList()
            adapter.submit(items)
            showMsg(S.str(R.string.s035))
            return
        }
        val path = paths[acc.id] ?: "/"
        pathText.text = path
        val seq = ++loadSeq
        loadingBar.visibility = View.VISIBLE
        messageText.visibility = View.GONE
        updateBack()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { TeraBoxClient(acc.cookie) { }.browse(path) }
                if (seq != loadSeq) return@launch
                withContext(Dispatchers.IO) { store.fillThumbs(acc.id, list) }
                items = list.sortedWith(compareByDescending<ShareFile> { it.isDir }.thenBy { Fmt.nat(it.name) })
                adapter.submit(items)
                loadedKey = acc.id + "|" + path
                if (items.isEmpty()) showMsg(S.str(R.string.s036))
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (seq == loadSeq) {
                    items = emptyList()
                    adapter.submit(items)
                    showMsg("✖ ${e.message ?: e.toString()}")
                }
            } finally {
                if (seq == loadSeq) loadingBar.visibility = View.GONE
            }
        }
    }

    // ------------------------------------------------------------ item actions

    private fun onItemClick(f: ShareFile) {
        val acc = curAcc() ?: return
        if (f.isDir) {
            paths[acc.id] = f.path
            loadDir()
        } else if (f.isVideo()) {
            playFile(acc, f)
        } else {
            Toast.makeText(requireContext(), S.str(R.string.s037), Toast.LENGTH_SHORT).show()
        }
    }

    private fun onItemLong(anchor: View, f: ShareFile) {
        val acc = curAcc() ?: return
        if (!f.isDir && !f.isVideo()) return
        val m = PopupMenu(requireContext(), anchor)
        m.menu.add(0, 1, 0, if (f.isDir) S.str(R.string.s038) else S.str(R.string.s039))
        m.setOnMenuItemClickListener {
            if (f.isDir) addDirToLibrary(acc, f) else addFileToLibrary(acc, f)
            true
        }
        m.show()
    }

    /** Existing library entry for this account file, or a new one (not yet saved). */
    private fun entryFor(acc: Account, f: ShareFile, lib: List<LibEntry>): LibEntry {
        lib.firstOrNull { it.accountId == acc.id && it.remotePath == f.path }?.let { return it }
        val dir = f.path.substringBeforeLast('/', "")
        val rel = if (dir.startsWith("/TeraPlay")) dir.removePrefix("/TeraPlay") else dir
        return LibEntry(
            surl = "",
            fsId = f.fsId,
            name = f.name,
            folder = rel.ifEmpty { "/" },
            size = f.size,
            duration = 0,
            thumb = f.thumb,
            addedAt = System.currentTimeMillis(),
            remotePath = f.path,
            accountId = acc.id
        )
    }

    private fun playFile(acc: Account, f: ShareFile) {
        val lib = store.load()
        val videos = items.filter { !it.isDir && it.isVideo() }
        val entries = videos.map { entryFor(acc, it, lib) }
        store.addEntries(entries)
        ThumbCache.prefetchAll(requireContext(), entries)
        val i = videos.indexOfFirst { it.fsId == f.fsId }.coerceAtLeast(0)
        Actions.openPlayer(requireContext(), entries, entries[i])
    }

    private fun addFileToLibrary(acc: Account, f: ShareFile) {
        val n = store.addEntries(listOf(entryFor(acc, f, store.load())))
        Toast.makeText(
            requireContext(),
            if (n > 0) S.str(R.string.s040) else S.str(R.string.s041),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun collect(c: TeraBoxClient, dir: String, depth: Int, out: MutableList<ShareFile>) {
        for (f in c.browse(dir)) {
            if (f.isDir) {
                if (depth < 6 && out.size < 3000) collect(c, f.path, depth + 1, out)
            } else if (f.isVideo()) {
                out.add(f)
            }
        }
    }

    private fun addDirToLibrary(acc: Account, dir: ShareFile) {
        loadingBar.visibility = View.VISIBLE
        Toast.makeText(requireContext(), S.str(R.string.s042), Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val n = withContext(Dispatchers.IO) {
                    val found = mutableListOf<ShareFile>()
                    collect(TeraBoxClient(acc.cookie) { }, dir.path, 0, found)
                    val lib = store.load()
                    store.addEntries(found.map { entryFor(acc, it, lib) })
                }
                ThumbCache.prefetchAll(requireContext(), store.load())
                Toast.makeText(requireContext(), S.str(R.string.s043, n), Toast.LENGTH_LONG).show()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                showMsg("✖ ${e.message ?: e.toString()}")
            } finally {
                loadingBar.visibility = View.GONE
            }
        }
    }
}

class FilesAdapter(
    private val onClick: (ShareFile) -> Unit,
    private val onLong: (View, ShareFile) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<ShareFile> = emptyList()

    fun submit(l: List<ShareFile>) {
        items = l
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return object : RecyclerView.ViewHolder(v) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val f = items[position]
        val v = holder.itemView
        v.findViewById<TextView>(R.id.fileName).text = f.name
        v.findViewById<TextView>(R.id.fileMeta).text = if (f.isDir) S.str(R.string.s044) else Fmt.size(f.size)
        v.findViewById<ImageView>(R.id.fileIcon).setImageResource(
            when {
                f.isDir -> R.drawable.ic_files
                f.isVideo() -> R.drawable.ic_video
                else -> R.drawable.ic_file
            }
        )
        v.setOnClickListener { onClick(f) }
        v.setOnLongClickListener {
            onLong(v, f)
            true
        }
    }
}
