package com.teraplay.app

import android.util.Base64
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class TeraBoxException(message: String) : Exception(message)

data class ShareFile(
    val fsId: Long,
    val path: String,
    val name: String,
    val isDir: Boolean,
    val category: Int,
    val size: Long = 0,
    val duration: Int = 0,
    val thumb: String = ""
) {
    fun isVideo(): Boolean {
        if (category == 1) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "ts", "flv", "wmv", "3gp", "mpg", "mpeg")
    }
}

class ImportItem(val file: ShareFile, val destDir: String)

class ImportResult(val paths: Map<Long, String>, val failed: Int, val aborted: Boolean)

class StreamResult(val m3u8: String, val title: String, val remotePath: String = "")

private class Http(
    val code: Int,
    val body: String,
    val location: String?,
    val logid: String?,
    val regionPrefix: String?
)

/**
 * Kotlin port of the few calls we need from the Node.js "terabox-api" library:
 * updateAppData, checkLogin, shortUrlInfo, shortUrlList, createDir, shareTransfer, getStream.
 */
class TeraBoxClient(cookieInput: String, private val log: (String) -> Unit) {

    companion object {
        const val UA = "terabox;1.40.0.132;PC;PC-Windows;10.0.26100;WindowsTeraBox"
        const val DEST_DIR = "/TeraPlay"
        const val DOMAIN = "terabox.com"
        const val STREAM_TYPE = "M3U8_AUTO_480"

        /** "/Folder/Sub" (path inside the share) -> "/TeraPlay/Folder/Sub" (path in the user's account). */
        fun destDirFor(srcDir: String): String {
            val clean = srcDir.split("/").filter { it.isNotEmpty() }
                .joinToString("/") { it.replace(Regex("[\\\\:*?\"<>|]"), "_") }
            return if (clean.isEmpty()) DEST_DIR else "${DEST_DIR}/${clean}"
        }
    }

    private var whost = "https://www.${DOMAIN}"
    private val cookies = LinkedHashMap<String, String>()
    private var jsToken = ""
    private var bdsToken = ""
    private var logId = "0"
    private val madeDirs = HashSet<String>()

    private val appParams = listOf(
        "app_id" to "250528",
        "web" to "1",
        "channel" to "dubox",
        "clienttype" to "0"
    )

