package app.onlynazril.extension.tiktok;

import android.os.Build;
import android.widget.TextView;

import app.onlynazril.extension.tiktok.settings.PostDateFormat;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The text the elements write, and the one place that decides its shape.
 *
 * Three switches are only readable on screen if the line they produce is predictable: the handle
 * stays on the name, the time keeps TikTok's position and colour, and the region follows the time.
 * A writer composing its own string is how the same region ended up inside a name on one surface
 * and on the time view on another, so every writer comes here instead.
 *
 * The text a view already carries is never trusted on its own. A recycled view still holds the
 * previous video's text when it is handed over, and that text is shaped exactly like this item's, so
 * it is rebuilt from this item's own values whenever it can be recognised as ours, and an age-shaped
 * text that disagrees with this item is replaced.
 */
public final class StampText {
    public static final String SEPARATOR = " \u00b7 ";
    public static final String LEADING_DOT = "\u00b7 ";

    /** Tag holding exactly what this extension last rendered on the view, if anything. */
    public static final int TARGET_TAG = 0x7f0f9999;

    /** Tag holding the time text a render was built from, before the region. */
    public static final int BASE_TAG = 0x7f0f9998;

    /** A trailing "· XX" — a country code, whether TikTok put it there or this extension did. */
    private static final Pattern REGION_SUFFIX = Pattern.compile("\\s*\\u00b7\\s*[A-Z]{2,4}$");

    /** A separator already in front of the time, so the forced dot is added exactly once. */
    private static final Pattern LEADING_SEPARATOR = Pattern.compile("^[\\s\\u00b7]+");

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60L * MINUTE;
    private static final long DAY = 24L * HOUR;
    /** TikTok stores the post time in seconds; anything smaller is not epoch millis. */
    private static final long SECONDS_LIMIT = 100_000_000_000L;

    private StampText() {}

    /** The handle element's own text: the name, and nothing else. */
    public static String handle(String unique) {
        return "@" + unique;
    }

    /**
     * The feed header's time line: a dot in front of the time, then the region when it is on.
     *
     * With no time on the view — TikTok hides it and the post time is off — the region is written on
     * its own, so the region switch never depends on the post-time switch: a country code has a
     * place here whether or not a time does.
     */
    public static String headerTime(String base, String region) {
        if (base.isEmpty()) return region == null ? "" : LEADING_DOT + region;
        String target = LEADING_DOT + base;
        if (region == null || hasRegion(base) || endsWithRegion(base, region)) return target;
        return target + SEPARATOR + region;
    }

    /** A comment's time line: the time as it stands, then the region when it is on. */
    public static String withRegion(String time, String region) {
        if (region == null) return time;
        if (time.isEmpty()) return region;
        if (hasRegion(time) || endsWithRegion(time, region)) return time;
        return time + SEPARATOR + region;
    }

    /** Whether the text already carries this exact region, code or name. */
    private static boolean endsWithRegion(String text, String region) {
        if (region == null || region.isEmpty()) return false;
        return text.endsWith(SEPARATOR + region)
                || text.endsWith(LEADING_DOT + region)
                || text.equals(region);
    }

    /**
     * The time text this extension writes. The epoch is the source: TikTok's own text is used only
     * when the item carries no create time, so the same video reads the same on every surface.
     */
    public static String timeText(
            TextView timeView, String current, long createTime, boolean allowOurs, boolean compact,
            PostDateFormat dateFormat) {
        String fallback = baseText(timeView, current);
        if (!allowOurs) return fallback;
        Locale locale = AppLocale.of(timeView.getContext());
        String expected = relative(createTime, locale, compact, dateFormat);
        return expected.isEmpty() ? fallback : expected;
    }

    /** The text without the region suffix this extension may have appended to it. */
    public static String withoutRegion(String text) {
        return REGION_SUFFIX.matcher(text).replaceFirst("");
    }

    /** The time before this extension added the region, recovered from its own last render. */
    public static String baseText(TextView view, String current) {
        Object rendered = view.getTag(TARGET_TAG);
        Object base = view.getTag(BASE_TAG);
        if (rendered instanceof String && current.equals(rendered) && base instanceof String) {
            return (String) base;
        }
        return withoutRegion(withoutLeadingSeparator(current));
    }

    public static boolean hasRegion(String text) {
        return REGION_SUFFIX.matcher(text).find();
    }

    public static String withoutLeadingSeparator(String text) {
        return LEADING_SEPARATOR.matcher(text).replaceFirst("");
    }

    /**
     * Compact age in the app's language. English and Indonesian follow TikTok's own shapes; every
     * other locale uses the platform CLDR formatter, whose wording may differ from TikTok's.
     */
    private static String relative(long createTime, Locale locale, boolean compact, PostDateFormat dateFormat) {
        if (createTime <= 0) return "";
        long millis = createTime < SECONDS_LIMIT ? createTime * 1000L : createTime;
        long delta = System.currentTimeMillis() - millis;
        if (isIndonesian(locale)) {
            if (delta < MINUTE) {
                long seconds = delta / 1000L;
                return seconds <= 0 ? "baru saja" : unit(seconds, "dtk", "lalu", compact);
            }
            if (delta < HOUR) return unit(delta / MINUTE, "mnt", "lalu", compact);
            if (delta < DAY) return unit(delta / HOUR, "j", "lalu", compact);
            long days = delta / DAY;
            if (days <= 7) return unit(days, "h", "lalu", compact);
            return date(millis, locale, "%1$td-%1$tm", dateFormat);
        }
        if (isEnglish(locale)) {
            if (delta < MINUTE) return "now";
            if (delta < HOUR) return unit(delta / MINUTE, "m", "ago", compact);
            if (delta < DAY) return unit(delta / HOUR, "h", "ago", compact);
            long days = delta / DAY;
            if (days <= 7) return unit(days, "d", "ago", compact);
            return date(millis, locale, "%1$tm-%1$td", dateFormat);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return IcuAgeText.relative(delta, millis, locale, compact, dateFormat);
        }
        return "";
    }

    /**
     * A date without the year while it is this year, and a full localized date otherwise, so an
     * older post cannot read as one from this year. An explicit format overrides both.
     */
    static String date(long millis, Locale locale, String sameYearPattern, PostDateFormat dateFormat) {
        Date value = new Date(millis);
        if (dateFormat.pattern != null) {
            return new SimpleDateFormat(dateFormat.pattern, locale).format(value);
        }
        if (sameYearPattern == null) {
            return DateFormat.getDateInstance(DateFormat.SHORT, locale).format(value);
        }
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
            return String.format(Locale.ROOT, sameYearPattern, millis);
        }
        return DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(value);
    }

    private static String unit(long value, String unit, String suffix, boolean compact) {
        return compact ? value + unit + " " + suffix : value + " " + unit + " " + suffix;
    }

    private static boolean isEnglish(Locale locale) {
        return locale != null && "en".equals(locale.getLanguage());
    }

    private static boolean isIndonesian(Locale locale) {
        String language = locale == null ? "" : locale.getLanguage();
        return "id".equals(language) || "in".equals(language);
    }
}
