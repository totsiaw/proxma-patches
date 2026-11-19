package patches.myzong

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Document
import org.w3c.dom.Element

/*
 * Dark mode for MyZong that follows the phone's system theme (issue #36).
 *
 * Evidence (5.20.20.121, also 5.19.19.112):
 *  - The application theme is `AppTheme` (res/values + res/values-v23), parent
 *    `Theme.MaterialComponents.Light.NoActionBar`. The app ships no dark palette of its own:
 *    values-night only holds the library DayNight aliases, and the one app style in it
 *    (`Theme.MyZongApp`) is a copy of the light one. Nothing in the app calls
 *    AppCompatDelegate.setDefaultNightMode, so the app's configuration tracks the system uiMode.
 *  - Most screens are classic Views (625 XML layouts); a minority are Jetpack Compose.
 *
 * Approach: Android's own force-dark renderer (API 29+). The framework darkens a window when ALL of:
 * the window's configuration is UI_MODE_NIGHT_YES (the phone is in dark mode), the theme has
 * android:isLightTheme=true (inherited from Theme.Material.Light), and the theme has
 * android:forceDarkAllowed=true. The renderer flips light backgrounds dark and dark text light
 * together, per draw op, so text stays readable; photos/bitmaps are left as they are. When the phone
 * is in light mode nothing changes. That is exactly "follow the system theme" without having to
 * invent a second palette for several hundred screens.
 *
 *  1. Resources: android:forceDarkAllowed=true is set on the two roots every AppCompat/
 *     MaterialComponents light theme in the APK inherits from (Platform.V21.AppCompat.Light, and
 *     Platform.V25.AppCompat.Light which replaces it on API 25+), so activities, dialogs, bottom
 *     sheets and pickers all follow; and on the app's own themes (AppTheme, Theme.MyZongApp) in
 *     every values bucket that declares them, in case a theme further down ever sets it false.
 *  2. Bytecode: Compose's AndroidComposeView calls `disallowForceDark(view)` on API 29+, which opts
 *     every Compose screen out of force dark — those screens would stay white inside an otherwise
 *     dark app. That helper (class name obfuscated, method name kept) is turned into a no-op so the
 *     Compose screens darken the same way as the rest.
 */

private val FORCE_DARK_STYLES = setOf(
    "Platform.V21.AppCompat.Light",
    "Platform.V25.AppCompat.Light",
    "AppTheme",
    "Theme.MyZongApp",
)

private val STYLE_FILES = listOf(
    "res/values/styles.xml",
    "res/values-v23/styles.xml",
    "res/values-v25/styles.xml",
    "res/values-night/styles.xml",
)

private fun Element.upsertItem(doc: Document, name: String, value: String) {
    val items = getElementsByTagName("item")
    for (i in 0 until items.length) {
        val item = items.item(i) as? Element ?: continue
        if (item.getAttribute("name") == name) {
            item.textContent = value
            return
        }
    }
    val item = doc.createElement("item")
    item.setAttribute("name", name)
    item.textContent = value
    appendChild(item)
}

/** Returns the names of the styles in [path] that now allow force dark (empty if the file is absent). */
private fun ResourcePatchContext.allowForceDark(path: String): List<String> {
    val doc = try {
        document(path)
    } catch (_: Exception) {
        return emptyList() // bucket not present in this build
    }
    val touched = mutableListOf<String>()
    doc.use {
        val styles = doc.getElementsByTagName("style")
        for (i in 0 until styles.length) {
            val style = styles.item(i) as? Element ?: continue
            val name = style.getAttribute("name")
            if (name in FORCE_DARK_STYLES) {
                style.upsertItem(doc, "android:forceDarkAllowed", "true")
                touched += name
            }
        }
    }
    return touched
}

private val allowForceDarkResourcePatch = resourcePatch(
    description = "Allows Android's force-dark renderer on MyZong's light themes.",
) {
    compatibleWith(COMPATIBILITY_MYZONG)

    execute {
        val touched = STYLE_FILES.flatMap { allowForceDark(it) }.toSet()
        // Without the light-theme root the app's own activities would not follow; fail loudly
        // rather than ship a no-op.
        check("Platform.V21.AppCompat.Light" in touched) {
            "MyZong light theme root not found; styles touched: $touched"
        }
        check("AppTheme" in touched) { "MyZong AppTheme not found; styles touched: $touched" }
    }
}

/** Compose's `AndroidComposeViewForceDarkModeQ.disallowForceDark(View)` (class name obfuscated). */
internal val composeDisallowForceDarkFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    custom = { m, _ -> m.name == "disallowForceDark" },
)

@Suppress("unused")
val followSystemDarkModePatch = bytecodePatch(
    name = "Follow system dark mode",
    description = "Makes MyZong go dark when your phone is in dark mode and stay light when it is " +
        "not, using Android's built-in dark rendering (Android 10 and newer). Backgrounds turn dark " +
        "and text turns light together; pictures are left as they are.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_MYZONG)
    dependsOn(fixSplitInstallPatch, allowForceDarkResourcePatch)

    execute {
        // Let the Compose screens follow too instead of staying white inside a dark app.
        composeDisallowForceDarkFingerprint.method.addInstructions(0, "return-void")
    }
}
