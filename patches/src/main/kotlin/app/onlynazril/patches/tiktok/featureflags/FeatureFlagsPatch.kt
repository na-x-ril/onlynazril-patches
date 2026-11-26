package app.onlynazril.patches.tiktok.featureflags

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.findMutableMethodOf
import app.onlynazril.patches.shared.Constants.COMPATIBILITY_TIKTOK
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val FEATURE_FLAGS =
    "Lapp/onlynazril/extension/tiktok/flags/FeatureFlags;"

/**
 * One typed getter of TikTok's AB class. The parameters and the key's position are what identify
 * it; the class name and the method names change every build.
 */
private data class Shape(val returnType: String, val params: List<String>, val keyIndex: Int)

private val BOOLEAN = Shape("Z", listOf("I", "Ljava/lang/String;", "Z", "Z"), 1)
private val INT = Shape("I", listOf("I", "I", "Ljava/lang/String;", "Z"), 2)
private val LONG = Shape("J", listOf("I", "J", "Ljava/lang/String;", "Z"), 2)
private val FLOAT = Shape("F", listOf("I", "Ljava/lang/String;", "Z", "F"), 1)
private val DOUBLE = Shape("D", listOf("D", "I", "Ljava/lang/String;", "Z"), 2)
private val STRING = Shape(
    "Ljava/lang/String;",
    listOf("I", "Ljava/lang/String;", "Ljava/lang/String;", "Z"),
    1,
)

/** All six typed getters together name the AB class; the raw getter is not needed for the hook. */
private val AB_SHAPES = setOf(BOOLEAN, INT, LONG, FLOAT, DOUBLE, STRING)

private data class Hook(val method: String, val descriptor: String) {
    /** A long or a double comes back in a register pair and needs `move-result-wide`. */
    val wide: Boolean get() = descriptor.endsWith("J") || descriptor.endsWith("D")
    val objectResult: Boolean get() = descriptor.endsWith(";")
}

private val HOOKS = listOf(
    BOOLEAN to Hook("overrideBoolean", "(Ljava/lang/String;Z)Z"),
    INT to Hook("overrideInt", "(Ljava/lang/String;I)I"),
    LONG to Hook("overrideLong", "(Ljava/lang/String;J)J"),
    FLOAT to Hook("overrideFloat", "(Ljava/lang/String;F)F"),
    DOUBLE to Hook("overrideDouble", "(Ljava/lang/String;D)D"),
    STRING to Hook(
        "overrideString",
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
    ),
)

/**
 * Overrides TikTok's AB feature flags from the Tweaks screen and records every flag the app reads.
 *
 * The getters are hooked at their `return`, not at their entry: the app's own value is already in
 * a register there, so the override only replaces it and nothing has to be recomputed. A class is
 * the AB class when it carries one getter of every type; the names are never written down.
 *
 * Off by default. Without an override every getter returns the app's own answer.
 */
@Suppress("unused")
val tiktokFeatureFlagsPatch = bytecodePatch(
    name = "Feature flags",
    description = "Override TikTok's AB flags from the Tweaks screen and list every flag the app " +
        "reads. Off by default; a flag without an override keeps the app's own value.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_TIKTOK)

    extendWith("extensions/tiktok.mpe")

    execute {
        var classes = 0
        var hooked = 0
        classDefForEach { classDef ->
            val shapes = classDef.methods.mapNotNull { method ->
                if (AccessFlags.STATIC.isSet(method.accessFlags)) {
                    null
                } else {
                    AB_SHAPES.firstOrNull { shape -> method.matches(shape) }
                }
            }.toSet()
            if (!AB_SHAPES.all { it in shapes }) return@classDefForEach

            HOOKS.forEach { (shape, hook) ->
                val method = classDef.methods.singleOrNull { it.matches(shape) }
                    ?: throw PatchException(
                        "Feature flags: expected one ${shape.returnType} getter on " +
                            "${classDef.type}.",
                    )
                val mutable = mutableClassDefBy(classDef.type).findMutableMethodOf(method)
                hookReturns(mutable, method, shape, hook)
                hooked++
            }
            classes++
        }
        if (classes == 0) {
            throw PatchException("Feature flags: TikTok's AB class was not found.")
        }
    }
}

