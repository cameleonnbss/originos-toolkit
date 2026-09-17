package dev.cameleonnbss.originostoolkit.core

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/**
 * The interface language, chosen in Settings rather than only inherited from
 * Android's own setting.
 *
 * Why not `AppCompatDelegate.setApplicationLocales`: that needs `appcompat` and
 * an `AppCompatActivity`, and this app is a plain `ComponentActivity` with no
 * appcompat anywhere in its tree. Pulling in a whole support library to store
 * one string would be the largest dependency in the project for the smallest
 * feature.
 *
 * So the locale is pinned the old-fashioned way — `attachBaseContext` hands the
 * activity a configuration-pinned context — which works on every API this app
 * supports and costs nothing. `resConfigs` already ships `en`, `fr` and `zh`, so
 * the strings are in the APK; this only decides which ones get resolved.
 *
 * Services keep the application context and therefore the *system* language.
 * That is a deliberate limit, not an oversight: the notification channels are
 * created once, and re-creating them on a language change would rename channels
 * the user may have already tuned.
 */
object AppLanguage {

    /** Stored value meaning "whatever Android is set to". */
    const val SYSTEM = ""

    /**
     * The offered languages, in the order they are shown.
     *
     * Every entry must have a `values-<tag>` folder, or picking it silently does
     * nothing — which is exactly the bug this list was added alongside.
     */
    val CHOICES: List<Pair<String, String>> = listOf(
        "en" to "English",
        "fr" to "Français",
        "zh" to "中文",
    )

    /**
     * A [base] context pinned to [tag], or [base] untouched when following the
     * system.
     *
     * The process default locale is updated too, so `String.format` and the
     * `%.0f` calls scattered through the UI agree with the resources.
     */
    fun wrap(base: Context, tag: String): Context {
        if (tag.isBlank()) {
            // Put the process default back as well. Without this, switching from
            // French back to "System" would keep formatting numbers in French
            // until the process happened to restart.
            Locale.setDefault(Resources.getSystem().configuration.locales[0])
            return base
        }

        val locale = Locale.forLanguageTag(tag)
        if (locale.language.isEmpty()) return base

        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLocales(LocaleList(locale))
        return base.createConfigurationContext(configuration)
    }

    /**
     * Persist [tag] and restart [context] so the new resources are resolved.
     *
     * A recreate is enough because the locale is applied in `attachBaseContext`,
     * which runs again for the new activity instance.
     */
    fun apply(context: Context, prefs: Prefs, tag: String) {
        prefs.languageTag = tag
        (context as? Activity)?.recreate()
    }
}
