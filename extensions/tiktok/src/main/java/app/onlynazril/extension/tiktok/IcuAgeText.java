package app.onlynazril.extension.tiktok;

import android.icu.text.DisplayContext;
import android.icu.text.RelativeDateTimeFormatter;
import android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit;
import android.icu.text.RelativeDateTimeFormatter.Direction;
import android.icu.text.RelativeDateTimeFormatter.RelativeUnit;
import android.icu.text.RelativeDateTimeFormatter.Style;
import android.icu.util.ULocale;

import app.onlynazril.extension.tiktok.settings.PostDateFormat;

import java.util.Locale;

/**
 * CLDR age text for locales without a hand-tuned formatter.
 *
 * Loaded only on API 24+, where {@code android.icu} exists. Wording follows CLDR, so it can differ
 * from TikTok's own i18n packs.
 */
final class IcuAgeText {
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60L * MINUTE;
    private static final long DAY = 24L * HOUR;

    private IcuAgeText() {}

    static String relative(long delta, long millis, Locale locale, boolean compact, PostDateFormat dateFormat) {
        RelativeDateTimeFormatter formatter = RelativeDateTimeFormatter.getInstance(
                ULocale.forLocale(locale),
                null,
                Style.NARROW,
                DisplayContext.CAPITALIZATION_NONE);
        if (delta < 1000L) {
            return removeSpace(formatter.format(Direction.PLAIN, AbsoluteUnit.NOW), compact);
        }
        if (delta < MINUTE) {
            return removeSpace(
                    formatter.format(delta / 1000L, Direction.LAST, RelativeUnit.SECONDS), compact);
        }
        if (delta < HOUR) {
            return removeSpace(
                    formatter.format(delta / MINUTE, Direction.LAST, RelativeUnit.MINUTES), compact);
        }
        if (delta < DAY) {
            return removeSpace(
                    formatter.format(delta / HOUR, Direction.LAST, RelativeUnit.HOURS), compact);
        }
        long days = delta / DAY;
        if (days <= 7) {
            return removeSpace(
                    formatter.format(days, Direction.LAST, RelativeUnit.DAYS), compact);
        }
        return StampText.date(millis, locale, null, dateFormat);
    }

    /** Drops the CLDR space between a number and its unit: `2 h` becomes `2h`. */
    private static String removeSpace(String text, boolean compact) {
        if (!compact) return text;
        return text.replaceAll("(?<=\\d)[\\s\\u00A0\\u202F]+", "");
    }
}
