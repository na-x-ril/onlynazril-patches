package app.onlynazril.patches.tiktok.playback

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * The feed's playback controller: the one object the feed player reports progress through.
 *
 * Real-named and carried by both supported builds (`com.ss.android.ugc.aweme.feed.controller.
 * PlayerController` on 47.7.1 through 47.7.3). It is the class that owns the current item's id
 * (`mCurrentAid`), the last position (`lastPosition`) and the player manager
 * (`getPlayerManager()`), so every resume-playback hook belongs here rather than on the
 * per-cell views whose names are obfuscated and change every build.
 */
internal const val FEED_PLAYER_CONTROLLER =
    "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;"

/** The real-named source id every feed player callback carries first. */
internal const val SOURCE_ID = "Ljava/lang/String;"

/**
 * Any reference type. The event/error/state types in these callbacks are obfuscated and rename
 * between builds, so the parameter position is matched and the type is not written down.
 */
private const val ANY_OBJECT = "L"

/**
 * The playback tick: `onPlayProgressChange(sourceId, positionMs, durationMs)`.
 *
 * The two longs were read off the method body: `lastPosition` is written from the first, and the
 * second is divided by 100 after `long-to-float` to make the progress percentage, with the first
 * divided by it, that makes the first the position and the second the duration, both in
 * milliseconds. The method also reads `mCurrentAid`, but the callback's `sourceId` is the key both
 * save and restore use, because the controller field can lag behind on a preloaded cell.
 *
 * Matched on the real-named class, the exact three-parameter shape and the two field accesses, not
 * on the class's position in the feed stack. The single-float overload (`onPlayProgressChange(F)V`)
 * has no body here, and the name-based match would otherwise be ambiguous with it.
 */
internal val FeedPlayProgressFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onPlayProgressChange",
    returnType = "V",
    parameters = listOf(SOURCE_ID, "J", "J"),
    filters = listOf(
        fieldAccess(
            definingClass = "this",
            name = "lastPosition",
            type = "J",
            opcode = Opcode.IPUT_WIDE,
        ),
        fieldAccess(
            definingClass = "this",
            name = "mCurrentAid",
            type = "Ljava/lang/String;",
            opcode = Opcode.IGET_OBJECT,
        ),
    ),
)

/**
 * The video's first rendered frame: where a saved position can be applied.
 *
 * The player is ready but has not been seen yet, which is the same point TikTok's own detail
 * player uses to seek back (`DetailFragmentPanel#onRenderFirstFrame`). Hooking `onPlayPrepared` is
 * not an option, it has an empty body on both builds.
 *
 * The event type (`X.0BKd` on 47.7.1, `X.0BKZ` on 47.7.2, `X.0BKb` on 47.7.3) is obfuscated and renamed between
 * builds, so the shape is matched instead: the two-parameter overload of a real-named method whose
 * first parameter is the real-named source id. The one-parameter overload is a different callback
 * and takes the event alone.
 */
internal val FeedFirstFrameFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onRenderFirstFrame",
    returnType = "V",
    parameters = listOf(SOURCE_ID, ANY_OBJECT),
)

/**
 * The pause callback: the last moment the playhead is exact.
 *
 * The progress tick trails the player, so a position read here is the closest value to where the
 * user actually left. The stop callback is deliberately not used: at that point the controller's
 * position has already fallen back to `lastPosition`, which is older than the pause value.
 *
 * The player dispatches both pause overloads, the event one first and this one second. Hooking
 * both means the position is saved before the event handler can touch the player, and this
 * one-string overload still covers a build whose dispatcher only calls the legacy method.
 */
internal val FeedPauseFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onPausePlay",
    returnType = "V",
    parameters = listOf(SOURCE_ID),
)

/**
 * The item is being prepared: the open boundary the progress callback cannot provide. The
 * one-string overload is the non-event path; the event overload carries the same source id.
 */
internal val FeedPrepareFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onPreparePlay",
    returnType = "V",
    parameters = listOf(SOURCE_ID),
)

internal val FeedPrepareEventFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onPreparePlay",
    returnType = "V",
    parameters = listOf(SOURCE_ID, ANY_OBJECT),
)

