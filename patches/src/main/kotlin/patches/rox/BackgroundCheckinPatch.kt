package patches.rox

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

private const val JOB_SERVICE_CLASS = "Lapp/proxma/rox/CheckinJobService;"
private const val JOB_SERVICE = "app.proxma.rox.CheckinJobService"
private const val ALARM_RECEIVER = "app.proxma.rox.CheckinAlarm"
private const val UNLOCK_SERVICE = "app.proxma.rox.UnlockService"
private const val SPECIAL_USE_SUBTYPE =
    "Waits for the user's first unlock of the day, then opens the app's own screen for a moment " +
        "to do the daily check-in the user turned on"
private val WAKE_PERMISSIONS = listOf(
    // Appear on top: what lets ROX open its own screen from the background.
    "android.permission.SYSTEM_ALERT_WINDOW",
    // The foreground service that waits for the first unlock of the day.
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    // Sets the planned alarm again after a reboot (alarms don't survive one).
    "android.permission.RECEIVE_BOOT_COMPLETED",
)

private val checkinJobServiceManifestPatch = resourcePatch {
    // Clears 3.0.14's duplicate <application android:name> before this reads the manifest as XML.
    dependsOn(fixApplicationNamePatch)

    finalize {
        document("AndroidManifest.xml").use { doc ->
            val application = doc.getElementsByTagName("application").item(0) as Element
            // What lets the background run open ROX's own screen, where the check-in works:
            // "Appear on top" once the user turns it on. Nothing opens over the lock screen.
            // ROX 3.0.14 already holds FOREGROUND_SERVICE and RECEIVE_BOOT_COMPLETED; added only if missing.
            val manifest = doc.documentElement
            val permissions = doc.getElementsByTagName("uses-permission")
            for (permission in WAKE_PERMISSIONS) {
                if ((0 until permissions.length).none {
                        (permissions.item(it) as Element).getAttribute("android:name") == permission
                    }
                ) {
                    manifest.insertBefore(
                        doc.createElement("uses-permission").apply {
                            setAttribute("android:name", permission)
                        },
                        application,
                    )
                }
            }
            val services = doc.getElementsByTagName("service")
            if ((0 until services.length).none {
                    (services.item(it) as Element).getAttribute("android:name") == JOB_SERVICE
                }
            ) {
                application.appendChild(
                    doc.createElement("service").apply {
                        setAttribute("android:name", JOB_SERVICE)
                        setAttribute("android:permission", "android.permission.BIND_JOB_SERVICE")
                        setAttribute("android:exported", "false")
                    },
                )
            }
            // Started by the wake-up alarm; waits for the first unlock of the day and checks in then.
            if ((0 until services.length).none {
                    (services.item(it) as Element).getAttribute("android:name") == UNLOCK_SERVICE
                }
            ) {
                application.appendChild(
                    doc.createElement("service").apply {
                        setAttribute("android:name", UNLOCK_SERVICE)
                        setAttribute("android:foregroundServiceType", "specialUse")
                        setAttribute("android:exported", "false")
                        appendChild(
                            doc.createElement("property").apply {
                                setAttribute("android:name", "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE")
                                setAttribute("android:value", SPECIAL_USE_SUBTYPE)
                            },
                        )
                    },
                )
            }
            // The wake-up alarm that starts the check-in on time while Doze holds the job back. It
            // also gets the boot broadcast, to set the alarm again after a reboot.
            val receivers = doc.getElementsByTagName("receiver")
            if ((0 until receivers.length).none {
                    (receivers.item(it) as Element).getAttribute("android:name") == ALARM_RECEIVER
                }
            ) {
                application.appendChild(
                    doc.createElement("receiver").apply {
                        setAttribute("android:name", ALARM_RECEIVER)
                        setAttribute("android:exported", "false")
                        appendChild(
                            doc.createElement("intent-filter").apply {
                                appendChild(
                                    doc.createElement("action").apply {
                                        setAttribute("android:name", "android.intent.action.BOOT_COMPLETED")
                                    },
                                )
                            },
                        )
                    },
                )
            }
        }
    }
}

@Suppress("unused")
val backgroundCheckinPatch = bytecodePatch(
    name = "Auto check-in in the background",
    description = "Does the daily streak check-in without you opening ROX, the first time you unlock your " +
        "phone each day. Just after midnight (Pakistan time) an alarm that also fires while the phone sleeps " +
        "starts a small service with a silent notice (\"ROX will check in when you unlock your phone\"). " +
        "When you next unlock the phone and today's check-in isn't done, ROX opens its own screen, presses " +
        "Check-in and closes it again, usually within a few seconds and after two minutes at the most. On " +
        "Android 11 and later that screen is fully transparent and taps go through it to the app below, but " +
        "the Back button and the keyboard reach ROX until it closes; where Android won't make it transparent, " +
        "ROX shows for that moment. Nothing opens while the phone is locked or the screen is off, " +
        "and it tries once a day; if you switch apps before the check-in is through, it tries again at your " +
        "next unlock, up to three unlocks a day. ROX needs \"Appear on top\" to open itself; while it is off, a " +
        "notification asks you to turn it on. A notification says when it worked, or asks you to tap " +
        "it if it couldn't; the next time you open ROX a short message also says what the last " +
        "background try did. You must be signed in, and ROX must have been opened once after installing. " +
        "Force-stopping ROX stops it until the next time you open the app.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_ROX)
    extendWith("extensions/extension.mpe")
    dependsOn(autoCheckinPatch, checkinJobServiceManifestPatch)

    execute {
        val onCreate = roxMainActivityOnCreateFingerprint.method
        val superCall = onCreate.instructions.toList().indexOfFirst { instruction ->
            // 3.0.13 compiled this as invoke-super; 3.0.14 as invoke-super/range. Accept both.
            if (instruction.opcode != Opcode.INVOKE_SUPER && instruction.opcode != Opcode.INVOKE_SUPER_RANGE) {
                return@indexOfFirst false
            }
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@indexOfFirst false
            reference.name == "onCreate" &&
                reference.parameterTypes.size == 1 &&
                reference.parameterTypes.first().toString() == "Landroid/os/Bundle;"
        }
        if (superCall < 0) {
            throw PatchException("MainActivity.onCreate has no invoke-super onCreate(Bundle) to anchor on")
        }

        // Range form: in 3.0.14 onCreate uses enough registers that p0 is above v15, which a
        // plain invoke-static {p0} cannot encode.
        onCreate.addInstructions(
            superCall + 1,
            "invoke-static/range {p0 .. p0}, $JOB_SERVICE_CLASS->onActivityCreate(Landroid/app/Activity;)V",
        )
    }
}
