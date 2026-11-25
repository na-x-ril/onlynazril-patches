package app.onlynazril.extension.tiktok.playback;

import android.content.Context;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashMap;

import app.onlynazril.extension.tiktok.internal.Debug;
import app.onlynazril.extension.tiktok.internal.Reflect;
import app.onlynazril.extension.tiktok.settings.ResumePlaybackSettings;

/**
 * Remembers where a video was left and seeks back to it when the video is opened again.
 * <p>
 * Two hooks feed this: the feed player's progress tick hands over the position and duration, and
 * the prepared callback is the moment the seek can be applied, before a frame is shown; the first
 * rendered frame stays as the fallback. Pause is hooked on both controller overloads, because the
 * exact play head is read there and one overload may run after the other has changed the player's
 * state. The search result card autoplays through its own player, so its progress is saved through
 * {@code onExternalProgress} under the same item id.
 * <p>
 * The app's play request is not used for the restore. On 47.7.x and 47.8.3 its start-time value
 * never reaches the player; on 47.9.x and 48.x it reaches {@code init_start_time_ms}, but playback
 * still starts at 0 and the nonzero value brings the cover flash back. The seek is the only path
 * that moves the playhead.
 * <p>
 * The map is an in-memory LRU of twelve items: the item touched most recently is the last one
 * dropped, and killing the process clears every position. The duration is stored with the position
 * and is deliberately not replaced by a wildly different reading, which is how a neighbouring
 * preloaded item's duration is kept out of the seek percentage.
 * <p>
 * A restore has a guard of its own. The seek is issued at prepared, and progress ticks stay ignored
 * until the player reports a position at the target (or the guard times out), so the first ticks,
 * which still describe the pre-seek play head, cannot move the resume point backwards.
 * <p>
 * A position below the start threshold is never saved, and a pause there clears an older resume
 * point only after the video has actually played past that threshold in this open. That keeps the
 * pre-seek tick from deleting the point it is about to restore.
 */
public final class ResumePlayback {
    /** The player reports the target within about this much; outside it, the tick is pre-seek. */
    private static final long SEEK_LAND_TOLERANCE_MS = 1000L;
    /** A seek that has not landed by now is treated as failed; the target stays for the next open. */
    private static final long SEEK_TIMEOUT_MS = 5000L;
    /** A user seek that never reports an end must not block this item for the rest of the run. */
    private static final long USER_SEEK_TIMEOUT_MS = 10000L;
    /** The player could not report a position; a real zero is 0, not negative. */
    private static final long POSITION_UNAVAILABLE = -1L;
    /** Progress ticks smaller than this do not rewrite the entry; pause still stores the exact value. */
    private static final long MIN_UPDATE_DELTA_MS = 250L;
    /** A duration that moves this much belongs to another item, not a format change. */
    private static final long DURATION_TOLERANCE_MS = 2000L;
    /** A prepared seek that never lands is retried on the frame and in progress, then abandoned. */
    private static final int MAX_RESTORE_ATTEMPTS = 3;
    private static final int MAX_ENTRIES = 12;

    /**
     * Access-ordered, so {@code get} and {@code put} move an item to the young end. The first key is
     * therefore the least recently touched item, which is the one a thirteenth item evicts.
     */
    private static final LinkedHashMap<String, Entry> ENTRIES =
            new LinkedHashMap<>(16, 0.75f, true);

    private static int saves;
    private static int seeks;
    private static int userSeeks;
    private static int clears;
    private static int evictions;
    private static String previewAid = "";
    private static long previewLoggedAt;
    private static String last = "no position seen this run";

    private ResumePlayback() {}

    private static final class Entry {
        long positionMs;
        long durationMs;
        long lastKnownPositionMs;
        long seekStartedAtMs;
        long seekTargetMs;
        long openedAtMs;
        long userSeekStartedAtMs;
        long pendingDurationMs;
        int pendingDurationCount;
        /** Seek attempts for the current restore; a prepared seek that is ignored gets retried. */
        int restoreAttempts;
        boolean seeking;
        /** A user drag or seek is in flight; progress ticks must not save the intermediate value. */
        boolean userSeeking;
        /** The surface is recovering from a failure or a resolution switch. */
        boolean suspect;
        /** Whether this open has already played past the start threshold. */
        boolean started;
    }

