package app.onlynazril.extension.tiktokHandle;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.onlynazril.extension.tiktokHandle.internal.Debug;
import app.onlynazril.extension.tiktokHandle.internal.Reflect;
import app.onlynazril.extension.tiktokHandle.settings.HandleSettings;

/**
 * The feed header's post-time view.
 *
 * Two things shape this class. The view is only reachable by id (`tv_post_time`; the header's other
 * ids are obfuscated), so the root view is taken at view creation and the time view resolved once
 * from it. And the values must come from *the item this header is showing*, read off the component
 * itself: reading a "last seen" value at write time gives the neighbouring video's region and age,
 * which is what a shared, deferred value produces once the list starts prefetching.
 *
 * This class writes one view and owns nothing beyond it: the region comes from `RegionSource`, the
 * text from `StampText`. The post time and the region are therefore two switches over one line
 * rather than two writers over one view — a view can only be written once per bind, so both switches
 * have to be read before that write, not after it.
 *
 * Writing here rather than appending to the author name is also what gives the region its colour:
 * the time view already carries TikTok's dimmer colour, while a suffix in the name would take the
 * name's, and a String return from the name getter cannot carry a per-part span.
 */
public final class AuthorInfoBridge {
    private static final String TAG = "tiktokHandle";

    /** `tv_post_time` is the header's own name for it on 47.0.3; the rest are fallbacks. */
    private static final String[] TIME_VIEW_IDS = {
        "tv_post_time", "tv_create_time", "time", "tvTime", "tv_time", "tv_date",
    };

    private static final Map<Object, WeakReference<View>> TIME_VIEWS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static int timeViewId;
    private static boolean idResolved;
    private static int reported;
    private static int reportedSkips;
    private static final int MAX_REPORTS = 12;
    private static final int MAX_SKIPS = 4;

    private AuthorInfoBridge() {}

    /** View creation: resolve the time view once for this component. */
    public static void onHeaderView(Object assem, View root) {
        try {
            if (assem == null || root == null) return;
            int id = timeViewId(root.getContext());
            if (id == 0) return;
            View time = root.findViewById(id);
            if (time == null) return;
            TIME_VIEWS.put(assem, new WeakReference<>(time));
        } catch (Throwable t) {
            Log.w(TAG, "post-time view lookup failed", t);
        }
    }

    /**
     * Per-item renderer, called at each of its returns: the item handed over is the one this
     * header is drawing, and TikTok's own text is already set.
     */
    public static void onHeaderItem(Object assem, Object aweme) {
        try {
            if (assem == null || aweme == null) return;
            WeakReference<View> ref = TIME_VIEWS.get(assem);
            View time = ref == null ? null : ref.get();
            if (time == null) return;
            apply(time, aweme);
        } catch (Throwable t) {
            Log.w(TAG, "post-time write failed", t);
        }
    }

    private static void apply(View view, Object aweme) {
        try {
            if (!(view instanceof TextView)) return;
            if (!HandleSettings.surfaceEnabled(Surfaces.FEED)) return;
            boolean showTime = HandleSettings.timeOn(Surfaces.FEED);
            boolean showRegion = HandleSettings.regionOn(Surfaces.FEED);
            if (!showTime && !showRegion) return;

            TextView timeView = (TextView) view;
            long createTime = createTimeOf(aweme);
            String current = currentText(timeView);
            String base = StampText.timeText(timeView, current, createTime, showTime);
            String region = showRegion ? RegionSource.forAweme(aweme) : null;
            if (base.isEmpty() && region == null) {
                // No time on screen to keep and no region to place: nothing this view can say.
                reportSkip(showTime, showRegion);
                return;
            }

            // The header separates the name from the time with a margin, not a dot, so the dot is
            // put in front of the time here — on TikTok's own text as well, which is otherwise
            // left as TikTok wrote it.
            String target = StampText.headerTime(base, region);
            report(aweme, region, createTime, showTime, showRegion, current, target);
            if (target.equals(current)) return;
            timeView.setText(target);
            timeView.setVisibility(View.VISIBLE);
            // Exactly what was rendered, and the time text it was built from. The base is
            // remembered rather than recovered from the text later: a view carrying a region on its
            // own has no time to rebuild from, and a recycled view is recognised by its own render.
            timeView.setTag(StampText.TARGET_TAG, target);
            timeView.setTag(StampText.BASE_TAG, base);
        } catch (Throwable t) {
            Log.w(TAG, "post-time / region write failed", t);
        }
    }

    /**
     * One line per render, capped: what the item carries next to what is drawn, and the state of the
     * two switches that shaped it. This is the only way to tell a wrong source from a wrong item on
     * a device, because both look the same on screen; and a line that names its own switches lets
     * the post time be checked from the region's line and the other way round.
     */
    private static void report(Object aweme, String region, long createTime, boolean showTime,
            boolean showRegion, String before, String after) {
        if (reported >= MAX_REPORTS) return;
        reported++;
        Object author = Reflect.property(aweme, "getAuthor", "author");
        Debug.print("header item: author=" + Reflect.string(author, "getUniqueId", "uniqueId")
                + " authorRegion=" + Reflect.string(author, "getRegion", "region")
                + " itemRegion=" + Reflect.string(aweme, "getRegion", "region")
                + " used=" + (region == null ? "none" : region)
                + " createTime=" + createTime
                + " (time=" + onOff(showTime) + " region=" + onOff(showRegion) + ")"
                + " before='" + before + "' -> '" + after + "'");
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    /**
     * A render with nothing to write, printed so that a silent header is never ambiguous: no line
     * and an empty report look the same otherwise, and they mean different things.
     */
    private static void reportSkip(boolean showTime, boolean showRegion) {
        if (reportedSkips >= MAX_SKIPS) return;
        reportedSkips++;
        Debug.print("header skip: no time on screen (time=" + onOff(showTime)
                + " region=" + onOff(showRegion) + ")");
    }

    private static long createTimeOf(Object aweme) {
        Object value = Reflect.property(aweme, "getCreateTime", "createTime");
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static int timeViewId(Context context) {
        if (idResolved || context == null) return timeViewId;
        idResolved = true;
        Resources resources = context.getResources();
        for (String name : TIME_VIEW_IDS) {
            int id = resources.getIdentifier(name, "id", context.getPackageName());
            if (id == 0) continue;
            timeViewId = id;
            return timeViewId;
        }
        Log.w(TAG, "no post-time view id resolved — post time and region stay off");
        return 0;
    }

    private static String currentText(TextView view) {
        CharSequence text = view.getText();
        return text == null ? "" : text.toString();
    }
}
