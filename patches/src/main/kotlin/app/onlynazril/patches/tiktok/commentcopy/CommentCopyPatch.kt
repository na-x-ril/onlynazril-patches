package app.onlynazril.patches.tiktok.commentcopy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.argumentRegister
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import app.onlynazril.patches.shared.Constants.COMPATIBILITY_TIKTOK
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val BRIDGE = "Lapp/onlynazril/extension/tiktok/commentcopy/CommentCopyBridge;"
private const val PATCH = "Copy comments without username"

/**
 * Leaves the username out of a copied comment.
 *
 * TikTok puts the commenter's name in front of the text before it copies it, and it does so on two
 * routes. The comment sheet's own Copy builds its `ClipData` through one static builder that takes
 * the name prefix, the text and the text's emoji spans apart; the patch blanks that first argument
 * at the top of the builder, so the emoji encoding is untouched. Every other route (Favorites →
 * Comments, and the reply copy) joins the name and the text itself and hands the single string to
 * a plain-text clipboard helper; the patch finds each such method by the pair of calls it makes
 * (`Comment#getText` and `Comment#getUser`) plus its call to that helper, and replaces the string
 * with the comment's own text before the helper is called.
 *
 * Both hooks ask the same switch in the extension, so the switch is the feature. Nothing is matched
 * by name: the classes are obfuscated and rename with every build, and the anchors are the classes'
 * shapes (see `Fingerprints.kt`).
 */
@Suppress("unused")
val tiktokCommentCopyPatch = bytecodePatch(
    name = PATCH,
    description = "Leaves the username out of a copied comment: the clipboard gets the comment's " +
        "own text. On by default; the switch is in Tweaks.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_TIKTOK)
    extendWith("extensions/tiktok.mpe")

    execute {
        // Route one: the builder the comment sheet's Copy flows through.
        val builders = CommentClipDataBuilderFingerprint.matchAllOrNull().orEmpty()
        if (builders.size > 1) {
            throw PatchException(
                "$PATCH: ${builders.size} comment ClipData builders, so which one the sheet " +
                    "copies through is a guess: ${builders.map { it.originalClassDef.type }}.",
            )
        }
        builders.singleOrNull()?.method?.blankPrefix()

        // Route two: the plain helper, and each comment-copy method that hands it a string.
        val helpers = ClipboardTextHelperFingerprint.matchAllOrNull().orEmpty()
        if (helpers.size > 1) {
            throw PatchException(
                "$PATCH: ${helpers.size} plain clipboard helpers: " +
                    helpers.map { it.originalClassDef.type },
            )
        }

        var patchedCalls = 0
        helpers.singleOrNull()?.let { helperMatch ->
            val helper = HelperSignature(
                definingClass = helperMatch.originalClassDef.type,
                name = helperMatch.originalMethod.name,
                parameters = helperMatch.originalMethod.parameterTypes.map { it.toString() },
                returnType = helperMatch.originalMethod.returnType,
            )
            val commentCopy = Fingerprint(
                filters = listOf(
                    methodCall(
                        definingClass = helper.definingClass,
                        name = helper.name,
                        parameters = helper.parameters,
                        returnType = helper.returnType,
                    ),
                ),
                custom = { method, _ -> method.isCommentCopy(helper) },
            )

            commentCopy.matchAll().forEach { match ->
                val method = match.method
                method.helperCallIndexes(helper).asReversed().forEach { helperCallIndex ->
                    val copiedTextRegister = method.getInstruction<Instruction>(helperCallIndex)
                        .argumentRegister(1)
                        ?: throw PatchException(
                            "$PATCH: the clipboard call in ${match.originalClassDef.type}" +
                                "->${method.name} does not name its text in a register.",
                        )
                    val assignmentIndex =
                        method.copiedTextAssignmentBefore(helperCallIndex, copiedTextRegister)
                            ?: throw PatchException(
                                "$PATCH: ${match.originalClassDef.type}->${method.name} calls the " +
                                    "clipboard with a string no assignment in the method writes.",
                            )
                    val insertIndex = assignmentIndex + 1
                    val commentTextRegister = method.lastCommentTextRegisterBefore(insertIndex)
                        ?: throw PatchException(
                            "$PATCH: ${match.originalClassDef.type}->${method.name} reads the " +
                                "comment text without keeping it in a register.",
                        )
                    method.sanitizeCopiedText(insertIndex, copiedTextRegister, commentTextRegister)
                    patchedCalls++
                }
            }
        }

        if (builders.isEmpty() && patchedCalls == 0) {
            throw PatchException("$PATCH: neither comment copy route was found to hook.")
        }
    }
}