    /**
     * The item is being prepared. This is the open boundary: it resets the per-open flags so a
     * progress tick at the head cannot be mistaken for a user seek to zero. The two prepare
     * overloads can both fire, so a repeat within half a second is ignored.
     */
    public static synchronized void onOpen(Object controller, String sourceId) {
        Context ctx = ResumePlaybackSettings.appContext();
        if (!ResumePlaybackSettings.isEnabled(ctx)) return;

        String aid = aid(controller, sourceId);
        if (aid == null) return;

        Entry entry = ENTRIES.get(aid);
        if (entry == null) return;

        long now = SystemClock.elapsedRealtime();
        if (now - entry.openedAtMs < 500L) return;
        entry.openedAtMs = now;
        entry.started = false;
        entry.userSeeking = false;
        entry.userSeekStartedAtMs = 0L;
        entry.suspect = false;
        // The seek guard is not reset here: a prepare can repeat while the same restore seek is
        // still in flight, and clearing it would issue another seek on every repeat.
        last = "open " + aid;
    }

    /** A user seek started: stop letting progress ticks write the intermediate positions. */
    public static synchronized void onSeekStart(Object controller, String sourceId) {
        Context ctx = ResumePlaybackSettings.appContext();
        if (!ResumePlaybackSettings.isEnabled(ctx)) return;

        String aid = aid(controller, sourceId);
        if (aid == null) return;

        Entry entry = ENTRIES.get(aid);
        if (entry == null) {
            entry = new Entry();
            ENTRIES.put(aid, entry);
        }
        // A restore seek is issued by this patch, not by the user; the first-frame guard owns it.
        if (entry.seeking) return;

        entry.userSeeking = true;
        entry.userSeekStartedAtMs = SystemClock.elapsedRealtime();
        entry.suspect = false;
        entry.lastKnownPositionMs = entry.positionMs;
        last = "user seek start " + aid;
    }

    /**
     * A user seek ended. Unlike the progress tick, this also sees a seek while paused and a seek
     * to zero. A failed seek leaves the previous point untouched.
     */
    public static synchronized void onSeekFinished(
            Object controller, String sourceId, boolean success) {
        Context ctx = ResumePlaybackSettings.appContext();
        if (!ResumePlaybackSettings.isEnabled(ctx)) return;

        String aid = aid(controller, sourceId);
        if (aid == null) return;

        Entry entry = ENTRIES.get(aid);
        if (entry != null) {
            // A restore seek is not a user seek; onProgress owns the landing guard.
            if (entry.seeking) return;
            entry.userSeeking = false;
        }
        if (!success) return;

        long position = currentPosition(controller);
        if (position < 0) {
            if (entry == null) return;
            position = entry.lastKnownPositionMs > 0
                    ? entry.lastKnownPositionMs
                    : entry.positionMs;
            if (position <= 0) return;
        }

        long duration = entry != null && entry.durationMs > 0
                ? entry.durationMs
                : managerDuration(controller);
        if (duration <= 0) return;

        long start = ResumePlaybackSettings.startSeconds(ctx) * 1000L;
        long end = ResumePlaybackSettings.endSeconds(ctx) * 1000L;
        if (position < start) {
            // The seek is explicit, so a point at the head really is the new state. It is not the
            // pre-seek tick a progress callback can produce.
            if (entry != null) remove(aid, "clear seek start " + aid);
            return;
        }
        if (duration - position < end) {
            remove(aid, "clear seek end " + aid);
            return;
        }

        if (entry == null) {
            entry = new Entry();
            ENTRIES.put(aid, entry);
        }
        entry.positionMs = position;
        entry.durationMs = chooseDuration(entry, duration);
        entry.lastKnownPositionMs = position;
        entry.userSeeking = false;
        entry.userSeekStartedAtMs = 0L;
        entry.suspect = false;
        entry.started = true;
        entry.restoreAttempts = 0;
        userSeeks++;
        saves++;
        last = "user seek " + aid + " pos=" + position + " dur=" + entry.durationMs;
        evict();
    }

