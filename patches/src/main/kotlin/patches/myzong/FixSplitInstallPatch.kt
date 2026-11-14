package patches.myzong

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import org.w3c.dom.Node

/*
 * MyZong ships as a split App Bundle (base + config.<abi> + config.<density>). Its base manifest
 * declares the splits as REQUIRED:
 *   <manifest android:requiredSplitTypes="base__abi,base__density" ...>
 *   <meta-data android:name="com.android.vending.splits.required" android:value="true"/>
 *   <meta-data android:name="com.android.vending.splits" .../>
 *
 * A patched app is installed as ONE standalone APK (Morphe/the Manager merges the splits in first),
 * so those config splits no longer exist as separately-installed packages. On Android 12+ the
 * framework's missing-splits check (PackageManager.isMissingRequiredSplits / requiredSplitTypes)
 * then sees the required splits absent and aborts the process the moment it launches — the app
 * "just keeps crashing" with no usable screen. Stripping the split-required markers makes the merged
 * APK a complete standalone package again. No-op (removeAttribute/no match) on any build that lacks
 * them, so it is safe for every MyZong version.
 */
internal val fixSplitInstallPatch = resourcePatch(
    description = "Strips the split-APK 'required splits' markers so the merged, patched APK installs " +
        "and launches as a standalone package instead of aborting on a missing-splits check.",
) {
    compatibleWith(COMPATIBILITY_MYZONG)

    finalize {
        document("AndroidManifest.xml").use { doc ->
            // <manifest android:requiredSplitTypes=.. android:splitTypes=..>
            doc.documentElement?.apply {
                removeAttribute("android:requiredSplitTypes")
                removeAttribute("android:splitTypes")
            }

            // <application android:isSplitRequired="true"> (not always present)
            (doc.getElementsByTagName("application").item(0) as? Element)
                ?.removeAttribute("android:isSplitRequired")

            // <meta-data android:name="com.android.vending.splits[.required]" ...>
            val metas = doc.getElementsByTagName("meta-data")
            val toRemove = mutableListOf<Node>()
            for (i in 0 until metas.length) {
                val el = metas.item(i) as Element
                when (el.getAttribute("android:name")) {
                    "com.android.vending.splits.required",
                    "com.android.vending.splits",
                    -> toRemove.add(el)
                }
            }
            toRemove.forEach { it.parentNode.removeChild(it) }
        }
    }
}