/** The helper's own signature, so a call elsewhere can be recognised as a call to it. */
private data class HelperSignature(
    val definingClass: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
) {
    fun matches(reference: MethodReference): Boolean =
        reference.definingClass == definingClass &&
            reference.name == name &&
            reference.parameterTypes.map { it.toString() } == parameters &&
            reference.returnType == returnType
}

/**
 * A comment-copy method: it reads the comment's text **and** its user, and hands a string to the
 * clipboard helper. The two getters together are what says "this is the comment's own copy", not a
 * name.
 */
private fun Method.isCommentCopy(helper: HelperSignature): Boolean {
    val implementation = implementation ?: return false
    var readsText = false
    var readsUser = false
    var callsHelper = false
    for (instruction in implementation.instructions) {
        val reference = instruction.getReference<MethodReference>() ?: continue
        if (reference.isCommentGetText()) readsText = true
        if (reference.isCommentGetUser()) readsUser = true
        if (helper.matches(reference)) callsHelper = true
    }
    return readsText && readsUser && callsHelper
}

/** Every call to the clipboard helper in this method. */
private fun MutableMethod.helperCallIndexes(helper: HelperSignature): List<Int> =
    implementation!!.instructions.withIndex().mapNotNull { (index, instruction) ->
        if (instruction.getReference<MethodReference>()?.let(helper::matches) == true) index else null
    }

/** The `move-result-object` that wrote [register] just before [index]: the string being copied. */
private fun MutableMethod.copiedTextAssignmentBefore(index: Int, register: Int): Int? =
    implementation!!.instructions.take(index).withIndex().lastOrNull { (_, instruction) ->
        instruction.opcode == Opcode.MOVE_RESULT_OBJECT &&
            (instruction as? OneRegisterInstruction)?.registerA == register
    }?.index

/** The register holding `Comment#getText`'s result immediately before [index]. */
private fun MutableMethod.lastCommentTextRegisterBefore(index: Int): Int? {
    val instructions = implementation!!.instructions.toList()
    val getTextIndex = instructions.take(index).withIndex().lastOrNull { (_, instruction) ->
        instruction.getReference<MethodReference>()?.isCommentGetText() == true
    }?.index ?: return null
    val result = instructions.getOrNull(getTextIndex + 1) ?: return null
    if (result.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    return (result as? OneRegisterInstruction)?.registerA
}

/**
 * Blanks the builder's first argument, the name prefix. At index 0 nothing reads it but the
 * builder's own code that follows, and the text and its emoji spans are left alone.
 */
private fun MutableMethod.blankPrefix() {
    addInstructions(
        0,
        """
            invoke-static/range { p0 .. p0 }, $BRIDGE->copiedPrefix(Ljava/lang/String;)Ljava/lang/String;
            move-result-object p0
        """.trimIndent(),
    )
}

/**
 * Replaces the string about to be copied with the comment's own text, in the register that holds
 * it. Both registers are copied down first when either sits above v15, since a plain invoke can
 * only name four bits; the copies come from the free-register search, which is what keeps a live
 * local from being written over.
 */
private fun MutableMethod.sanitizeCopiedText(insertIndex: Int, copiedText: Int, commentText: Int) {
    val call = "$BRIDGE->sanitizeCopiedCommentText" +
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
    if (copiedText <= 15 && commentText <= 15) {
        addInstructions(
            insertIndex,
            """
                invoke-static {v$copiedText, v$commentText}, $call
                move-result-object v$copiedText
            """.trimIndent(),
        )
        return
    }

    val registers = getFreeRegisterProvider(insertIndex, 2, copiedText, commentText)
    val stagedCopied = registers.getFreeRegister()
    val stagedComment = registers.getFreeRegister()
    if (stagedCopied > 15 || stagedComment > 15) {
        throw PatchException(
            "$PATCH: $definingClass->$name has no four-bit register free to stage the copied " +
                "text in.",
        )
    }
    addInstructions(
        insertIndex,
        """
            move-object/from16 v$stagedCopied, v$copiedText
            move-object/from16 v$stagedComment, v$commentText
            invoke-static {v$stagedCopied, v$stagedComment}, $call
            move-result-object v$stagedCopied
            move-object/from16 v$copiedText, v$stagedCopied
        """.trimIndent(),
    )
}