    /** The video finished normally; a completed video is not a resume point. */
    public static synchronized void onCompleted(Object controller, String sourceId) {
        Context ctx = ResumePlaybackSettings.appContext();
        if (!ResumePlaybackSettings.isEnabled(ctx)) return;

        String aid = aid(controller, sourceId);
        if (aid == null) return;
        if (isSearchContext(controller)) return;
        remove(aid, "clear completed " + aid);
    }

    /** The video looped; the position resets without a user seek, so the old point goes. */
    public static synchronized void onLoop(Object controller, String sourceId) {
        Context ctx = ResumePlaybackSettings.appContext();
        if (!ResumePlaybackSettings.isEnabled(ctx)) return;

        String aid = aid(controller, sourceId);
        if (aid == null) return;
        // A search card autoplay loops while it is on screen; clearing the point there would lose
        // the position the card was about to hand to the detail.
        if (isSearchContext(controller)) return;
        remove(aid, "clear loop " + aid);
    }

    /**
     * The surface is going away without a pause callback: save the last known position once. A
     * missing position falls back to the stored value, never to zero.
     */
    public static synchronized void onFinalSave(Object controller, String sourceId) {
        Context ctx = ResumePlaybackSettings.appContext();
        if (!ResumePlaybackSettings.isEnabled(ctx)) return;

        String aid = aid(controller, sourceId);
        if (aid == null) return;

        Entry entry = ENTRIES.get(aid);
        if (entry == null || entry.seeking) return;
        if (blockedByUserSeek(entry, SystemClock.elapsedRealtime())) return;

        long position = isCurrent(controller, aid)
                ? currentPosition(controller)
                : POSITION_UNAVAILABLE;
        if (position <= 0 && entry.positionMs > 0) {
            // Release can report zero after the player is gone; the stored point is the better
            // answer than deleting it. An explicit seek to zero has already cleared the entry
            // through onSeekFinished.
            position = entry.positionMs;
        }
        if (position <= 0) {
            if (entry.started) remove(aid, "clear final start " + aid);
            return;
        }

        long duration = entry.durationMs > 0 ? entry.durationMs : managerDuration(controller);
        if (duration <= 0) return;

        long start = ResumePlaybackSettings.startSeconds(ctx) * 1000L;
        long end = ResumePlaybackSettings.endSeconds(ctx) * 1000L;
        if (position < start) {
            if (entry.started) remove(aid, "clear final start " + aid);
            return;
        }
        if (duration - position < end) {
            remove(aid, "clear final end " + aid);
            return;
        }

        entry.positionMs = position;
        entry.durationMs = chooseDuration(entry, duration);
        entry.lastKnownPositionMs = position;
        entry.userSeeking = false;
        entry.userSeekStartedAtMs = 0L;
        entry.started = false;
        entry.restoreAttempts = 0;
        saves++;
        last = "final " + aid + " pos=" + position + " dur=" + entry.durationMs;
        evict();
    }

    /** A failed/retried/resolution-switched surface: a temporary zero must not erase the point. */
    public static synchronized void onSuspect(Object controller, String sourceId) {
        setSuspect(controller, sourceId);
    }

    /** Retry without a source id in the callback; the controller still names the current item. */
    public static synchronized void onSuspect(Object controller) {
        setSuspect(controller, null);
    }

    private static void setSuspect(Object controller, String sourceId) {
        Context ctx = ResumePlaybackSettings.appContext();
        if (!ResumePlaybackSettings.isEnabled(ctx)) return;

        String aid = aid(controller, sourceId);
        if (aid == null) return;

        Entry entry = ENTRIES.get(aid);
        if (entry == null) return;
        entry.suspect = true;
        entry.userSeeking = false;
        entry.userSeekStartedAtMs = 0L;
        last = "suspect " + aid;
    }

