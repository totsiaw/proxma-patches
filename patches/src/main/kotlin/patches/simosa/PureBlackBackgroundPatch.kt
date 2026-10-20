package patches.simosa

import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/*
 * Pure black (AMOLED) background for Simosa.
 *
 * Evidence (3.3.5, com.jazz.jazzworld):
 *  - The application theme is `Theme.CleanArchitecture`, declared in BOTH res/values/styles.xml and
 *    res/values-night/styles.xml, with parent `Theme.Material3.DayNight.NoActionBar`.
 *  - Most of the app's 647 layouts set no background at all on their root, so the screen backdrop is
 *    whatever the theme resolves (Material3 light = near-white #FFFBFE, dark = #141218).
 *  - The app's own dark values are the Android Studio template's, not hand-tuned: values-night sets
 *    colorPrimary AND colorOnPrimary to the same `white` (#ffdadada) — in night mode the theme's
 *    primary content is invisible. So "just use the app's dark theme" is not a usable answer.
 *
 * What this patch does, all at the theme level (no layout or code changes):
 *  1. Pins the theme parent to the explicit DARK Material3 theme, so the black backdrop applies even
 *     when the phone is in light mode (the request is for a black app, not "black when my phone is
 *     dark").
 *  2. Sets the window background, the theme background and every Material3 surface/container tone to
 *     #000000 — window, screens, sheets, dialogs, menus.
 *  3. Repairs the template's broken night primary/secondary so text on brand-coloured buttons stays
 *     visible (brand red/orange with white content, matching @color/colorPrimary and
 *     @color/colorOrange), and forces light status/navigation bar icons so they read on black.
 *
 * Deliberately NOT touched: the app's own card/panel colours (e.g. the many @color/colorWhite and
 * @android:color/white card backgrounds), because the app paints dark text on those panels; turning
 * them black would make that text unreadable. Brand colours and artwork are untouched.
 */

private const val APP_THEME = "Theme.CleanArchitecture"
private const val DARK_PARENT = "@style/Theme.Material3.Dark.NoActionBar"

/** style item name -> value, applied to the app theme in every values bucket that declares it. */
private val PURE_BLACK_ITEMS = listOf(
    // The backdrop itself.
    "android:windowBackground" to "#ff000000",
    "android:colorBackground" to "#ff000000",
    // Material3 surface tones: screens, sheets, dialogs, menus.
    "colorSurface" to "#ff000000",
    "colorSurfaceDim" to "#ff000000",
    "colorSurfaceContainer" to "#ff000000",
    "colorSurfaceContainerLow" to "#ff000000",
    "colorSurfaceContainerLowest" to "#ff000000",
    "colorSurfaceContainerHigh" to "#ff000000",
    "colorSurfaceContainerHighest" to "#ff000000",
    // System bars: black bars, light icons.
    "android:navigationBarColor" to "#ff000000",
    "android:windowLightStatusBar" to "false",
    "android:windowLightNavigationBar" to "false",
    // Brand accents, pinned so the dark theme keeps visible button text.
    "colorPrimary" to "@color/colorPrimary",
    "colorOnPrimary" to "@color/colorWhite",
    "colorSecondary" to "@color/colorOrange",
    "colorOnSecondary" to "@color/colorWhite",
)

private val THEME_FILES = listOf(
    "res/values/styles.xml",
    "res/values-night/styles.xml",
)

/** Sets [name]/[value] on the `<style>` named [styleName], replacing an existing item if present. */
private fun Element.upsertItem(doc: org.w3c.dom.Document, name: String, value: String) {
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

private fun ResourcePatchContext.makeThemePureBlack(path: String) {
    val doc = document(path)
    try {
        val styles = doc.getElementsByTagName("style")
        var theme: Element? = null
        for (i in 0 until styles.length) {
            val style = styles.item(i) as? Element ?: continue
            if (style.getAttribute("name") == APP_THEME) {
                theme = style
                break
            }
        }
        val style = theme ?: run {
            val created = doc.createElement("style")
            created.setAttribute("name", APP_THEME)
            doc.getDocumentElement().appendChild(created)
            created
        }
        style.setAttribute("parent", DARK_PARENT)
        PURE_BLACK_ITEMS.forEach { (name, value) -> style.upsertItem(doc, name, value) }
    } finally {
        doc.close()
    }
}

@Suppress("unused")
val pureBlackBackgroundPatch = resourcePatch(
    name = "Pure black background",
    description = "Turns Simosa's app background and Material surfaces pure black (#000000) for " +
        "OLED screens: window, screens, sheets, dialogs and menus go true black and the app keeps " +
        "its dark theme even when the phone is in light mode. Card and button colours are left as " +
        "they are so text on them stays readable.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SIMOSA)

    execute {
        THEME_FILES.forEach { path -> makeThemePureBlack(path) }
    }
}
