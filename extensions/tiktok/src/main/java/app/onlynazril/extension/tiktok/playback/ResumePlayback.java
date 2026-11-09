package app.onlynazril.extension.tiktok.playback;

import android.content.Context;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashMap;

import app.onlynazril.extension.tiktok.internal.Reflect;
import app.onlynazril.extension.tiktok.settings.ResumePlaybackSettings;

/**
 * Remembers where a video was left and seeks back to it when the video is opened again.
 * <p>
 * Two hooks feed this: the feed player's progress tick hands over the position and duration, and
 * the first rendered frame is the moment the seek can be applied. Pause is hooked on both of the
 * controller's overloads, because the exact play head is read there and one overload may run after
 * the other has already changed the player's state.
 * <p>
 * The position and the duration it belongs to are stored together, per item. The duration is the
 * one the player reported while that item was playing, not a fresh read from the shared player
 * manager: on a transition the manager can still describe the previous or next item, and a wrong
 * duration is a wrong seek percentage. Reads from the manager are only the fallback.
 * <p>
 * The store is an in-memory FIFO of twelve items: enough to scroll back through a session, and
 * gone with the process. Saving a thirteenth item drops the one that was stored first, even if it
 * was watched again in between. Nothing is written to disk, so killing the app clears every
 * position without a shutdown hook that a kill would not run anyway.
 */
public final class ResumePlayback {
    /** A seek needs a moment to be reflected in the player's reported position. */
    private static final long SEEK_GRACE_MS = 2000L;
    private static final int MAX_ENTRIES = 12;
    private static final int MAX_SEEKS = 16;

    /** Insertion-ordered: the first item stored is the first one dropped when the store is full. */
    private static final LinkedHashMap<String, Entry> POSITIONS =
            new LinkedHashMap<>(MAX_ENTRIES, 0.75f, false);
    /** The last seek per item, so a repeated first-frame callback does not seek twice. */
    private static final LinkedHashMap<String, Long> SOUGHT =
            new LinkedHashMap<>(MAX_SEEKS, 0.75f, true);

    private ResumePlayback() {}

    private static final class Entry {
        final long position;
        final long duration;

        Entry(long position, long duration) {
            this.position = position;
            this.duration = duration;
        }
    }

    /**
     * Saves the position the player reports. Only a position past the start threshold and clear of
     * the end threshold is kept; an early tick is ignored rather than clearing the previous value,
     * because the first ticks after opening a video report the pre-seek play head.
     */
    public static synchronized void onProgress(
            Object controller, String sourceId, long positionMs, long durationMs) {
        Context ctx = ResumePlaybackSettings.appContext();
        if (!ResumePlaybackSettings.isEnabled(ctx)) return;
        if (durationMs <= 0 || positionMs < 0) return;

        String aid = aid(controller, sourceId);
        if (aid == null) return;

        // A seek leaves the reported position behind the target for a few ticks; those ticks must
        // not move the resume point backwards.
        Long sought = SOUGHT.get(aid);
        if (sought != null && SystemClock.elapsedRealtime() - sought < SEEK_GRACE_MS) return;

        long start = ResumePlaybackSettings.startSeconds(ctx) * 1000L;
        long end = ResumePlaybackSettings.endSeconds(ctx) * 1000L;
        if (positionMs < start) return;
        if (durationMs - positionMs < end) {
            clear(aid);
            return;
        }

        POSITIONS.put(aid, new Entry(positionMs, durationMs));
        evict();
    }

    /** Saves the exact play head when the video pauses or stops. */
    public static synchronized void onPause(Object controller, String sourceId) {
        Context ctx = ResumePlaybackSettings.appContext();
        if (!ResumePlaybackSettings.isEnabled(ctx)) return;

        String aid = aid(controller, sourceId);
        if (aid == null) return;

        Entry saved = POSITIONS.get(aid);
        long duration = saved != null && saved.duration > 0
                ? saved.duration
                : managerDuration(controller);
        if (duration <= 0) return;

        long position = currentPosition(controller);
        if (position <= 0) {
            // The player may already be released when the callback arrives; the value the tick
            // just stored is then the best remaining answer.
            if (saved == null) return;
            position = saved.position;
        }
        if (position <= 0) return;

        long start = ResumePlaybackSettings.startSeconds(ctx) * 1000L;
        long end = ResumePlaybackSettings.endSeconds(ctx) * 1000L;
        if (position < start) return;
        if (duration - position < end) {
            clear(aid);
            return;
        }

        POSITIONS.put(aid, new Entry(position, duration));
        evict();
    }

