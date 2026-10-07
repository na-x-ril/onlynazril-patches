package app.onlynazril.patches.tiktok.customfont

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.findMutableMethodOf
import app.onlynazril.patches.shared.Constants.COMPATIBILITY_TIKTOK
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val BRIDGE = "Lapp/onlynazril/extension/tiktok/CustomFontBridge;"

/**
 * Answers the factory every face TikTok draws with.
 *
 * TikTok bundles two families of its own faces (TikTok Display and
 * TikTok Text) and resolves every text style through one static
 * factory: a variable-font request (weight, width, slant, optical
 * size) when the platform can carry one, and the bundled static faces
 * when it cannot. The factory is obfuscated and renames with every
 * build, so it is found by shape: the class that reads the bundled
 * font files and carries the nine-parameter resolver.
 *
 * While the switch in Tweaks says so, the factory answers with the
 * font file the Tweaks screen imported instead, so every
 * surface the factory serves draws in it. The patch does not touch
 * the framework: the patcher only rewrites the app's own classes,
 * so the hook sits at the app's last shared step, not at
 * `Typeface` itself.
 *
 * What it cannot do is keep the weights: the factory's callers ask
 * for faces by weight (bold, medium) and the file carries one face,
 * so a style that needs a heavier face draws in the file's own.
 *
 * The picker's answer rides on the foundation activity's
 * onActivityResult, which the activities the Tweaks screen lives
 * in inherit: every activity result in the app passes through it,
 * and the bridge keeps only the font picker's own request.
 */
@Suppress("unused")
val tiktokCustomFontPatch = bytecodePatch(
    name = "Custom font",
    description = "Answers the factory every face TikTok draws with, so an " +
        "imported font file replaces TikTok's own text on every " +
        "surface. The switch and the picker are in Tweaks; off by " +
        "default, and inert until a font is imported.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_TIKTOK)

    extendWith("extensions/tiktok.mpe")

    execute {
        // The obfuscated class that owns the factory, found by shape
        // rather than by name: it reads the bundled font files and
        // carries the static nine-parameter resolver. Every other
        // Typeface factory in the bundle takes fewer parameters, so
        // the pair of facts is unique.
        val candidates = mutableListOf<ClassDef>()
        classDefForEach { classDef ->
            var readsFonts = false
            for (method in classDef.methods) {
                val implementation = method.implementation ?: continue
                readsFonts = implementation.instructions.any { instruction ->
                    instruction is ReferenceInstruction
                            && instruction.opcode == Opcode.CONST_STRING
                            && (instruction.reference as? StringReference)?.string in FONT_KEYS
                }
                if (readsFonts) break
            }
            val factory = classDef.methods.firstOrNull { method ->
                method.returnType == FACTORY_RETURN
                        && method.parameterTypes == FACTORY_PARAMETERS
                        && method.implementation != null
                        && AccessFlags.STATIC.isSet(method.accessFlags)
            }
            if (readsFonts && factory != null) candidates += classDef
        }
        val holder = candidates.singleOrNull()
            ?: throw PatchException(
                "Custom font: expected one factory reading ${FONT_KEYS} " +
                    "with the nine-parameter resolver, found ${candidates.size}: " +
                    "${candidates.joinToString { it.type }}.",
            )
        val factory = holder.methods.first { method ->
            method.returnType == FACTORY_RETURN
                    && method.parameterTypes == FACTORY_PARAMETERS
        }

        // The factory itself: every surface's face passes through it.
        mutableClassDefBy(holder.type).findMutableMethodOf(factory).answerWithCustomFont()

        // The picker's answer: the foundation activity delivers every
        // activity result through this method, so the picker's file
        // arrives here whatever activity asked for it. The bridge
        // keeps only its own request code; everything else falls
        // through to the app's own handling.
        BaseActivityOnActivityResultFingerprint.method.apply {
            addInstructions(
                0,
                """
                    invoke-static/range {p0 .. p3}, $BRIDGE->onActivityResult(Landroid/app/Activity;IILandroid/content/Intent;)V
                """.trimIndent(),
            )
        }
    }
}

/**
 * Answers the bridge's face at the method's entry, and lets the
 * app's own resolution stand when there is none. The result lands in
 * v0, the first local, so the fall-through path never sees a
 * parameter rewritten: the frame carries twelve locals against the
 * nine parameters, and the check below refuses a frame that does not.
 */
private fun MutableMethod.answerWithCustomFont() {
    val patch = "Custom font"
    requireLocals(patch, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $BRIDGE->typeface()Landroid/graphics/Typeface;
            move-result-object v0
            if-eqz v0, :morphe_customfont_keep
            return-object v0
            :morphe_customfont_keep
            nop
        """.trimIndent(),
    )
}
