package app.onlynazril.extension.tiktok.feedfilter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import app.onlynazril.extension.tiktok.internal.Debug;
import app.onlynazril.extension.tiktok.internal.Reflect;
import app.onlynazril.extension.tiktok.settings.FeedFilterSettings;

/**
 * Drops the items a page should not show, in place, on the list the app is about to use.
 *
 * Four call sites. The feed's own path , the fetch's return and the pager item model's storage ,
 * runs the full filter. The generic `FeedItemList` setter and clone, which are shared with the
 * profile, search and repost lists, run the **ad filter only**: a like-count range there would hide
 * the profile owner's own posts, but an ad is not wanted on any surface.
 *
 * The list is walked with an `Iterator` and items are removed through it. Rebuilding the list would
 * leave the pager holding the old one, and `removeIf` needs Android 7 while the app reaches lower.
 *
 * Every link is optional: the page, its item list and an item's counters can each be absent. An
 * absent value never removes anything , see {@link Counts}.
 *
 * A batch of one is never filtered unless that item is an ad. That is the shape of a deep link or
 * the current video being inserted into the pager; removing its only item leaves the surface with
 * nothing to open.
 *
 * One census line per call (capped in the log, never in the screen) says what was handed over and
 * what each filter did with it. It is the only way to tell "the filter never saw this item" from
 * "it saw it and did not match", which look identical on screen.
 */
public final class FeedFilter {
    /**
     * Log lines are spent on what the filter did, not on the order calls arrive in: a session that
     * spent its whole budget on the cold start's two-item pages had no room left for the drops. A
     * few idle lines are still kept, so "the hook never ran" stays visible.
     */
    private static final int MAX_ACTIONS = 100;
    private static final int MAX_IDLE = 6;
    /** Enough dropped items to name in one line to make the filter's work checkable, not a log. */
    private static final int MAX_REASONS = 6;

    private static int actions;
    private static int idle;

    private FeedFilter() {}

    /**
     * The four entry points, one per hook, differing in what they are handed and in how much of
     * the filter they are allowed to run. The feed's own hooks get the full set; the generic page
     * model shared with profile/search/repost gets the ad filter only.
     */
    public static void filterFetch(Object page) {
        filter(page, "fetch", false);
    }

    public static void filterStore(Object list) {
        filter(list, "store", false);
    }

    public static void filterGenericSet(Object list) {
        filter(list, "generic-set", true);
    }

    public static void filterGenericClone(Object page) {
        filter(page, "generic-clone", true);
    }

    /** Whether the item is an ad under the current switch; used by the storage's list guard. */
    public static boolean isAdItem(Object item) {
        return item != null
                && FeedFilterSettings.isAdsEnabled()
                && new AdsFilter().shouldRemove(item);
    }

    private static int refusedAds;

    /** A storage list refused a direct add, which is a path no insert callback sees. */
    public static void logSkippedAd(Object item) {
        if (refusedAds++ >= MAX_ACTIONS) return;
        Debug.print("feedfilter: storage refused " + reason(new AdsFilter(), item));
    }