    /**
     * Saves the position the player reports. Only a position past the start threshold and clear of
     * the end threshold is kept; the tick that arrives before a restore seek has landed is ignored.
     */
    public static synchronized void onProgress(
            Object controller, String sourceId, long positionMs, long durationMs) {
        Context ctx = ResumePlaybackSettings.appContext();
        if (!ResumePlaybackSettings.isEnabled(ctx)) return;
        if (durationMs <= 0 || positionMs < 0) return;

        String aid = aid(controller, sourceId);
        if (aid == null) return;

        long now = SystemClock.elapsedRealtime();
        Entry entry = ENTRIES.get(aid);
        long start = ResumePlaybackSettings.startSeconds(ctx) * 1000L;
        long end = ResumePlaybackSettings.endSeconds(ctx) * 1000L;

        // A restore seek is still in flight. While it is, the player's position is the pre-seek
        // play head and must not replace the target. The guard ends when the target is reached, not
        // only by the timer.
        if (entry != null && entry.seeking) {
            if (now - entry.seekStartedAtMs < SEEK_TIMEOUT_MS) {
                if (!landed(entry, positionMs)) return;
                entry.seeking = false;
                entry.restoreAttempts = 0;
            } else {
                entry.seeking = false;
                // The seek was ignored. Retry while the player is already running, up to the cap,
                // so a slow restore does not leave the video playing from zero for the whole open.
                if (entry.restoreAttempts < MAX_RESTORE_ATTEMPTS
                        && positionMs + SEEK_LAND_TOLERANCE_MS < entry.positionMs) {
                    restore(controller, sourceId, "retry", false);
                } else {
                    entry.restoreAttempts = 0;
                }
                return;
            }
        }
        if (entry != null && blockedByUserSeek(entry, now)) return;
        if (entry != null && entry.suspect) {
            // A failure or a resolution switch can report zero while the surface recovers. A zero
            // in that state is not the user seeking to the start.
            if (positionMs < start) return;
            entry.suspect = false;
        }

        if (positionMs < start) {
            // A real seek back to the start clears the old point. Before the video has played past
            // this threshold the tick is the pre-seek play head, and the entry stays untouched.
            if (entry != null && entry.started) remove(aid, "clear start " + aid);
            return;
        }
        if (durationMs - positionMs < end) {
            remove(aid, "clear end " + aid);
            return;
        }

        boolean created = entry == null;
        if (created) {
            entry = new Entry();
            ENTRIES.put(aid, entry);
        }

        entry.durationMs = chooseDuration(entry, durationMs);
        entry.lastKnownPositionMs = positionMs;
        if (created || Math.abs(positionMs - entry.positionMs) >= MIN_UPDATE_DELTA_MS) {
            entry.positionMs = positionMs;
            saves++;
            last = "save " + aid + " pos=" + positionMs + " dur=" + entry.durationMs;
        }
        entry.started = true;
        evict();
    }

    /** Saves the exact play head when the video pauses or stops. */
    public static synchronized void onPause(Object controller, String sourceId) {
        Context ctx = ResumePlaybackSettings.appContext();
        if (!ResumePlaybackSettings.isEnabled(ctx)) return;

        String aid = aid(controller, sourceId);
        if (aid == null) return;

        Entry entry = ENTRIES.get(aid);
        long position = currentPosition(controller);

        // Pausing during a restore seek keeps the target: the reported position is still the
        // pre-seek one. A landed seek is allowed to continue to the normal save below.
        if (entry != null && entry.seeking) {
            if (position > 0 && landed(entry, position)) {
                entry.seeking = false;
            } else {
                return;
            }
        }
        if (entry != null && blockedByUserSeek(entry, SystemClock.elapsedRealtime())) return;

        if (position < 0) {
            if (entry == null || entry.positionMs <= 0) return;
            position = entry.positionMs;
        }

        long managerDuration = managerDuration(controller);
        long duration = entry != null && entry.durationMs > 0
                ? entry.durationMs
                : managerDuration;
        if (duration <= 0) return;
        // The stored duration can be stale or too short after a format change. In that one case the
        // manager's reading is the only usable answer; otherwise the stored value wins because a
        // transition can leave the manager describing the neighbouring item.
        if (managerDuration > 0 && position > duration + DURATION_TOLERANCE_MS) {
            duration = managerDuration;
        }

        long start = ResumePlaybackSettings.startSeconds(ctx) * 1000L;
        long end = ResumePlaybackSettings.endSeconds(ctx) * 1000L;
        if (entry != null && entry.suspect) {
            if (position < start) return;
            entry.suspect = false;
        }
        if (position < start) {
            if (entry != null && entry.started) remove(aid, "clear start " + aid);
            return;
        }
        if (duration - position < end) {
            remove(aid, "clear end " + aid);
            return;
        }

        if (entry == null) {
            entry = new Entry();
            ENTRIES.put(aid, entry);
        }
        entry.positionMs = position;
        entry.durationMs = chooseDuration(entry, duration);
        entry.lastKnownPositionMs = position;
        entry.userSeeking = false;
        entry.userSeekStartedAtMs = 0L;
        entry.started = false;
        saves++;
        last = "pause " + aid + " pos=" + position + " dur=" + entry.durationMs;
        evict();
    }

