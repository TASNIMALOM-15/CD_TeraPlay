package com.teraplay.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Account(
    val id: String,
    val name: String,
    val cookie: String,
    val total: Long = 0,
    val used: Long = 0,
    val checked: Long = 0
) {
    val free: Long get() = (total - used).coerceAtLeast(0)
}

/** Saved Terabox accounts (ndus cookie + last known storage), in filesDir/accounts.json. */
class AccountStore(ctx: Context) {
    private val file = File(ctx.filesDir, "accounts.json")

    companion object {
        private val lock = Any()
    }

    fun load(): List<Account> = synchronized(lock) { loadUnlocked() }

    private fun loadUnlocked(): List<Account> {
        val out = mutableListOf<Account>()
        if (!file.exists()) return out
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Account(
                        id = o.getString("id"),
                        name = o.optString("name", S.str(R.string.s001)),
                        cookie = o.getString("cookie"),
                        total = o.optLong("total", 0),
                        used = o.optLong("used", 0),
                        checked = o.optLong("checked", 0)
                    )
                )
            }
        } catch (e: Exception) {
            // ignore corrupted file
        }
        return out
    }

    private fun saveUnlocked(list: List<Account>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("cookie", it.cookie)
                    .put("total", it.total).put("used", it.used).put("checked", it.checked)
            )
        }
        file.writeText(arr.toString())
    }

    fun add(name: String, cookie: String): Account = synchronized(lock) {
        val list = loadUnlocked().toMutableList()
        val a = Account(System.currentTimeMillis().toString(36), name, cookie)
        list.add(a)
        saveUnlocked(list)
        a
    }

    fun update(a: Account) = synchronized(lock) {
        saveUnlocked(loadUnlocked().map { if (it.id == a.id) a else it })
    }

    fun remove(id: String) = synchronized(lock) {
        saveUnlocked(loadUnlocked().filter { it.id != id })
    }

    /** Cookie of the account [id]; falls back to the first account for old entries without an id. */
    fun cookieFor(id: String): String? {
        val list = load()
        return (list.firstOrNull { it.id == id } ?: list.firstOrNull())?.cookie
    }

    /** Older versions kept a single cookie in preferences: turn it into the first account. */
    fun migrate(prefs: SharedPreferences) {
        val old = prefs.getString("cookie", "").orEmpty().trim()
        if (old.isNotEmpty() && load().isEmpty()) add(S.str(R.string.s002), old)
        if (old.isNotEmpty()) prefs.edit().remove("cookie").apply()
    }
}
