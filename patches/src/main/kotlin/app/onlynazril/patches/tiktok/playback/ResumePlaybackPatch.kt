package app.onlynazril.patches.tiktok.playback

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.findMutableMethodOf
import app.morphe.util.implementationOrPatchException
import app.onlynazril.patches.shared.Constants.COMPATIBILITY_TIKTOK
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val RESUME_PLAYBACK =
    "Lapp/onlynazril/extension/tiktok/playback/ResumePlayback;"

private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"

/** The nested observer keeps its search name while the player it points at is obfuscated. */
private const val SEARCH_PLAYER_OBSERVER =
    "Lcom/ss/android/ugc/aweme/search/common/ui/component/SearchPlayerCore\$initLifeCycleObserver\$1;"

/**
 * Remembers where a video was left and seeks back to it on the next open.
 *
 * The hooks cover the position's whole life, not only the progress tick:
 *  0. the play-request builder: the position is handed to the player as `initialStartTimeMs`, so
 *     the video starts there natively instead of being seeked after its first frame;
 *  0b. the search result card's own player: its progress is saved under the same item id, so a
 *     preview that played in the grid becomes a resume point when the item is opened;
 *  1. `onPreparePlay`: the open boundary, so a pre-seek tick is not mistaken for a seek to zero;
 *  2. `onSeekStart` / `onSeekEnd` / `onRenderSeekComplete`: the explicit user seek, including a
 *     seek while the video is paused and a seek to the start;
 *  3. `onPlayProgressChange`: the fallback position while the video plays;
 *  4. both `onPausePlay` overloads: the exact playhead;
 *  5. `onPlayCompleted` / `onLoopPlay`: the video finished or restarted, so the old point goes;
 *  6. `onPlayRelease` / `onStopPlay`: the final save when no pause callback arrives;
 *  7. `onPlayFailed` / `onRetryOnError` / `onResolutionSwitchComplete`: the surface is recovering,
 *     do not let a temporary zero erase the point;
 *  8. `onRenderFirstFrame` and `onRenderFirstFrameFromResume`: the fallback seek, once per open,
 *     for a path whose play request did not take the saved position.
 *
 * All are entry hooks, so no register is invented: the progress callback already has the whole
 * argument list at its first instruction, and passing `p0..p5` forwards `this` plus the string and
 * both wide longs in one ranged call. Every other callback has `this` and the source id as its
 * first two parameters; the seek-end callbacks pass the success flag as the third.
 */
