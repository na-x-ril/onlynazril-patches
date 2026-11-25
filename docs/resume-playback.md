# Resume playback

The `Resume playback` patch keeps the play head of the last twelve videos in memory and seeks back
to it when a video is opened again. It is a bytecode patch plus the
`extensions/tiktok/playback` code.

Version scope: 47.9.1, 47.9.2, 47.9.3, 48.0.1 and 48.0.2.

## Hooks

The patch treats playback as a small state machine, because the progress tick alone cannot see
every position change:

| Hook | Role |
|---|---|
| `onPreparePlay(String)` / `onPreparePlay(String, event)` | the open boundary: reset the per-open flags |
| `onSeekStart(String, int, float)` | a user seek began; progress ticks must not save the intermediate value |
| `onSeekEnd(String, boolean)` / `onRenderSeekComplete(String, boolean)` | the seek ended; read the exact position, save it, or clear it at the start/end |
| `onPlayProgressChange(String, long, long)` | the fallback save while playing |
| `onPausePlay(String)` / `onPausePlay(String, event)` | the exact save before the event handler can move the play head |
| `onPlayCompleted(String)` / `(String, int)` | the video finished: the entry goes |
| `onLoopPlay(String, int)` | the video looped: the entry goes, the reset is not a user seek |
| `onPlayRelease(String)` / `onStopPlay(String)` | the final save when no pause callback arrives |
| `onPlayFailed(...)` / `onRetryOnError(...)` / `onResolutionSwitchComplete(...)` | the surface is recovering; a temporary zero must not erase the point |
| `onPlayPrepared(String)` | the restore seek, issued before the first frame is shown |
| `onRenderFirstFrame(String, event)` / `onRenderFirstFrameFromResume(String)` | the fallback restore seek when prepared could not seek |

`onPlayStop` is deliberately not used as the main save; at that point the controller's position has
already fallen back to the older `lastPosition`. The release/stop hooks above only make a final
save from the last valid position.

## PlayerController callbacks that are not hooked

The class exposes more callbacks than the feature needs. These are deliberately left alone:

| Callback | Why it is not used |
|---|---|
| `onPlayCompletedFirstTime` | a subset of `onPlayCompleted`; clearing there as well would be duplicate work |
| `onPlayStop(...)` (four overloads) | the position has already fallen back to `lastPosition`; `onPlayRelease` / `onStopPlay` are the final-save paths |
| `onPlayPause`, `onPause()`, `onResume()`, `onPlaying`, `onResumePlay` | state notifications; no position change that the progress/pause hooks do not see first |
| `onPlayPrepare` | a prepare alias; `onPreparePlay` is the open boundary |
| `onSpeedChanged` | changes the tick cadence, not the position unit (still milliseconds) |
| `onResolutionSwitchStart` | the completion callback already marks the item suspect |
| `onBuffering`, `onDecoderBuffering` | buffering state, not a play head |
| `onVideoSecondFrame`, `onVideoSizeChanged`, `onFrameAboutToBeRendered`, `onRenderReady`, `onPreRenderReady`, `onPreRenderSessionMissed`, `onLiveRenderFirstFrame` | rendering/metrics, not a position to remember |
| `onPlayerInternalEvent`, `onBufferedPercent`, `onBufferedTimeMs`, `onVideoBitrateChanged`, `onBrightnessChanged`, `onMaskInfoCallback`, `onCrosstalkHappened` | telemetry/player metrics |
| `onPageScrollStateChanged` | pager scroll, not playback |
| `onAttach`, `onDetach`, `onDestroyView`, `onPause()`, `onResume()` | lifecycle without a source id; the per-item callbacks carry the id |
| `getCurrentPosition`, `getPlayerManager`, `getContext`, `getEventType`, `getPageType` | helpers; the extension reads the manager and the controller directly |

## Per-page or global?

The store is **global by `aid`**, not per page. A video is the same content whether it is opened
from the For You feed, search, a profile, or a deep link; giving each surface its own key would
create several resume points for one video and confuse the next open.

The current patch saves and restores by `aid` on every surface. If the feed's autoplay behaviour
should differ, the policy can be split without changing the key. A sane split is:

| Surface | Save | Restore |
|---|---|---|
| detail, search result detail, profile video, `video lainnya` | yes | yes |
| For You / Following autoplay | yes, subject to the thresholds | no by default; a video that merely scrolled past should not jump to an old position. A later `Resume in feed` switch can opt in |
| LIVE, ad, photo mode, story | no | no |

So: no per-page storage, and no separate hook set per page. `PlayerController` is shared; one set
of hooks covers every surface. Only the restore decision needs the surface classification, and that
classification belongs in the extension, not in the map key.

The surface can be read from the controller's real-named `getContext()` activity (the detail pages
report `com.ss.android.ugc.aweme.detail.ui.DetailActivity`, the main feed reports
`com.ss.android.ugc.aweme.main.MainActivity`). `getEventType()` / `getPageType()` are the fallback
when the activity is not one of those.

## Data

`ResumePlayback` keeps one `Entry` per item id:

```text
Entry {
  positionMs, durationMs, lastKnownPositionMs, openedAtMs,
  userSeekStartedAtMs, pendingDurationMs, pendingDurationCount,
  userSeeking, suspect, started
}
```