    /** Whether this controller belongs to the search surface, which keeps its own handoff. */
    private static boolean isSearchContext(Object controller) {
        String event = Reflect.string(controller, "getEventType", "mEventType");
        return event != null && event.contains("search");
    }

    /**
     * Progress from a surface that is not the feed controller, such as the search result card's
     * autoplay. The source id is the item id, the same key the feed uses, so opening the item
     * later finds this point.
     */
    public static synchronized void onExternalProgress(
            String sourceId, long positionMs, long durationMs) {
        if (!ResumePlaybackSettings.isEnabled()) return;

        long start = ResumePlaybackSettings.startSeconds(ctx()) * 1000L;
        long end = ResumePlaybackSettings.endSeconds(ctx()) * 1000L;
        long now = SystemClock.elapsedRealtime();
        boolean valid = sourceId != null && !sourceId.isEmpty()
                && durationMs > 0 && positionMs >= 0;
        boolean keep = valid && positionMs >= start && durationMs - positionMs >= end;
        String reason = !valid
                ? (durationMs <= 0 ? "no-duration" : "no-position")
                : (keep ? "saved" : (positionMs < start ? "below-start" : "near-end"));
        // Log before any guard: a preview whose player reports no duration is otherwise invisible,
        // and the values are what make the handoff problem readable.
        String id = sourceId == null ? "" : sourceId;
        if (Debug.verbose() && (!id.equals(previewAid) || now - previewLoggedAt >= 3000L)) {
            previewAid = id;
            previewLoggedAt = now;
            Debug.print("resume: preview aid=" + sourceId + " pos=" + positionMs + "ms dur="
                    + durationMs + "ms start=" + start + "ms end=" + end + "ms " + reason);
        }
        if (!keep) return;

        Entry entry = ENTRIES.get(sourceId);
        if (entry == null) {
            entry = new Entry();
            ENTRIES.put(sourceId, entry);
        }
        entry.durationMs = chooseDuration(entry, durationMs);
        entry.lastKnownPositionMs = positionMs;
        if (Math.abs(positionMs - entry.positionMs) >= MIN_UPDATE_DELTA_MS) {
            entry.positionMs = positionMs;
            saves++;
            last = "preview " + sourceId + " pos=" + positionMs;
        }
        entry.started = true;
        evict();
    }

    /** The process context for a save that arrives on a surface without a controller. */
    private static Context ctx() {
        return ResumePlaybackSettings.appContext();
    }

    /**
     * Applies the saved position once the media is prepared, before any frame is rendered.
     * <p>
     * This is the earliest point the duration is known. A seek here moves the player to the saved
     * point before it shows the frame at zero, so the restore is not a visible jump. The seek takes
     * a percentage of the duration, which is what the feed's own seekbar passes: the manager's
     * {@code seek(float)} is the real-named method behind it. The guard is installed before the
     * call, so a tick racing the seek cannot overwrite the target.
     * <p>
     * The first frame remains as the fallback for a player that was not ready here.
     */
    private static int restoreLogs = 20;

    public static synchronized void onPrepared(Object controller, String sourceId) {
        restore(controller, sourceId, "prepared", true);
    }