    /**
     * Replaces the storage's live list with an {@link AdFreeList} built from it.
     *
     * The storage is a plain `ArrayList` that the ad SDK can add to directly. Wrapping the field
     * makes every path , insert callback, setter, page restore and a bare `add` , pass the same
     * refusal. The storage's own `getList` returns a copy, so the field is found by its declared
     * `ArrayList` type instead.
     */
    public static void wrapStorage(Object store) {
        if (store == null) return;
        // `getList` on the storage returns a copy, so it cannot be used to find the live field.
        // The store classes keep the live item list in an ArrayList field; the instance can be a
        // subclass, so the field is looked up through the whole hierarchy.
        boolean wrapped = false;
        for (Class<?> type = store.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!ArrayList.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(store);
                    if (!(value instanceof List) || value instanceof AdFreeList) continue;
                    field.set(store, new AdFreeList((List<?>) value));
                    wrapped = true;
                    Debug.print("feedfilter: storage list wrapped field="
                            + type.getSimpleName() + "." + field.getName()
                            + " size=" + ((List<?>) value).size());
                } catch (Throwable ignored) {
                    // A field the runtime refuses to open is skipped; the other hooks still run.
                }
            }
        }
        if (!wrapped) {
            Debug.print("feedfilter: storage list not wrapped for " + store.getClass().getName());
        }
    }

    /**
     * Filters the list the feed panel is about to insert. Ads only: this path also carries live and
     * survey cards, and a count range here is not the feed's list. The returned list is a copy, so
     * the caller's immutable singleton is never mutated.
     */
    public static List<?> filterInsertList(List<?> items) {
        if (items == null || items.isEmpty()) return items;
        IFilter ads = FeedFilterSettings.isAdsEnabled() ? new AdsFilter() : null;
        if (ads == null) return items;

        List<Object> kept = null;
        List<String> reasons = null;
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if (item != null && ads.shouldRemove(item)) {
                if (kept == null) {
                    kept = new ArrayList<>(items.size());
                    kept.addAll(items.subList(0, i));
                    reasons = new ArrayList<>(MAX_REASONS);
                }
                if (reasons.size() < MAX_REASONS) reasons.add(reason(ads, item));
            } else if (kept != null) {
                kept.add(item);
            }
        }
        if (kept == null) return items;

        int removed = items.size() - kept.size();
        String line = "insert items=" + items.size()
                + " kept=" + kept.size()
                + " ads=" + removed
                + " dropped=" + reasons;
        FeedFilterStats.record(line, removed);
        Debug.print("feedfilter: " + line);
        return kept;
    }

    private static void filter(Object source, String site, boolean adsOnly) {
        // Every return here is recorded, including the ones that filter nothing. Without that, "the
        // hook never ran" and "it ran and the page was not what we expected" look the same on screen,
        // which is exactly the pair that is hardest to tell apart from a device.
        if (source == null) {
            FeedFilterStats.record(site + " a null argument", 0);
            return;
        }

        // Two kinds of caller land here: the fetch hands over a page, the storage hands over the
        // list itself. Reading `items` off a list is what once made a hook count pages without ever
        // filtering one.
        List<?> items = source instanceof List
                ? (List<?>) source
                : asList(Reflect.property(source, "getItems", "items"));
        if (items == null) {
            FeedFilterStats.record(site + " an argument with no item list", 0);
            return;
        }

        Active active = active(adsOnly);
        if (active.filters.length == 0) {
            FeedFilterStats.record(site + " a list of " + items.size() + ", no filter on", 0);
            return;
        }

        // The page's own ad slots, which the app may merge in after this point rather than in the
        // list. Clearing them here is what keeps a preloaded ad from appearing during a later
        // scroll; the item list alone is not enough when the page carries the ad on the side.
        int preloadCount = -1;
        boolean preloadCleared = false;
        if (!(source instanceof List)) {
            Object preload = Reflect.property(source, "getPreloadAds", "preloadAds");
            if (preload instanceof List) {
                preloadCount = ((List<?>) preload).size();
                if (active.adsFilter != null && preloadCount > 0) {
                    ((List<?>) preload).clear();
                    preloadCleared = true;
                }
            }
        }

        if (items.isEmpty()) {
            FeedFilterStats.record(site + " an empty list", 0);
            return;
        }
        // A single item is the deep-link/current video being inserted; removing it leaves nothing
        // to open. A lone ad is still an ad, so that one goes through the normal filter.
        if (items.size() == 1 && !isAd(active, items.get(0))) {
            FeedFilterStats.record(site + " a single-item batch, kept", 0);
            return;
        }

        int before = items.size();
        int removed = 0;
        int unread = 0;
        int[] hits = new int[active.filters.length];
        List<String> reasons = new ArrayList<>(MAX_REASONS);
        Iterator<?> iterator = items.iterator();
        while (iterator.hasNext()) {
            Object item = iterator.next();
            if (item == null) continue;
            if (active.counts && Reflect.property(item, "getStatistics", "statistics") == null) unread++;
            for (int i = 0; i < active.filters.length; i++) {
                if (active.filters[i].shouldRemove(item)) {
                    hits[i]++;
                    if (reasons.size() < MAX_REASONS) reasons.add(reason(active.filters[i], item));
                    iterator.remove();
                    removed++;
                    break;
                }
            }
        }

        // Recorded on every call, so the settings screen can answer "did it run at all".
        String line = site + " " + describe(
                items, before, removed, active, hits, unread, preloadCount, preloadCleared, reasons);
        FeedFilterStats.record(line, removed);
        if (removed > 0) {
            if (actions++ < MAX_ACTIONS) Debug.print("feedfilter: " + line);
        } else if (idle++ < MAX_IDLE) {
            Debug.print("feedfilter: " + line);
        }
    }

    /**
     * The filters for this page, resolved once: the switches are read here and not per item, and a
     * count filter is only built when its range would hide something. `adsOnly` is the generic page
     * model's mode, shared with lists that are not the feed.
     */
    private static Active active(boolean adsOnly) {
        List<IFilter> filters = new ArrayList<>(3);
        List<String> names = new ArrayList<>(3);
        boolean counts = false;
        IFilter adsFilter = null;

        if (FeedFilterSettings.isAdsEnabled()) {
            adsFilter = new AdsFilter();
            filters.add(adsFilter);
            names.add("ads");
        }
        if (!adsOnly) {
            long[] views = setRange(FeedFilterSettings.minMaxViews());
            if (views != null) {
                filters.add(new ViewCountFilter(views));
                names.add(described("views", views));
                counts = true;
            }
            long[] likes = setRange(FeedFilterSettings.minMaxLikes());
            if (likes != null) {
                filters.add(new LikeCountFilter(likes));
                names.add(described("likes", likes));
                counts = true;
            }
        }
        return new Active(
                filters.toArray(new IFilter[0]),
                names.toArray(new String[0]),
                counts,
                adsFilter);
    }

    /** Whether the one item is an ad, so the single-item guard does not keep a lone ad. */
    private static boolean isAd(Active active, Object item) {
        return active.adsFilter != null && item != null && active.adsFilter.shouldRemove(item);
    }

    /**
     * What a dropped item was, so it can be recognised on screen: the count that failed the bound and
     * the handle that posted it. Without the handle a drop can only be counted, never matched against
     * what the feed is still showing.
     */
    private static String reason(IFilter filter, Object item) {
        String reason = filter.reason(item);
        Object author = Reflect.property(item, "getAuthor", "author");
        if (author == null) return reason;
        String handle = Reflect.string(author, "getUniqueId", "uniqueId");
        return handle == null ? reason : reason + "@" + handle;
    }

    /**
     * A count filter's name with the range it is enforcing, so a drop's reason and the bound that
     * caused it are in the same line. Without the bound, `dropped=[likes=17913]` says an item went
     * but not why , which is the one thing a report has to settle.
     */
    private static String described(String filter, long[] range) {
        return filter + "[" + bound(range[0]) + ".." + bound(range[1]) + "]";
    }

    private static String bound(long value) {
        return value == Long.MAX_VALUE ? "MAX" : Long.toString(value);
    }

    /** null for the whole range, which hides nothing; the filter is not built for it. */
    private static long[] setRange(long[] range) {
        return range[0] == 0 && range[1] == Long.MAX_VALUE ? null : range;
    }

    private static String describe(
            List<?> items,
            int before,
            int removed,
            Active active,
            int[] hits,
            int unread,
            int preload,
            boolean preloadCleared,
            List<String> reasons) {
        StringBuilder line = new StringBuilder("list=")
                .append(Integer.toHexString(System.identityHashCode(items)))
                .append(" items=").append(before)
                .append(" removed=").append(removed);
        for (int i = 0; i < active.filters.length; i++) {
            line.append(' ').append(active.names[i]).append('=').append(hits[i]);
        }
        if (active.counts) line.append(" unreadCounts=").append(unread);
        line.append(" preload=").append(preload);
        if (preloadCleared) line.append(" cleared");
        if (!reasons.isEmpty()) {
            // Only on a call that dropped something: the builder is what a fix has to reach, and the
            // list it hands over is a fresh copy every time, so the frames are the only thing that
            // names it.
            line.append(" by=").append(caller());
            line.append(" dropped=").append(reasons);
        }
        return line.toString();
    }

    /** The frames above the hook, minus the extension's own, so the caller that built the list is named. */
    private static String caller() {
        StackTraceElement[] frames = new Throwable().getStackTrace();
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (StackTraceElement frame : frames) {
            String name = frame.getClassName();
            if (name.startsWith("app.onlynazril.extension") || name.startsWith("java.lang.Thread")) {
                continue;
            }
            if (shown++ >= 3) break;
            if (out.length() > 0) out.append(" <- ");
            out.append(name).append('#').append(frame.getMethodName());
        }
        return out.toString();
    }

    private static List<?> asList(Object value) {
        return value instanceof List ? (List<?>) value : null;
    }

    private static final class Active {
        final IFilter[] filters;
        final String[] names;
        final boolean counts;
        final IFilter adsFilter;

        Active(IFilter[] filters, String[] names, boolean counts, IFilter adsFilter) {
            this.filters = filters;
            this.names = names;
            this.counts = counts;
            this.adsFilter = adsFilter;
        }
    }
}
