package patches.rox

import app.morphe.patcher.patch.resourcePatch

/*
 * ROX 3.0.14's <application> element carries two android:name attributes:
 *   android:name=".MyApplication"                       (the app's own Application)
 *   android:name="com.pairip.application.Application"    (PairIP's licence wrapper)
 *
 * The binary manifest tolerates the duplicate, but as soon as any patch parses the decoded
 * manifest as XML the strict DOM parser rejects it ("Attribute android:name was already
 * specified for element application"), which aborts the whole patch run on the user's device.
 *
 * Stock lists the app's own ".MyApplication" first and PairIP's wrapper second. Keep the app's
 * own class, which is the one the app was built and tested around, and drop the wrapper. The
 * wrapper only adds the licence check (which "Bypass Play Store check" neutralises anyway) on
 * top of com.jazz.rox.MyApplication, so the app starts exactly its own Application either way.
 * The PairIP name is only removed when the app's own name is present too, so a build that ships
 * a single PairIP name is left untouched.
 */
internal val fixApplicationNamePatch = resourcePatch {
    execute {
        val manifest = get("AndroidManifest.xml", false)
        var text = manifest.readText()

        val ownNames = listOf(
            "android:name=\".MyApplication\"",
            "android:name=\"com.jazz.rox.MyApplication\"",
        )
        if (ownNames.none { text.contains(it) }) return@execute

        val pairipName = "android:name=\"com.pairip.application.Application\""
        if (text.contains(pairipName)) {
            // Drop the duplicate together with any whitespace that precedes it, so the
            // remaining attributes stay well-formed.
            text = text.replace(Regex("""\s*${Regex.escape(pairipName)}"""), "")
            manifest.writeText(text)
        }
    }
}