    /** The fallback restore point: the prepared seek could not run, so seek once on the frame. */
    public static synchronized void onFirstFrame(Object controller, String sourceId) {
        restore(controller, sourceId, "first frame", false);
    }

    private static void restore(
            Object controller, String sourceId, String phase, boolean beforeStart) {
        Context ctx = ResumePlaybackSettings.appContext();
        if (restoreLogs-- > 0) {
            Debug.print("resume: " + phase + " on=" + ResumePlaybackSettings.isEnabled(ctx)
                    + " id=" + sourceId);
        }
        if (!ResumePlaybackSettings.isEnabled(ctx)) return;

        String aid = aid(controller, sourceId);
        if (aid == null) return;

        Entry entry = ENTRIES.get(aid);
        if (entry == null || entry.positionMs <= 0) return;
        if (entry.userSeeking) return;
        if (entry.restoreAttempts >= MAX_RESTORE_ATTEMPTS) return;
        entry.started = false;
        entry.suspect = false;

        long current = currentPosition(controller);
        // A repeated callback while a seek is still in flight must not issue a second seek; only a
        // landed target ends the guard early.
        if (entry.seeking) {
            if (current > 0 && landed(entry, current)) {
                entry.seeking = false;
                entry.restoreAttempts = 0;
            } else if (!beforeStart && entry.restoreAttempts < MAX_RESTORE_ATTEMPTS) {
                // The prepared seek was ignored. The frame is the first point the surface is
                // ready, so retry there instead of waiting for the guard to expire.
                entry.seeking = false;
            } else {
                return;
            }
        }

        long duration = entry.durationMs > 0 ? entry.durationMs : managerDuration(controller);
        if (duration <= 0) return;

        long start = ResumePlaybackSettings.startSeconds(ctx) * 1000L;
        long end = ResumePlaybackSettings.endSeconds(ctx) * 1000L;
        if (entry.positionMs < start || duration - entry.positionMs < end) {
            remove(aid, "clear thresholds " + aid);
            return;
        }

        // A player that is still on another item can report a position past this item's duration.
        // Seeking that player would jump the wrong video, so leave the point for the next callback.
        if (current > duration + DURATION_TOLERANCE_MS) {
            Debug.print("resume: " + phase + " foreign player current=" + current
                    + "ms dur=" + duration + "ms id=" + aid);
            return;
        }

        // Before the player starts, the position it reports still belongs to the old session, so it
        // cannot prove the new one will start at the target. A seek to the same point is harmless,
        // and it is the only way to keep the frame at zero from being rendered. On the first frame
        // the position is the new session's, so a landed prepared seek is left alone here.
        if (current > 0
                && Math.abs(current - entry.positionMs) < SEEK_LAND_TOLERANCE_MS) {
            entry.restoreAttempts = 0;
            Debug.print("resume: " + phase + " already at " + current + "ms id=" + aid);
            return;
        }

        // Never seek into the last second; the end threshold already keeps the saved point away
        // from it, this is only a guard against a shortened duration.
        long target = Math.max(start, Math.min(entry.positionMs, duration - 1000L));
        Object manager = Reflect.property(controller, "getPlayerManager", "mPlayerManager");
        if (manager == null) return;

        entry.seekStartedAtMs = SystemClock.elapsedRealtime();
        entry.seekTargetMs = target;
        entry.seeking = true;
        entry.restoreAttempts++;
        try {
            Debug.print("resume: seek(" + phase + ") id=" + aid + " target=" + target
                    + "ms current=" + current + "ms dur=" + duration + "ms");
            Method seek = manager.getClass().getMethod("seek", float.class);
            float percent = Math.max(0f, Math.min(100f, target * 100f / duration));
            seek.invoke(manager, percent);
            seeks++;
            last = "seek(" + phase + ") " + aid + " pos=" + target + " dur=" + duration;
        } catch (Throwable ignored) {
            entry.seeking = false;
        }
    }

    /** The screen's one line: what the feature has done this run, without a per-tick log. */
    public static synchronized String summary() {
        return saves + " saved, " + seeks + " restored, " + userSeeks + " seeked, "
                + clears + " cleared, " + evictions + " evicted \u00b7 " + last;
    }

