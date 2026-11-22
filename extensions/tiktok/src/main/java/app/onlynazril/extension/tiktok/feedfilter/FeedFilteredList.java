package app.onlynazril.extension.tiktok.feedfilter;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collection;

/**
 * The feed adapter's item list, with the feed's own filters refused at insertion.
 * <p>
 * The feed adapter also fills its list directly, without a setter or insert callback: that is how
 * the last page comes back the moment the app opens, before a fetch has run. Ads were already
 * refused here; a like/view range, a LIVE item and a drama item were not, so a filtered video could
 * still be the first thing on screen. This list closes that path for the feed's adapter only.
 * <p>
 * The detail feed and a profile's list use their own adapter, so the opened video is never refused
 * here.
 */
public final class FeedFilteredList extends ArrayList<Object> {
    /** A single item this soon after the list is made is the preload, not an opened video. */
    private static final long PRELOAD_WINDOW_MS = 1500L;

    private final long createdAt = SystemClock.elapsedRealtime();

    public FeedFilteredList(Collection<?> source) {
        super(source == null ? 0 : source.size());
        if (source == null) return;
        for (Object item : source) {
            if (!FeedFilter.shouldRefuseFeed(item)) super.add(item);
        }
    }

    @Override
    public boolean add(Object item) {
        if (refuseSingle(item)) {
            FeedFilter.logRefusedFeedItem(item);
            return false;
        }
        return super.add(item);
    }

    @Override
    public void add(int index, Object item) {
        if (refuseSingle(item)) {
            FeedFilter.logRefusedFeedItem(item);
            return;
        }
        super.add(index, item);
    }

    /**
     * A single insertion is the video the user opened from a profile, a link or the feed itself.
     * It must stay even when it is outside the range, or there is nothing to open. Only before the
     * first page has loaded does a single item get the counts filter: that is the preloaded first
     * video, which would otherwise be the leak at launch.
     */
    private boolean refuseSingle(Object item) {
        if (FeedFilter.shouldRefuse(item)) return true;
        boolean beforeFirstPage = super.isEmpty() && !FeedFilter.hasPageLoaded()
                && SystemClock.elapsedRealtime() - createdAt < PRELOAD_WINDOW_MS;
        return beforeFirstPage && FeedFilter.shouldRefuseFeed(item);
    }

    @Override
    public boolean addAll(Collection<?> items) {
        if (items == null) return false;
        if (items.size() == 1) {
            Object only = items.iterator().next();
            if (refuseSingle(only)) {
                FeedFilter.logRefusedFeedItem(only);
                return false;
            }
            return super.add(only);
        }
        boolean changed = false;
        for (Object item : items) changed |= add(item);
        return changed;
    }

    @Override
    public boolean addAll(int index, Collection<?> items) {
        if (items == null) return false;
        if (items.size() == 1) {
            Object only = items.iterator().next();
            if (refuseSingle(only)) {
                FeedFilter.logRefusedFeedItem(only);
                return false;
            }
            super.add(index, only);
            return true;
        }
        int at = index;
        boolean changed = false;
        for (Object item : items) {
            if (FeedFilter.shouldRefuseFeed(item)) {
                FeedFilter.logRefusedFeedItem(item);
                continue;
            }
            super.add(at++, item);
            changed = true;
        }
        return changed;
    }

    @Override
    public Object set(int index, Object item) {
        if (FeedFilter.shouldRefuseFeed(item)) {
            FeedFilter.logRefusedFeedItem(item);
            return get(index);
        }
        return super.set(index, item);
    }
}
