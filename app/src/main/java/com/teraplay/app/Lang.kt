package com.teraplay.app

import android.content.Context
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Texts for code that has no Activity at hand (errors, summaries). Updated by every screen when it starts. */
object S {
    lateinit var res: Resources

    fun str(id: Int, vararg args: Any?): String =
        if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
}

/** App language: English (default) or Bengali, saved locally and applied app-wide. */
object Lang {
    fun saved(ctx: Context): String =
        ctx.applicationContext.getSharedPreferences("teraplay", Context.MODE_PRIVATE).getString("lang", "en") ?: "en"

    fun apply(code: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
    }

    fun choose(ctx: Context, code: String) {
        ctx.applicationContext.getSharedPreferences("teraplay", Context.MODE_PRIVATE)
            .edit().putString("lang", code).apply()
        apply(code)
    }
}
