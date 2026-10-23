package io.embodit.rehearsalhall.ui.hall.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.rehearsalhall.R
import io.embodit.rehearsalhall.ui.hall.LanguagePref
import java.util.Locale

/**
 * Copy for the language chosen in the app (follow system / 中文 / English), switched at run time without restarting the
 * Stage: the screen provides a Resources object for that locale and every panel reads its strings from it (Spatial
 * Ruler).
 */
val LocalHallResources = staticCompositionLocalOf<Resources> { error("LocalHallResources not provided") }

fun localizedResources(context: Context, language: LanguagePref): Resources {
    val tag =
        when (language) {
            LanguagePref.SYSTEM -> return context.resources
            LanguagePref.ZH -> "zh-CN"
            LanguagePref.EN -> "en"
        }
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(configuration).resources
}

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalHallResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalHallResources.current.getString(id, *args)

fun Resources.zh(): Boolean = getString(R.string.lang_code) == "zh"

fun Resources.zoneName(zone: Int): String = getStringArray(R.array.zone_names)[zone.coerceIn(0, 8)]

/** Panel sizes (design-package `panels[].sizeDp`), in dp. */
object PanelSizes {
    const val LECTERN_W = 760f
    const val LECTERN_H = 480f
    const val SIGN_W = 320f
    const val SIGN_H = 96f
}
