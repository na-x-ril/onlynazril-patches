package app.onlynazril.patches.tiktok.playback

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import com.android.tools.smali.dexlib2.Opcode

/**
 * The feed's playback controller: the one object the feed player reports progress through.
 *
 * Real-named and carried by both supported builds (`com.ss.android.ugc.aweme.feed.controller.
 * PlayerController` on 47.7.1 and 47.7.2). It is the class that owns the current item's id
 * (`mCurrentAid`), the last position (`lastPosition`) and the player manager
 * (`getPlayerManager()`), so every resume-playback hook belongs here rather than on the
 * per-cell views whose names are obfuscated and change every build.
 */
internal const val FEED_PLAYER_CONTROLLER =
    "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;"

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
    parameters = listOf("Ljava/lang/String;", "J", "J"),
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
 * The event type (`X.0BKd` on 47.7.1, `X.0BKZ` on 47.7.2) is obfuscated and renamed between
 * builds, so the shape is matched instead: the two-parameter overload of a real-named method whose
 * first parameter is the real-named source id. The one-parameter overload is a different callback
 * and takes the event alone.
 */
internal val FeedFirstFrameFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onRenderFirstFrame",
    returnType = "V",
    custom = { method, _ ->
        method.parameterTypes.size == 2 &&
            method.parameterTypes[0] == "Ljava/lang/String;"
    },
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
    parameters = listOf("Ljava/lang/String;"),
)

/**
 * The event pause overload: `onPausePlay(sourceId, event)`.
 *
 * The event type is obfuscated and renamed between builds (`X.0AIw` on 47.7.1, `X.0AIt` on
 * 47.7.2), so only the real-named method, the source-id first parameter and the two-parameter
 * shape are matched. Its body runs before the legacy one-string callback is forwarded, which is
 * where the player's position is still the pause playhead.
 */
internal val FeedPauseEventFingerprint = Fingerprint(
    definingClass = FEED_PLAYER_CONTROLLER,
    name = "onPausePlay",
    returnType = "V",
    custom = { method, _ ->
        method.parameterTypes.size == 2 &&
            method.parameterTypes[0] == "Ljava/lang/String;"
    },
)