    // The original library removes ECDHE-RSA-AES128-SHA from the TLS cipher list; do the same here.
    private val tlsSpec: ConnectionSpec = run {
        val suites = ConnectionSpec.MODERN_TLS.cipherSuites
            ?.filter { it != CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA }
            ?: emptyList()
        ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .cipherSuites(*suites.toTypedArray())
            .build()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectionSpecs(listOf(tlsSpec, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    init {
        cookies["lang"] = "en"
        val c = cookieInput.trim().trim('"', '\'')
        if (c.contains("=")) {
            c.split(";").forEach { part ->
                val i = part.indexOf('=')
                if (i > 0) cookies[part.substring(0, i).trim()] = part.substring(i + 1).trim()
            }
        } else if (c.isNotEmpty()) {
            cookies["ndus"] = c
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun cookieHeader(): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun mergeCookies(r: Response) {
        for (h in r.headers("Set-Cookie")) {
            val first = h.substringBefore(';')
            val i = first.indexOf('=')
            if (i > 0) {
                val v = first.substring(i + 1).trim()
                if (v.isNotEmpty()) cookies[first.substring(0, i).trim()] = v
            }
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun form(vararg p: Pair<String, String>): String =
        p.joinToString("&") { enc(it.first) + "=" + enc(it.second) }

    private fun buildUrl(path: String, q: List<Pair<String, String>> = emptyList()): String {
        val b = (whost + path).toHttpUrl().newBuilder()
        q.forEach { b.addQueryParameter(it.first, it.second) }
        return b.build().toString()
    }

    private fun snip(s: String, n: Int = 400): String =
        if (s.length > n) s.substring(0, n) + "…" else s

    private fun http(
        url: String,
        post: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        cookieSuffix: String = "",
        saveCookies: Boolean = false
    ): Http {
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Cookie", cookieHeader() + cookieSuffix)
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        if (post != null) {
            b.post(post.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        }
        client.newCall(b.build()).execute().use { r ->
            if (saveCookies) mergeCookies(r)
            return Http(
                code = r.code,
                body = r.body?.string() ?: "",
                location = r.header("Location"),
                logid = r.header("logid"),
                regionPrefix = r.header("region-domain-prefix")
            )
        }
    }

    private fun parse(r: Http, what: String): JSONObject {
        if (r.code != 200) throw TeraBoxException("${what}: HTTP ${r.code} ${snip(r.body)}")
        return try {
            JSONObject(r.body)
        } catch (e: Exception) {
            throw TeraBoxException(S.str(R.string.s089, what, snip(r.body)))
        }
    }

    // ---------------------------------------------------------------- session

    /** Port of updateAppData(): scrapes /main for jsToken / bdstoken. */
    fun updateAppData() {
        var url = "${whost}/main"
        var body = ""
        var finalUrl = url
        for (i in 1..6) {
            val r = http(url, saveCookies = true)
            finalUrl = url
            if (r.logid != null) logId = r.logid
            if (r.code == 301 || r.code == 302 || r.code == 303 || r.code == 307) {
                var loc = r.location ?: break
                if (loc == "/login") loc = "${whost}/login"
                val resolved = url.toHttpUrl().resolve(loc) ?: break
                val newOrigin = "${resolved.scheme}://${resolved.host}"
                if (newOrigin != whost) {
                    whost = newOrigin
                    log("host → ${whost}")
                }
                url = resolved.toString()
                continue
            }
            body = r.body
            break
        }

        if (finalUrl.contains("/login")) {
            throw TeraBoxException(S.str(R.string.s090))
        }

        var tdata = JSONObject()
        val m = Regex("<script>var templateData = (.*);</script>").find(body)
        if (m != null) {
            try {
                tdata = JSONObject(m.groupValues[1].split(";</script>")[0])
            } catch (e: Exception) {
                log("templateData parse error: ${e.message}")
            }
        }

        var js = tdata.optString("jsToken", "")
        js = if (js.isNotEmpty()) {
            Regex("%28%22(.*)%22%29").find(js)?.groupValues?.get(1) ?: ""
        } else {
            Regex("window.jsToken%20%3D%20a%7D%3Bfn%28%22(.*)%22%29").find(body)?.groupValues?.get(1) ?: ""
        }
        jsToken = js
        bdsToken = tdata.optString("bdstoken", "")
        log("tokens: jsToken=${if (jsToken.isEmpty()) "NO" else "ok"}, bdstoken=${if (bdsToken.isEmpty()) "NO" else "ok"}")
    }

    private fun ensureTokens() {
        if (jsToken.isEmpty()) updateAppData()
        if (jsToken.isEmpty()) {
            throw TeraBoxException(S.str(R.string.s091))
        }
    }

    /** Port of checkLogin(). */
    private fun checkLogin() {
        for (attempt in 1..2) {
            val r = http(buildUrl("/api/check/login"))
            val region = r.regionPrefix
            if (!region.isNullOrEmpty() && attempt == 1) {
                whost = "https://${region}.${DOMAIN}"
                log("region host → ${whost}")
                continue
            }
            val j = parse(r, "check/login")
            if (j.optInt("errno", -1) != 0) {
                throw TeraBoxException(S.str(R.string.s092, j.optInt("errno")))
            }
            log("login ok (uk=${j.optString("uk")})")
            return
        }
    }

    // ---------------------------------------------------------------- share link

    fun extractSurl(input: String): String? {
        val t = input.trim()
        Regex("[?&]surl=([A-Za-z0-9_-]+)").find(t)?.let { return it.groupValues[1] }
        Regex("/s/1([A-Za-z0-9_-]+)").find(t)?.let { return it.groupValues[1] }
        return null
    }

    private fun shareInfo(surl: String): JSONObject {
        val browserId = Base64.encodeToString(ByteArray(44).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
        val r = http(
            buildUrl("/api/shorturlinfo", listOf("shorturl" to "1${surl}", "root" to "1")),
            cookieSuffix = "; browserid=${browserId}"
        )
        return parse(r, "shorturlinfo")
    }

    private fun shareList(surl: String, dir: String, retry: Boolean = true): JSONObject {
        ensureTokens()
        val q = mutableListOf(
            "jsToken" to jsToken,
            "shorturl" to surl,
            "by" to "name",
            "order" to "asc",
            "num" to "20000",
            "dir" to dir,
            "page" to "1"
        )
        if (dir.isEmpty()) q.add("root" to "1")
        val j = parse(http(buildUrl("/share/list", appParams + q)), "share/list")
        if (j.optInt("errno") == 4000020 && retry) {
            updateAppData()
            return shareList(surl, dir, false)
        }
        return j
    }

    private fun toShareFile(o: JSONObject): ShareFile {
        val path = o.optString("path", "")
        var name = o.optString("server_filename", "")
        if (name.isEmpty()) name = path.substringAfterLast('/')
        var thumb = ""
        val th = o.optJSONObject("thumbs")
        if (th != null) {
            for (k in listOf("url3", "url2", "url1", "icon")) {
                val u = th.optString(k, "")
                if (u.isNotEmpty()) {
                    thumb = u
                    break
                }
            }
        }
        return ShareFile(
            fsId = o.optLong("fs_id"),
            path = path,
            name = name,
            isDir = o.optInt("isdir") == 1,
            category = o.optInt("category", 0),
            size = o.optLong("size", 0),
            duration = o.optInt("duration", 0),
            thumb = thumb
        )
    }

    /** Walks folders recursively; collects every file and every folder path of the share. */
    private fun collectAll(
        surl: String,
        list: JSONArray?,
        depth: Int,
        files: MutableList<ShareFile>,
        dirs: MutableList<String>
    ) {
        if (list == null) return
        for (i in 0 until list.length()) {
            val f = toShareFile(list.getJSONObject(i))
            if (f.isDir) {
                dirs.add(f.path)
                if (depth < 8 && files.size < 20000) {
                    log("folder: ${f.path}")
                    collectAll(surl, shareList(surl, f.path).optJSONArray("list"), depth + 1, files, dirs)
                }
            } else {
                files.add(f)
            }
        }
    }

    // ---------------------------------------------------------------- transfer + stream

    private fun createDir(path: String) {
        try {
            val r = http(
                "${whost}/api/create?a=commit",
                post = form("path" to path, "isdir" to "1", "block_list" to "[]")
            )
            log("createDir(${path}): ${snip(r.body, 120)}")
        } catch (e: Exception) {
            log("createDir ignored: ${e.message}")
        }
    }

    /** Creates every level of [path] ("/TeraPlay/A/B") once; errors (already exists) are ignored. */
    private fun ensureDir(path: String) {
        var cur = ""
        for (seg in path.split("/").filter { it.isNotEmpty() }) {
            cur += "/${seg}"
            if (madeDirs.add(cur)) createDir(cur)
        }
    }

    /** name -> size of everything inside a folder of the user's own account (empty if the folder is missing). */
    private fun listDirInfo(dir: String): Map<String, Long> {
        val r = http(
            "${whost}/api/list",
            post = form(
                "order" to "name", "desc" to "0", "dir" to dir,
                "num" to "20000", "page" to "1", "showempty" to "0"
            )
        )
        val j = parse(r, "api/list")
        if (j.optInt("errno", -1) != 0) return emptyMap()
        val arr = j.optJSONArray("list") ?: return emptyMap()
        val m = HashMap<String, Long>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            m[o.optString("server_filename", "")] = o.optLong("size", 0)
        }
        return m
    }

    private fun listDir(dir: String): Set<String> = listDirInfo(dir).keys

    /** True if a file of this name (and same size, when sizes are known) is already in [existing]. */
    private fun sameFileIn(existing: Map<String, Long>, f: ShareFile): Boolean {
        val sz = existing[f.name] ?: return false
        return sz == f.size || sz == 0L || f.size == 0L
    }

    /** For every wanted folder: what is already inside it in this account (folders that do not exist are skipped). */
    fun existingNames(dirs: Collection<String>): Map<String, Map<String, Long>> {
        requireCookie()
        checkLogin()
        val top = listDirInfo(DEST_DIR).keys
        val out = HashMap<String, Map<String, Long>>()
        for (d in dirs) {
            val rest = d.removePrefix(DEST_DIR).trim('/')
            if (rest.isNotEmpty() && rest.substringBefore('/') !in top) continue
            out[d] = listDirInfo(d)
        }
        return out
    }

    /** Creates (empty) folders in this account. */
    fun makeDirs(dirs: Collection<String>) {
        requireCookie()
        checkLogin()
        dirs.forEach { ensureDir(it) }
    }

    /** Port of shareTransfer(): copies the shared file into the user's own account. Returns remote path. */
    private fun transfer(shareId: String, uk: String, f: ShareFile, destDir: String, retry: Boolean = true): String {
        ensureTokens()
        val q = appParams + listOf(
            "jsToken" to jsToken,
            "dp-logid" to logId,
            "ondup" to "newcopy",
            "async" to "1",
            "shareid" to shareId,
            "from" to uk
        )
        val r = http(
            buildUrl("/share/transfer", q),
            post = form("fsidlist" to "[${f.fsId}]", "path" to destDir),
            extraHeaders = mapOf("Referer" to whost)
        )
        val j = parse(r, "share/transfer")
        val errno = j.optInt("errno", -1)
        if (errno == 400810 && retry) {
            updateAppData()
            return transfer(shareId, uk, f, destDir, false)
        }
        if (errno != 0) {
            throw TeraBoxException(S.str(R.string.s093, errno, snip(r.body)))
        }
        log("transfer ok: ${snip(r.body, 200)}")
        val to = j.optJSONObject("extra")?.optJSONArray("list")?.optJSONObject(0)?.optString("to", "")
        return if (!to.isNullOrEmpty()) to else "${destDir}/${f.name}"
    }

    private fun getStream(path: String, type: String = STREAM_TYPE): String {
        val r = http(
            "${whost}/api/streaming",
            post = form("path" to path, "type" to type, "vip" to "2")
        )
        return r.body
    }

    // ---------------------------------------------------------------- main flow

    private fun requireCookie() {
        if (!cookies.containsKey("ndus")) throw TeraBoxException(S.str(R.string.s094))
    }

    private fun idsFrom(info: JSONObject): Pair<String, String> {
        var shareId = info.optString("shareid", "")
        if (shareId.isEmpty()) shareId = info.optString("share_id", "")
        var uk = info.optString("uk", "")
        if (uk.isEmpty()) uk = info.optString("share_uk", "")
        if (shareId.isEmpty() || uk.isEmpty()) {
            throw TeraBoxException(S.str(R.string.s095, snip(info.toString())))
        }
        return shareId to uk
    }

    private fun infoChecked(surl: String): JSONObject {
        val info = shareInfo(surl)
        val errno = info.optInt("errno", 0)
        log("shorturlinfo errno=${errno}")
        if (errno != 0) throw TeraBoxException(S.str(R.string.s096, errno, snip(info.toString())))
        return info
    }

    class ScanResult(val surl: String, val files: List<ShareFile>, val dirs: List<String>)

    /** Single-file or folder link -> every video inside (subfolders included). */
    fun scan(link: String): ScanResult {
        requireCookie()
        val surl = extractSurl(link)
            ?: throw TeraBoxException(S.str(R.string.s097))
        log("surl = ${surl}")
        checkLogin()
        val info = infoChecked(surl)
        var list = info.optJSONArray("list")
        if (list == null || list.length() == 0) {
            list = shareList(surl, "").optJSONArray("list")
        }
        val files = mutableListOf<ShareFile>()
        val dirs = mutableListOf<String>()
        collectAll(surl, list, 0, files, dirs)
        if (files.isEmpty() && dirs.isEmpty()) throw TeraBoxException(S.str(R.string.s098))
        log(S.str(R.string.s099, files.size, dirs.size))
        return ScanResult(surl, files, dirs)
    }

    /**
     * Returns the HLS playlist of one saved video. If [knownPath] (the copy already in the user's account)
     * still works it is reused, so the file is not copied again.
     */
    fun streamFor(
        surl: String,
        fsId: Long,
        name: String,
        type: String = STREAM_TYPE,
        knownPath: String = "",
        destDir: String = DEST_DIR
    ): StreamResult {
        requireCookie()
        checkLogin()

        if (knownPath.isNotEmpty()) {
            try {
                val body = getStream(knownPath, type)
                if (body.trimStart().startsWith("#EXTM3U")) {
                    log(S.str(R.string.s100))
                    return StreamResult(body, name, knownPath)
                }
                log(S.str(R.string.s101, snip(body, 160)))
            } catch (e: Exception) {
                log(S.str(R.string.s102, e.message))
            }
        }

        ensureDir(destDir)
        val remotePath = if (name in listDir(destDir)) {
            log(S.str(R.string.s103))
            "${destDir}/${name}"
        } else {
            val (shareId, uk) = idsFrom(infoChecked(surl))
            transfer(shareId, uk, ShareFile(fsId, "", name, false, 1), destDir)
        }
        log("remote path: ${remotePath}")

        var last = ""
        for (i in 1..15) {
            val body = getStream(remotePath, type)
            if (body.trimStart().startsWith("#EXTM3U")) {
                log("stream ready (try ${i})")
                return StreamResult(body, name, remotePath)
            }
            last = snip(body, 300)
            log("stream try ${i}: ${last}")
            Thread.sleep(2000)
        }
        throw TeraBoxException(S.str(R.string.s104, last))
    }

    /** Lists a folder of the account's own storage (used by the Files tab). */
    fun browse(dir: String): List<ShareFile> {
        requireCookie()
        checkLogin()
        val body = form(
            "order" to "name", "desc" to "0", "dir" to dir,
            "num" to "20000", "page" to "1", "showempty" to "0"
        )
        // With the web-app parameters Terabox also returns thumbnails; fall back to the plain call if refused.
        var j: JSONObject? = null
        var raw = ""
        for (withParams in listOf(true, false)) {
            val url = if (withParams) buildUrl("/api/list", appParams) else "${whost}/api/list"
            val r = http(url, post = body)
            val jj = parse(r, "api/list")
            if (jj.optInt("errno", -1) == 0) {
                j = jj
                break
            }
            raw = r.body
        }
        if (j == null) throw TeraBoxException(S.str(R.string.s105, snip(raw, 200)))
        val arr = j.optJSONArray("list") ?: return emptyList()
        return (0 until arr.length()).map { toShareFile(arr.getJSONObject(it)) }
    }

    /** Logs in / picks the right regional host once, before several calls. */
    fun prepare() {
        requireCookie()
        checkLogin()
    }

    /** Total length in seconds, from the #EXTINF lines of the stream playlist (no playback needed). 0 = unknown. */
    fun durationOf(path: String): Int {
        var body = getStream(path, "M3U8_AUTO_240")
        if (!body.trimStart().startsWith("#EXTM3U")) body = getStream(path, STREAM_TYPE)
        if (!body.trimStart().startsWith("#EXTM3U")) return 0
        var total = 0.0
        Regex("#EXTINF:([0-9.]+)").findAll(body).forEach { total += it.groupValues[1].toDouble() }
        return Math.round(total).toInt()
    }

    /** Free space of this account: (total, used) in bytes. */
    fun quota(): Pair<Long, Long> {
        requireCookie()
        checkLogin()
        val j = parse(http(buildUrl("/api/quota", listOf("checkexpire" to "1", "checkfree" to "1"))), "quota")
        val errno = j.optInt("errno", -1)
        if (errno != 0) throw TeraBoxException("quota errno=${errno}")
        return j.optLong("total", 0) to j.optLong("used", 0)
    }

    /**
     * Copies the given share files, one by one, into this account (each into its own destDir),
     * then checks the folders to see which files really arrived.
     */
    fun importBatch(
        surl: String,
        items: List<ImportItem>,
        progress: (done: Int, total: Int, name: String) -> Unit
    ): ImportResult {
        requireCookie()
        checkLogin()
        val (shareId, uk) = idsFrom(infoChecked(surl))

        val paths = HashMap<Long, String>()
        val pending = LinkedHashMap<String, MutableList<ShareFile>>()
        val existingByDir = HashMap<String, Map<String, Long>>()
        var failed = 0
        var consecutive = 0
        var done = 0
        var aborted = false

        for (item in items) {
            val f = item.file
            done++
            progress(done, items.size, f.name)
            val existing = existingByDir.getOrPut(item.destDir) {
                ensureDir(item.destDir)
                listDirInfo(item.destDir)
            }
            if (sameFileIn(existing, f)) {
                paths[f.fsId] = "${item.destDir}/${f.name}"
                continue
            }
            try {
                transfer(shareId, uk, f, item.destDir)
                pending.getOrPut(item.destDir) { mutableListOf() }.add(f)
                consecutive = 0
                Thread.sleep(400)
            } catch (e: Exception) {
                failed++
                consecutive++
                log("✖ ${f.name}: ${e.message}")
                if (consecutive >= 3) {
                    aborted = true
                    break
                }
            }
        }

        for ((dir, files) in pending) {
            var missing: List<ShareFile> = files.toList()
            for (attempt in 1..8) {
                val names = listDir(dir)
                missing.filter { it.name in names }.forEach { paths[it.fsId] = "${dir}/${it.name}" }
                missing = missing.filter { it.name !in names }
                if (missing.isEmpty()) break
                Thread.sleep(2500)
            }
            failed += missing.size
        }
        return ImportResult(paths, failed, aborted)
    }
}
