package app.onlynazril.patches.tiktok.playback

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.onlynazril.patches.shared.Constants.COMPATIBILITY_TIKTOK

private const val RESUME_PLAYBACK =
    "Lapp/onlynazril/extension/tiktok/playback/ResumePlayback;"

/**
 * Remembers where a video was left and seeks back to it on the next open.
 *
 * Four hooks, two per half of the job:
 *  1. the progress tick stores the position while the video plays, as a fallback;
 *  2. both pause overloads store the exact playhead, before the event handler can move it;
 *  3. the first rendered frame reads the position back and seeks to it, once per item.
 *
 * All are entry hooks, so no register is invented: the progress callback already has the whole
 * argument list at its first instruction, and passing `p0..p5` forwards `this` plus the string and
 * both wide longs in one ranged call. The pause callbacks and the first-frame callback have `this`
 * and the source id as their first two parameters.
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
    }
}
