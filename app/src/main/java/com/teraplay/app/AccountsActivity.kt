package com.teraplay.app

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AccountsActivity : AppCompatActivity() {

    private lateinit var accStore: AccountStore
    private lateinit var container: LinearLayout
    private lateinit var summary: TextView
    private val errors = HashMap<String, String>()
    private var refreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        S.res = resources
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accounts)
        Glass.setup(this, findViewById(R.id.content))
        accStore = AccountStore(this)
        container = findViewById(R.id.container)
        summary = findViewById(R.id.summary)

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<View>(R.id.addAccBtn).setOnClickListener { showAddDialog() }
        findViewById<View>(R.id.refreshBtn).setOnClickListener { refreshAll() }

        render()
        refreshAll()
    }

    private fun render() {
        container.removeAllViews()
        val list = accStore.load()
        for (a in list) {
            val v = layoutInflater.inflate(R.layout.item_account, container, false)
            v.findViewById<TextView>(R.id.accName).text = a.name
            val st = v.findViewById<TextView>(R.id.accStorage)
            val bar = v.findViewById<ProgressBar>(R.id.accBar)
            val err = v.findViewById<TextView>(R.id.accError)
            if (a.total > 0) {
                st.text = S.str(R.string.s003, Fmt.size(a.free), Fmt.size(a.used), Fmt.size(a.total))
                bar.max = 1000
                bar.progress = ((a.used * 1000) / a.total).toInt().coerceIn(0, 1000)
            } else {
                st.text = S.str(R.string.s004)
                bar.progress = 0
            }
            val e = errors[a.id]
            if (e != null) {
                err.text = "✖ ${e}"
                err.visibility = View.VISIBLE
            } else {
                err.visibility = View.GONE
            }
            v.findViewById<View>(R.id.accDelete).setOnClickListener { confirmDelete(a) }
            container.addView(v)
        }
        val ok = list.filter { it.total > 0 }
        summary.text = if (list.isEmpty()) {
            S.str(R.string.s005)
        } else {
            S.str(R.string.s006, list.size, Fmt.size(ok.sumOf { it.free }), Fmt.size(ok.sumOf { it.total }))
        }
    }

    private fun refreshAll() {
        if (refreshing) return
        refreshing = true
        summary.text = S.str(R.string.s007)
        lifecycleScope.launch {
            for (a in accStore.load()) {
                try {
                    val (t, u) = withContext(Dispatchers.IO) { TeraBoxClient(a.cookie) { }.quota() }
                    accStore.update(a.copy(total = t, used = u, checked = System.currentTimeMillis()))
                    errors.remove(a.id)
                } catch (e: Exception) {
                    errors[a.id] = e.message ?: e.toString()
                }
            }
            refreshing = false
            render()
        }
    }

    private fun showAddDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, pad / 2, pad, 0)

        val name = EditText(this)
        name.hint = S.str(R.string.s008)
        name.setSingleLine()
        name.setText(S.str(R.string.s009, accStore.load().size + 1))

        val cookie = EditText(this)
        cookie.hint = S.str(R.string.s010)
        cookie.setSingleLine()

        box.addView(name)
        box.addView(cookie)

        AlertDialog.Builder(this)
            .setTitle(S.str(R.string.s011))
            .setMessage(S.str(R.string.s012))
            .setView(box)
            .setPositiveButton(S.str(R.string.s013)) { _, _ ->
                val c = cookie.text.toString().trim()
                val n = name.text.toString().trim().ifEmpty { S.str(R.string.s001) }
                if (c.isNotEmpty()) {
                    accStore.add(n, c)
                    render()
                    refreshAll()
                }
            }
            .setNegativeButton(S.str(R.string.s014), null)
            .show()
    }

    private fun confirmDelete(a: Account) {
        AlertDialog.Builder(this)
            .setMessage(S.str(R.string.s015, a.name))
            .setPositiveButton(S.str(R.string.s016)) { _, _ ->
                accStore.remove(a.id)
                render()
            }
            .setNegativeButton(S.str(R.string.s017), null)
            .show()
    }
}