    /**
     * Applies the saved position on the video's first frame, once per item.
     * <p>
     * The seek takes a percentage of the duration, which is what the feed's own seekbar passes:
     * the manager's `seek(float)` is the real-named method behind it.
     */
    public static synchronized void onFirstFrame(Object controller, String sourceId) {
        Context ctx = ResumePlaybackSettings.appContext();
        if (!ResumePlaybackSettings.isEnabled(ctx)) return;

        String aid = aid(controller, sourceId);
        if (aid == null) return;
        long now = SystemClock.elapsedRealtime();
        Long lastSeek = SOUGHT.get(aid);
        if (lastSeek != null && now - lastSeek < SEEK_GRACE_MS) return;

        Entry saved = POSITIONS.get(aid);
        if (saved == null || saved.position <= 0) return;

        // The duration saved with this item is the one the player reported for it; a live read can
        // belong to a neighboring item during a transition.
        long duration = saved.duration > 0 ? saved.duration : managerDuration(controller);
        if (duration <= 0) return;

        long start = ResumePlaybackSettings.startSeconds(ctx) * 1000L;
        long end = ResumePlaybackSettings.endSeconds(ctx) * 1000L;
        if (saved.position < start || duration - saved.position < end) return;

        long current = currentPosition(controller);
        if (current > 0 && Math.abs(current - saved.position) < 1000L) {
            markSought(aid, now);
            return;
        }

        // Never seek into the last second; the end threshold already keeps the saved point away
        // from it, this is only a guard against a shortened duration.
        long target = Math.min(saved.position, Math.max(0L, duration - 1000L));

        Object manager = Reflect.property(controller, "getPlayerManager", "mPlayerManager");
        if (manager == null) return;
        try {
            Method seek = manager.getClass().getMethod("seek", float.class);
            float percent = Math.max(0f, Math.min(100f, target * 100f / duration));
            seek.invoke(manager, percent);
            markSought(aid, now);
        } catch (Throwable ignored) {
        }
    }

    private static String aid(Object controller, String sourceId) {
        // The callback's source id is the item id the feed prepared; the controller field is only
        // a fallback because it can still name the previous cell on a preloaded one.
        if (sourceId != null && !sourceId.isEmpty()) return sourceId;
        Object value = Reflect.readField(controller, "mCurrentAid");
        return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
    }

    /** Drops the oldest stored entries until the twelve newest fit. */
    private static void evict() {
        while (POSITIONS.size() > MAX_ENTRIES) {
            Iterator<String> oldest = POSITIONS.keySet().iterator();
            if (!oldest.hasNext()) return;
            String key = oldest.next();
            oldest.remove();
            SOUGHT.remove(key);
        }
    }

    private static void markSought(String aid, long now) {
        SOUGHT.put(aid, now);
        while (SOUGHT.size() > MAX_SEEKS) {
            Iterator<String> oldest = SOUGHT.keySet().iterator();
            if (!oldest.hasNext()) return;
            oldest.next();
            oldest.remove();
        }
    }

    private static long currentPosition(Object controller) {
        Object manager = Reflect.property(controller, "getPlayerManager", "mPlayerManager");
        if (manager == null) return 0L;
        Method method = Reflect.method(manager.getClass(), "getCurrentPosition");
        if (method == null) return 0L;
        try {
            Object value = method.invoke(manager);
            return value instanceof Long ? Math.max(0L, (Long) value) : 0L;
        } catch (Throwable ignored) {
            return 0L;
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

    /** Removes a position the current thresholds no longer keep. */
    private static void clear(String aid) {
        POSITIONS.remove(aid);
        SOUGHT.remove(aid);
    }
}