@Suppress("unused")
val tiktokResumePlaybackPatch = bytecodePatch(
    name = "Resume playback",
    description = "Continues a video from where you stopped. The twelve most recently opened " +
        "videos are kept until the app closes; the first and last seconds, set in Tweaks, are " +
        "skipped.",
) {
    compatibleWith(COMPATIBILITY_TIKTOK)

    extendWith("extensions/tiktok.mpe")

    execute {
        // The native resume: the app builds a play request for every video and passes it the
        // position it should start at. Substituting that one long makes the player prepare at the
        // saved point, so no seek is issued after the first frame.
        val playRequest = FeedPlayRequestFingerprint.methodOrNull
            ?: throw PatchException("Resume playback: the play-request builder was not found.")
        val mutableRequest = mutableClassDefBy(playRequest.definingClass)
            .findMutableMethodOf(playRequest)
        // The receiver carries the event type ("search_result" and similar). Remembering it here
        // lets the start-time substitution leave the search surface to the app's own handoff. The
        // receiver is addressed as a range: the inline compiler does not accept a `p` register in
        // this method's wrapper.
        val isStaticMethod = (playRequest.accessFlags and 0x0008) != 0
        val receiver = mutableRequest.implementationOrPatchException("Resume playback").registerCount -
            (playRequest.parameterTypes.size + if (isStaticMethod) 0 else 1)
        mutableRequest.addInstructions(
            0,
            "invoke-static/range {v$receiver .. v$receiver}, " +
                "$RESUME_PLAYBACK->onPlayRequest(Ljava/lang/Object;)V",
        )
        val instructions = mutableRequest.implementationOrPatchException("Resume playback")
            .instructions.toList()
        val helperCalls = instructions.withIndex().filter { (_, instruction) ->
            instruction is ReferenceInstruction &&
                (instruction.reference as? MethodReference)?.let { reference ->
                    reference.returnType == "J" &&
                        reference.parameterTypes.firstOrNull() == AWEME
                } == true
        }
        if (helperCalls.size != 1) {
            throw PatchException(
                "Resume playback: expected one progress-continue call, found ${helperCalls.size}.",
            )
        }
        val callIndex = helperCalls.single().index
        val wideResult = instructions.getOrNull(callIndex + 1)
        if (wideResult?.opcode != Opcode.MOVE_RESULT_WIDE) {
            throw PatchException(
                "Resume playback: the progress-continue call does not return a wide result.",
            )
        }
        // The app casts the long to the millisecond int the play request takes; the int is the
        // value to replace, and an int call keeps the injection inside the 4-bit register list.
        val position = instructions.getOrNull(callIndex + 2)
        if (position?.opcode != Opcode.LONG_TO_INT || position !is OneRegisterInstruction) {
            throw PatchException(
                "Resume playback: the progress-continue result is not cast to a start time.",
            )
        }
        val positionRegister = position.registerA
        // The helper call's first argument is the Aweme. Taking it from the instruction avoids a
        // `p` register in the snippet, which the inline compiler cannot name for this method.
        val awemeRegister = when (val call = instructions[callIndex]) {
            is FiveRegisterInstruction -> call.registerC
            is RegisterRangeInstruction -> call.startRegister
            else -> throw PatchException(
                "Resume playback: the progress-continue call has no readable argument list.",
            )
        }
        if (positionRegister > 15 || awemeRegister > 15) {
            throw PatchException(
                "Resume playback: the start-time call needs v0..v15, got " +
                    "v$awemeRegister and v$positionRegister.",
            )
        }
        mutableRequest.addInstructions(
            callIndex + 3,
            "invoke-static {v$awemeRegister, v$positionRegister}, " +
                "$RESUME_PLAYBACK->preferNative(Ljava/lang/Object;I)I\n" +
                "move-result v$positionRegister",
        )

        // The search result grid autoplays a card through its own player, not PlayerController.
        // Its progress reports the same aid, so saving it here makes the position available when
        // the card is opened. The player class is obfuscated; the nested observer is not, and its
        // only field is that player.
        val observer = classDefByOrNull(SEARCH_PLAYER_OBSERVER)
            ?: throw PatchException("Resume playback: the search player observer was not found.")
        val searchPlayer = observer.fields
            .map { it.type }
            .filter { it.startsWith("L") }
            .mapNotNull { classDefByOrNull(it) }
            .firstOrNull { candidate ->
                candidate.methods.any { method ->
                    method.name == "onPlayProgressChange" &&
                        method.parameterTypes == listOf(SOURCE_ID, "J", "J")
                }
            } ?: throw PatchException("Resume playback: the search player's progress hook was not found.")
        val searchProgress = searchPlayer.methods.first {
            it.name == "onPlayProgressChange" && it.parameterTypes == listOf(SOURCE_ID, "J", "J")
        }
        mutableClassDefBy(searchPlayer).findMutableMethodOf(searchProgress).addInstructions(
            0,
            "invoke-static/range {p1 .. p5}, $RESUME_PLAYBACK->onExternalProgress(" +
                "Ljava/lang/String;JJ)V",
        )

        val progress = FeedPlayProgressFingerprint.methodOrNull
            ?: throw PatchException(
                "Resume playback: the feed player's progress tick was not found.",
            )
        progress.addInstructions(
            0,
            "invoke-static/range {p0 .. p5}, $RESUME_PLAYBACK->onProgress(" +
                "Ljava/lang/Object;Ljava/lang/String;JJ)V",
        )

        val pause = FeedPauseFingerprint.methodOrNull
        val pauseEvent = FeedPauseEventFingerprint.methodOrNull
        if (pause == null && pauseEvent == null) {
            throw PatchException(
                "Resume playback: the feed player's pause callback was not found.",
            )
        }
        // The event overload runs first, while the playhead is still the pause point; the legacy
        // overload stays wired for a build whose dispatcher only calls that one.
        pauseEvent?.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $RESUME_PLAYBACK->onPause(" +
                "Ljava/lang/Object;Ljava/lang/String;)V",
        )
        pause?.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $RESUME_PLAYBACK->onPause(" +
                "Ljava/lang/Object;Ljava/lang/String;)V",
        )

        val firstFrame = FeedFirstFrameFingerprint.methodOrNull
            ?: throw PatchException(
                "Resume playback: the feed player's first-frame callback was not found.",
            )
        firstFrame.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $RESUME_PLAYBACK->onFirstFrame(" +
                "Ljava/lang/Object;Ljava/lang/String;)V",
        )

        // The item is being prepared. This is the open boundary the extension needs: without it a
        // progress tick at 0:01 cannot be told apart from a real user seek to the start.
        val prepare = FeedPrepareEventFingerprint.methodOrNull
            ?: throw PatchException(
                "Resume playback: onPreparePlay(String, event) was not found.",
            )
        prepare.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $RESUME_PLAYBACK->onOpen(" +
                "Ljava/lang/Object;Ljava/lang/String;)V",
        )
        FeedPrepareFingerprint.methodOrNull?.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $RESUME_PLAYBACK->onOpen(" +
                "Ljava/lang/Object;Ljava/lang/String;)V",
        )

        // The explicit user seek. The progress tick alone cannot see a seek while paused, and a
        // seek to zero never produces a tick the old flow could use.
        val seekStart = FeedSeekStartFingerprint.methodOrNull
            ?: throw PatchException("Resume playback: onSeekStart was not found.")
        seekStart.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $RESUME_PLAYBACK->onSeekStart(" +
                "Ljava/lang/Object;Ljava/lang/String;)V",
        )
        val seekEnd = FeedSeekEndFingerprint.methodOrNull
            ?: throw PatchException("Resume playback: onSeekEnd was not found.")
        seekEnd.addInstructions(
            0,
            "invoke-static/range {p0 .. p2}, $RESUME_PLAYBACK->onSeekFinished(" +
                "Ljava/lang/Object;Ljava/lang/String;Z)V",
        )
        FeedRenderSeekCompleteFingerprint.methodOrNull?.addInstructions(
            0,
            "invoke-static/range {p0 .. p2}, $RESUME_PLAYBACK->onSeekFinished(" +
                "Ljava/lang/Object;Ljava/lang/String;Z)V",
        )

        // A finished or looped video must not leave a point at its last second.
        val completed = FeedPlayCompletedFingerprint.methodOrNull
            ?: throw PatchException("Resume playback: onPlayCompleted was not found.")
        completed.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $RESUME_PLAYBACK->onCompleted(" +
                "Ljava/lang/Object;Ljava/lang/String;)V",
        )
        FeedPlayCompletedCountFingerprint.methodOrNull?.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $RESUME_PLAYBACK->onCompleted(" +
                "Ljava/lang/Object;Ljava/lang/String;)V",
        )
        val loop = FeedLoopPlayFingerprint.methodOrNull
            ?: throw PatchException("Resume playback: onLoopPlay was not found.")
        loop.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $RESUME_PLAYBACK->onLoop(" +
                "Ljava/lang/Object;Ljava/lang/String;)V",
        )

        // The last save when the surface goes away without a pause callback. Release is the main
        // path; stop is the older one and is kept as a fallback.
        val release = FeedPlayReleaseFingerprint.methodOrNull
            ?: FeedStopPlayFingerprint.methodOrNull
            ?: throw PatchException("Resume playback: no release or stop callback was found.")
        release.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $RESUME_PLAYBACK->onFinalSave(" +
                "Ljava/lang/Object;Ljava/lang/String;)V",
        )
        FeedPlayReleaseFingerprint.methodOrNull?.takeIf { it !== release }?.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $RESUME_PLAYBACK->onFinalSave(" +
                "Ljava/lang/Object;Ljava/lang/String;)V",
        )
        FeedStopPlayFingerprint.methodOrNull?.takeIf { it !== release }?.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $RESUME_PLAYBACK->onFinalSave(" +
                "Ljava/lang/Object;Ljava/lang/String;)V",
        )

        // A failure, retry or resolution switch can report a temporary zero. Mark the item suspect
        // so the next zero cannot clear a real resume point.
        val failed = FeedPlayFailedFingerprint.methodOrNull
        failed?.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $RESUME_PLAYBACK->onSuspect(" +
                "Ljava/lang/Object;Ljava/lang/String;)V",
        )
        FeedPlayFailedShortFingerprint.methodOrNull?.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $RESUME_PLAYBACK->onSuspect(" +
                "Ljava/lang/Object;Ljava/lang/String;)V",
        )
        FeedRetryFingerprint.methodOrNull?.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $RESUME_PLAYBACK->onSuspect(" +
                "Ljava/lang/Object;Ljava/lang/String;)V",
        )
        FeedRetryShortFingerprint.methodOrNull?.addInstructions(
            0,
            "invoke-static/range {p0 .. p0}, $RESUME_PLAYBACK->onSuspect(Ljava/lang/Object;)V",
        )
        FeedResolutionSwitchFingerprint.methodOrNull?.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $RESUME_PLAYBACK->onSuspect(" +
                "Ljava/lang/Object;Ljava/lang/String;)V",
        )

        // The app resumed from the background; this is the same restore point as the first frame.
        FeedFirstFrameFromResumeFingerprint.methodOrNull?.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $RESUME_PLAYBACK->onFirstFrame(" +
                "Ljava/lang/Object;Ljava/lang/String;)V",
        )
    }
}