private fun Method.matches(shape: Shape): Boolean =
    !AccessFlags.STATIC.isSet(accessFlags) &&
        returnType == shape.returnType &&
        parameterTypes.map(CharSequence::toString) == shape.params

private fun hookReturns(mutable: MutableMethod, method: Method, shape: Shape, hook: Hook) {
    val implementation = method.implementation
        ?: throw PatchException("Feature flags: ${method.name} has no body.")
    val key = keyRegister(method, shape)
    val returns = implementation.instructions
        .withIndex()
        .filter { (_, instruction) -> instruction.opcode in RETURNS }
        .toList()
    if (returns.isEmpty()) {
        throw PatchException("Feature flags: ${method.name} has no return.")
    }
    // From the end, so an earlier index stays valid.
    returns.asReversed().forEach { (index, instruction) ->
        val value = (instruction as OneRegisterInstruction).registerA
        mutable.addInstructions(
            index,
            replaceValue(key, value, hook, implementation.registerCount),
        )
    }
}

private val RETURNS = setOf(Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT)

/**
 * The instructions that hand one return value through the override.
 *
 * The plain form passes the key and the value straight to the helper, which is what every build so
 * far allows: the key sits in a low register and the value is the return register. When either is
 * above v15, a four-bit invoke cannot name it, so the two are copied into low scratch registers
 * first; the return instruction is terminal, so those registers are free.
 */
private fun replaceValue(key: Int, value: Int, hook: Hook, registers: Int): String {
    val call = "invoke-static {REGS}, $FEATURE_FLAGS->${hook.method}${hook.descriptor}"
    val read = when {
        hook.wide -> "move-result-wide"
        hook.objectResult -> "move-result-object"
        else -> "move-result"
    }
    if (hook.wide) {
        if (key <= 15 && value + 1 <= 15) {
            return "${call.replace("REGS", "v$key, v$value, v${value + 1}")}\n" +
                "$read v$value"
        }
        // A wide value is copied as an even-aligned pair that does not overlap it.
        val pair = (listOf(0, 2, 4, 6) + 0).firstOrNull {
            it + 1 < registers && it != value && it != value + 1 &&
                it + 1 != value && it + 1 != value + 1
        } ?: throw PatchException("Feature flags: no free pair for ${hook.method}.")
        val keyScratch = (1..7).firstOrNull {
            it != value && it != value + 1 && it != pair && it != pair + 1 && it < registers
        } ?: throw PatchException("Feature flags: no low register for ${hook.method}.")
        return """
            move/from16 v$keyScratch, v$key
            move-wide/from16 v$pair, v$value
            ${call.replace("REGS", "v$keyScratch, v$pair, v${pair + 1}")}
            $read v$pair
            move-wide/from16 v$value, v$pair
        """
    }
    if (key <= 15 && value <= 15) {
        return "${call.replace("REGS", "v$key, v$value")}\n$read v$value"
    }
    // The value is saved before the key is copied, so a key that lands on the value's register
    // cannot be read after being overwritten.
    val scratch = (1..3).firstOrNull { it != key && it < registers }
        ?: throw PatchException("Feature flags: no low register for ${hook.method}.")
    return """
        move/from16 v$scratch, v$value
        move/from16 v0, v$key
        ${call.replace("REGS", "v0, v$scratch")}
        $read v$scratch
        move/from16 v$value, v$scratch
    """
}

/** The key's register: the first parameter sits at registerCount - parameter registers. */
private fun keyRegister(method: Method, shape: Shape): Int {
    fun width(type: String) = if (type == "J" || type == "D") 2 else 1
    val paramRegisters = shape.params.sumOf(::width)
    val firstParam = method.implementation!!.registerCount - paramRegisters
    val offset = shape.params.take(shape.keyIndex).sumOf(::width)
    return firstParam + offset
}
