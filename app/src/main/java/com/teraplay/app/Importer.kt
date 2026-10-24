package com.teraplay.app

/**
 * "Add link" workflow: scan the share (every file, every sub-folder), skip whatever already exists,
 * pick accounts with enough free space, copy the rest one by one into /TeraPlay/<same folder names>,
 * and save the videos to the library.
 */
object Importer {

    private fun dirOf(f: ShareFile): String =
        TeraBoxClient.destDirFor(f.path.substringBeforeLast('/', ""))

    fun run(
        link: String,
        accStore: AccountStore,
        store: LibraryStore,
        log: (String) -> Unit,
        progress: (done: Int, total: Int, text: String) -> Unit
    ): String {
        val accounts = accStore.load()
        if (accounts.isEmpty()) throw TeraBoxException(S.str(R.string.s064))

        val scan = TeraBoxClient(accounts[0].cookie, log).scan(link)
        val surl = scan.surl
        val videoCount = scan.files.count { it.isVideo() }

        // 1) free space of every account
        val free = HashMap<String, Long>()
        for (a in accounts) {
            try {
                val (t, u) = TeraBoxClient(a.cookie, log).quota()
                val n = a.copy(total = t, used = u, checked = System.currentTimeMillis())
                accStore.update(n)
                free[a.id] = n.free
            } catch (e: Exception) {
                log(S.str(R.string.s065, a.name, e.message))
            }
        }

        // 2) what is already inside the accounts? (only needed to avoid a second copy in ANOTHER account;
        //    inside one account the copy step itself skips existing files)
        val allDirs = (scan.files.map { dirOf(it) } + scan.dirs.map { TeraBoxClient.destDirFor(it) }).toSet()
        val existing = HashMap<String, Map<String, Map<String, Long>>>()
        if (accounts.size > 1) {
            for (a in accounts) {
                try {
                    existing[a.id] = TeraBoxClient(a.cookie, log).existingNames(allDirs)
                } catch (e: Exception) {
                    log(S.str(R.string.s066, a.name, e.message))
                }
            }
        }

        // 3) decide for every file: already there / copy into account X / no space
        val lib = store.load()
        val libByKey = lib.associateBy { it.key }
        val todo = mutableListOf<Pair<ShareFile, String>>()
        val foundInAccount = mutableListOf<Pair<ShareFile, Pair<String, String>>>() // video, (accountId, remotePath)
        var noSpace = 0
        var alreadyDone = 0

        for (f in scan.files) {
            val d = dirOf(f)
            val isVideo = f.isVideo()
            val ex = libByKey["${surl}:${f.fsId}"]

            if (isVideo) {
                // same share video already in the library with a working copy
                if (ex != null && ex.remotePath.isNotEmpty()) {
                    alreadyDone++
                    continue
                }
                // same video (name + size) already copied through another link
                if (f.size > 0 && lib.any { it.name == f.name && it.size == f.size && it.remotePath.isNotEmpty() }) {
                    alreadyDone++
                    continue
                }
            }

            // already present in one of the accounts at the same place
            val holder = accounts.firstOrNull { a ->
                val sz = existing[a.id]?.get(d)?.get(f.name)
                sz != null && (sz == f.size || sz == 0L || f.size == 0L)
            }
            if (holder != null) {
                alreadyDone++
                if (isVideo) foundInAccount.add(f to (holder.id to "${d}/${f.name}"))
                continue
            }

            var acc = ex?.accountId.orEmpty()
            if (acc.isEmpty() || accounts.none { it.id == acc }) {
                acc = accounts.firstOrNull { (free[it.id] ?: -1L) >= f.size }?.id ?: ""
            }
            if (acc.isEmpty()) {
                noSpace++
                continue
            }
            free[acc] = (free[acc] ?: 0L) - f.size
            todo.add(f to acc)
        }

        // library entries for the videos (non-video files are only copied)
        val videoFiles = todo.map { it.first }.filter { it.isVideo() } + foundInAccount.map { it.first }
        val accountOf = HashMap<Long, String>()
        todo.forEach { accountOf[it.first.fsId] = it.second }
        foundInAccount.forEach { accountOf[it.first.fsId] = it.second.first }
        store.addAll(surl, videoFiles, accountOf)
        if (foundInAccount.isNotEmpty()) {
            val cur = store.load().associateBy { it.key }
            store.updateMany(foundInAccount.mapNotNull { (f, loc) ->
                cur["${surl}:${f.fsId}"]?.copy(accountId = loc.first, remotePath = loc.second)
            })
        }

        // 4) folders that contain no files at all are still created
        val filledDirs = scan.files.map { dirOf(it) }.toSet()
        val emptyDirs = scan.dirs.map { TeraBoxClient.destDirFor(it) }.filter { d ->
            filledDirs.none { it == d || it.startsWith("${d}/") }
        }
        if (emptyDirs.isNotEmpty()) {
            val primary = accounts.firstOrNull { it.id == todo.firstOrNull()?.second } ?: accounts[0]
            try {
                TeraBoxClient(primary.cookie, log).makeDirs(emptyDirs)
            } catch (e: Exception) {
                log(S.str(R.string.s067, e.message))
            }
        }

        // 5) copy, account by account
        var copied = 0
        var failed = 0
        var aborted = false
        var base = 0
        for (acc in accounts) {
            val mine = todo.filter { it.second == acc.id }.map { it.first }
            if (mine.isEmpty()) continue
            val client = TeraBoxClient(acc.cookie, log)
            val items = mine.map { ImportItem(it, dirOf(it)) }
            val res = client.importBatch(surl, items) { d, _, name ->
                progress(base + d, todo.size, "${acc.name}: ${name}")
            }
            base += mine.size
            copied += res.paths.size
            failed += res.failed
            if (res.aborted) aborted = true

            val cur = store.load().associateBy { it.key }
            val updates = mine.filter { it.isVideo() }.mapNotNull { f ->
                val e = cur["${surl}:${f.fsId}"] ?: return@mapNotNull null
                e.copy(accountId = acc.id, remotePath = res.paths[f.fsId] ?: e.remotePath)
            }
            store.updateMany(updates)

            try {
                val (t, u) = TeraBoxClient(acc.cookie, log).quota()
                accStore.update(acc.copy(total = t, used = u, checked = System.currentTimeMillis()))
            } catch (e: Exception) {
                // ignore
            }
        }

        val sb = StringBuilder(S.str(R.string.s068, scan.files.size, videoCount, scan.dirs.size))
        sb.append(S.str(R.string.s069, copied))
        if (alreadyDone > 0) sb.append(S.str(R.string.s070, alreadyDone))
        if (failed > 0) sb.append(S.str(R.string.s071, failed))
        if (noSpace > 0) sb.append(S.str(R.string.s072, noSpace))
        if (aborted) sb.append(S.str(R.string.s073))
        if (noSpace > 0) sb.append(S.str(R.string.s074))
        return sb.toString()
    }
}