    private static boolean landed(Entry entry, long position) {
        return Math.abs(position - entry.seekTargetMs) <= SEEK_LAND_TOLERANCE_MS;
    }

    /**
     * Whether an in-flight user seek still blocks saving. Without an end report it is cleared
     * after the user-seek timeout, so one stuck seek cannot block the item for the whole run.
     */
    private static boolean blockedByUserSeek(Entry entry, long now) {
        if (!entry.userSeeking) return false;
        if (now - entry.userSeekStartedAtMs < USER_SEEK_TIMEOUT_MS) return true;
        entry.userSeeking = false;
        entry.userSeekStartedAtMs = 0L;
        return false;
    }

    /**
     * A duration close to the stored one is accepted at once; a jump belongs to another item
     * (preload or transition) until the same new value repeats for three readings. That lets a
     * genuine server-side duration change in, without letting one neighbour's duration through.
     * A duration that is still unknown takes the first reading.
     */
    private static long chooseDuration(Entry entry, long next) {
        if (next <= 0) return entry.durationMs;
        if (entry.durationMs <= 0) {
            entry.durationMs = next;
            entry.pendingDurationMs = 0L;
            entry.pendingDurationCount = 0;
            return next;
        }
        if (Math.abs(next - entry.durationMs) <= DURATION_TOLERANCE_MS) {
            entry.durationMs = next;
            entry.pendingDurationMs = 0L;
            entry.pendingDurationCount = 0;
            return next;
        }

        if (next == entry.pendingDurationMs) {
            entry.pendingDurationCount++;
        } else {
            entry.pendingDurationMs = next;
            entry.pendingDurationCount = 1;
        }
        if (entry.pendingDurationCount >= 3) {
            entry.durationMs = next;
            entry.pendingDurationMs = 0L;
            entry.pendingDurationCount = 0;
        }
        return entry.durationMs;
    }

    private static void remove(String aid, String reason) {
        if (ENTRIES.remove(aid) != null) {
            clears++;
            last = reason;
        }
    }

    private static String aid(Object controller, String sourceId) {
        // The callback's source id is the item id the feed prepared; the controller field is only
        // a fallback because it can still name the previous cell on a preloaded one.
        if (sourceId != null && !sourceId.isEmpty()) return sourceId;
        Object value = Reflect.readField(controller, "mCurrentAid");
        return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
    }

    /**
     * Whether the controller still names this item. A released or transitioned player can report
     * the next item's position; that value must not be stored under the previous item's id.
     */
    private static boolean isCurrent(Object controller, String aid) {
        Object value = Reflect.readField(controller, "mCurrentAid");
        return value instanceof String && aid.equals(value);
    }

    /** Drops the least recently touched entry until the twelve newest fit. */
    private static void evict() {
        while (ENTRIES.size() > MAX_ENTRIES) {
            Iterator<String> eldest = ENTRIES.keySet().iterator();
            if (!eldest.hasNext()) return;
            eldest.next();
            eldest.remove();
            evictions++;
        }
    }

    private static long currentPosition(Object controller) {
        Object manager = Reflect.property(controller, "getPlayerManager", "mPlayerManager");
        if (manager == null) return POSITION_UNAVAILABLE;
        Method method = Reflect.method(manager.getClass(), "getCurrentPosition");
        if (method == null) return POSITION_UNAVAILABLE;
        try {
            Object value = method.invoke(manager);
            // Keep the raw value: clamping it to zero made a released player and a real seek to
            // zero indistinguishable, so a pause at the start fell back to the previous position.
            return value instanceof Long ? (Long) value : POSITION_UNAVAILABLE;
        } catch (Throwable ignored) {
            return POSITION_UNAVAILABLE;
        }
    }

    private static long managerDuration(Object controller) {
        Object manager = Reflect.property(controller, "getPlayerManager", "mPlayerManager");
        if (manager == null) return 0L;
        Method method = Reflect.method(manager.getClass(), "getDuration");
        if (method == null) return 0L;
        try {
            Object value = method.invoke(manager);
            return value instanceof Long && (Long) value > 0 ? (Long) value : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }
}
