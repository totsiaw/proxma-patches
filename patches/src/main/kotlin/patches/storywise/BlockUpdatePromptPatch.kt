package patches.storywise

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode

@Suppress("unused")
val blockUpdatePromptPatch = bytecodePatch(
    name = "Block update prompt",
    description = "Forces Remote Config to report latest_version_name=1.0.3 and " +
        "android_latest_build=34, matching the installed version so the force-update " +
        "dialog never appears.",
) {
    compatibleWith(COMPATIBILITY_STORYWISE)

    execute {
        val rcClass = mutableClassDefBy("LR5/e;")
        val dMethod = rcClass.methods.first {
            it.name == "d" && it.returnType == "Ljava/util/HashMap;"
        }
        val dInsns = dMethod.instructions
        val retIdx = dInsns.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
        check(retIdx >= 0) { "storywise: return-object not found in R5/e.d" }

        dMethod.addInstructions(
            retIdx,
            """
                new-instance v1, Ljava/util/HashMap;
                invoke-direct {v1}, Ljava/util/HashMap;-><init>()V
                const-string v2, "1.0.3"
                sget-object v3, LM4/k;->e:Ljava/nio/charset/Charset;
                invoke-virtual {v2, v3}, Ljava/lang/String;->getBytes(Ljava/nio/charset/Charset;)[B
                move-result-object v2
                const-string v3, "value"
                invoke-virtual {v1, v3, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                const-string v3, "source"
                const-string v4, "remote"
                invoke-virtual {v1, v3, v4}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                const-string v2, "latest_version_name"
                invoke-virtual {v0, v2, v1}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                new-instance v1, Ljava/util/HashMap;
                invoke-direct {v1}, Ljava/util/HashMap;-><init>()V
                const-string v2, "34"
                sget-object v3, LM4/k;->e:Ljava/nio/charset/Charset;
                invoke-virtual {v2, v3}, Ljava/lang/String;->getBytes(Ljava/nio/charset/Charset;)[B
                move-result-object v2
                const-string v3, "value"
                invoke-virtual {v1, v3, v2}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                const-string v3, "source"
                const-string v4, "remote"
                invoke-virtual {v1, v3, v4}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                const-string v2, "android_latest_build"
                invoke-virtual {v0, v2, v1}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            """.trimIndent(),
        )
    }
}