/** The user drags or taps the seek bar: `(sourceId, offset, progress)`. */
internal val FeedSeekStartFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onSeekStart",
    returnType = "V",
    parameters = listOf(SOURCE_ID, "I", "F"),
)

/** The user seek has ended: `(sourceId, success)`. */
internal val FeedSeekEndFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onSeekEnd",
    returnType = "V",
    parameters = listOf(SOURCE_ID, "Z"),
)

/** The seek's rendered frame; a second completion point when the player skips `onSeekEnd`. */
internal val FeedRenderSeekCompleteFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onRenderSeekComplete",
    returnType = "V",
    parameters = listOf(SOURCE_ID, "Z"),
)

/** The video reached its end: `(sourceId)` and the compatibility `(sourceId, reason)` overload. */
internal val FeedPlayCompletedFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onPlayCompleted",
    returnType = "V",
    parameters = listOf(SOURCE_ID),
)

internal val FeedPlayCompletedCountFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onPlayCompleted",
    returnType = "V",
    parameters = listOf(SOURCE_ID, "I"),
)

/** The video looped: the position is about to reset without a user seek. */
internal val FeedLoopPlayFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onLoopPlay",
    returnType = "V",
    parameters = listOf(SOURCE_ID, "I"),
)

/** The player is released, and the stop callback before it: the last chance to save. */
internal val FeedPlayReleaseFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onPlayRelease",
    returnType = "V",
    parameters = listOf(SOURCE_ID),
)

internal val FeedStopPlayFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onStopPlay",
    returnType = "V",
    parameters = listOf(SOURCE_ID),
)

/**
 * A play failure and a retry: both can report a position of zero while the surface recovers.
 * The error type is obfuscated, so the source-id first parameter and the arity are the anchor.
 */
internal val FeedPlayFailedFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onPlayFailed",
    returnType = "V",
    parameters = listOf(SOURCE_ID, ANY_OBJECT, ANY_OBJECT),
)

internal val FeedPlayFailedShortFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onPlayFailed",
    returnType = "V",
    parameters = listOf(SOURCE_ID, ANY_OBJECT),
)

internal val FeedRetryFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onRetryOnError",
    returnType = "V",
    parameters = listOf(SOURCE_ID, ANY_OBJECT),
)

internal val FeedRetryShortFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onRetryOnError",
    returnType = "V",
    parameters = listOf(ANY_OBJECT),
)

/** A resolution switch can re-prepare the player and report a zero position for a moment. */
internal val FeedResolutionSwitchFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onResolutionSwitchComplete",
    returnType = "V",
    parameters = listOf(SOURCE_ID, ANY_OBJECT, "Z", SOURCE_ID),
)

/** A first frame after the app resumes from the background, not the item's first open. */
internal val FeedFirstFrameFromResumeFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onRenderFirstFrameFromResume",
    returnType = "V",
    parameters = listOf(SOURCE_ID),
)

/**
 * The event pause overload: `onPausePlay(sourceId, event)`.
 *
 * The event type is obfuscated and renamed between builds (`X.0AIw` on 47.7.1, `X.0AIt` on
 * 47.7.2, `X.0AIx` on 47.7.3), so only the real-named method, the source-id first parameter and
 * the two-parameter
 * shape are matched. Its body runs before the legacy one-string callback is forwarded, which is
 * where the player's position is still the pause playhead.
 */
internal val FeedPauseEventFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onPausePlay",
    returnType = "V",
    parameters = listOf(SOURCE_ID, ANY_OBJECT),
)

/**
 * The play-request builder: `(Aweme, int, boolean, boolean) -> String`.
 *
 * This is the method that decides where a video starts. Its name is obfuscated and moves between
 * builds (`E` on 47.7.1, `B` on 47.9.3), but the signature is unique in the real-named controller
 * and the `"resumePlay"` log string is inside it on every supported build. In the body, the app
 * asks its progress-continue helper for a position and hands the answer to the play request as
 * `initialStartTimeMs`, so the player starts there without a seek.
 */
internal val FeedPlayRequestFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    returnType = "Ljava/lang/String;",
    parameters = listOf(
        "Lcom/ss/android/ugc/aweme/feed/model/Aweme;",
        "I",
        "Z",
        "Z",
    ),
    strings = listOf(string("resumePlay")),
)