The map is a `LinkedHashMap` with `accessOrder = true`, capped at twelve entries. `get` and `put`
move an item to the young end, so a thirteenth item evicts the **least recently touched** one, not
the first one ever saved. Nothing is written to disk; killing the process clears every position.

The item id is the callback's `sourceId`. The controller's `mCurrentAid` is only the fallback,
because it can still name the previous cell on a preloaded item.

## What is saved

The same thresholds apply to progress ticks, pauses and seek completion:

- before the start threshold: not saved;
- within the end threshold of the video's end: the entry is removed;
- otherwise the position and duration are stored together.

Progress ticks smaller than 250 ms do not rewrite the entry; pause and user-seek completion always
store the exact value. The duration is accepted at once when it is close to the stored one (within
two seconds). A larger jump is held back until the same new value repeats for three readings: one
neighbouring preloaded item's duration cannot become this item's end-threshold denominator, but a genuine
server-side duration change still lands after a few ticks.

A progress tick below the start threshold removes an older resume point only when this open has
already played past that threshold. Before that, the tick describes the player preparing at the
saved point and must leave the point it is about to restore alone.

A pause or a successful user seek below the start threshold clears the point explicitly: those
callbacks are user actions, not a preparing player.

A reported position of `0` is a real position, not a missing one. The manager's value is kept
unclamped: only a negative value means "position unavailable", and only then does a save fall back
to the last saved position. Clamping it to zero made a seek back to the start and a released player
look the same, so seeking to `00:00` and reopening the video resumed at the old point instead of
clearing it.

## Recovery states

- `onSeekStart` sets `userSeeking`; progress and pause callbacks ignore the item until the seek ends.
- `onSeekEnd`/`onRenderSeekComplete` read the final position. A failed seek leaves the previous
  point untouched.
- `onPlayFailed`, `onRetryOnError` and `onResolutionSwitchComplete` set `suspect`. While suspect, a
  zero or a sub-start position is ignored; the next position past the start threshold clears the
  flag and saves normally.
- `onPlayCompleted` and `onLoopPlay` remove the entry; the loop's reset to zero must not be treated
  as a user seek.
- `onPlayRelease`/`onStopPlay` make one final save from the current position, or from the stored
  position when the player can no longer report one.

## Restore

The seek is issued at `onPlayPrepared`, when the media is ready and the duration is known but no
frame has been rendered. That is what keeps the restore from showing the first frame at zero and
then jumping. A position past the item's duration belongs to another player, so that callback is
skipped instead of seeking the wrong video. The step:

1. read the entry, its stored duration and the player's current position;
2. at prepared, seek even when the player reports the target: that reading still belongs to the old
   session, so it cannot prove the new one will start there. At the first-frame fallback, skip the
   seek when the current position is already within one second, because by then it is the new
   session's position;
3. install the guard **before** calling the manager's `seek(float)`:

```text
seeking = true
seekStartedAt = now
seekTarget = clamp(saved position, start, duration - 1s)
seek(seekTarget * 100 / duration)
```

`onRenderFirstFrame` and `onRenderFirstFrameFromResume` run the same step as a fallback, for a
player that was not ready at prepared time. A repeated callback while the guard is active does not
issue a second seek; only a landed target ends it early.

A prepared seek that the player ignores is retried on the first frame, and again from the progress
timeout, up to three attempts. That keeps a slow restore from leaving the video playing from zero
for the rest of the open. After the third failed attempt the entry keeps its saved point and the
player is left where it is.

While `seeking` is true, progress ticks are ignored until the player reports a position within one
second of the target, or five seconds pass. This is what keeps the first ticks, which still
describe the pre-seek play head, from moving the resume point backwards. A pause during the guard
keeps the target; it does not save the pre-seek position.

## Why the play request is not used

The app's play-request builder computes a value and, on 47.9.x and 48.x, passes it into the play
config (`LX/07zK.LIZLLL` on 47.9.3, `LX/08Jm` on 48.0.2). On 47.7.x and 47.8.3 that value stops at
`isPlayFromStart` and never reaches the player.

Replacing it with the saved millisecond was tried on 47.9.3 and 48.0.1. On 47.9.3 the manager copies
it to `X/05vV.LJIIJJI` and `X/02sw#LJJIIZI` puts it into the engine params as
`init_start_time_ms`, but at runtime playback still started at 0. The same nonzero value also takes
the app's "play from start" view-config path, which brings the cover image flash back on pause.
Since the value does not move the playhead on any supported build, the seek is the only restore
path that works.

## Example

Duration 6:00, two-second end threshold:

| Event | Result |
|---|---|
| tick at 5:00 | entry `(5:00, 6:00)` |
| tick at 6:00 | within 2 s of the end: entry removed |
| tick back at 5:00 | entry `(5:00, 6:00)` again |

There is no "new duration" here: the duration is a property of the video, not of the position. The
position changes; when the entry was cleared at the end, the return creates it again.

## Diagnostics

The About section prints one line from `ResumePlayback.summary()`:

```text
Resume playback: 84 saved, 41 restored, 6 seeked, 7 cleared, 5 evicted · seek ... pos=300000 dur=360000
```

Nothing is logged per tick. Read the line after watching and reopening a video; the counters show
whether the save, the seek and the eviction paths actually ran.
