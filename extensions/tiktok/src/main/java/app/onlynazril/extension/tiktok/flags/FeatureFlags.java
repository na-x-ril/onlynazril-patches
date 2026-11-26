package app.onlynazril.extension.tiktok.flags;

import android.content.Context;

import java.util.HashMap;
import java.util.Map;

import app.onlynazril.extension.tiktok.internal.AppContext;
import app.onlynazril.extension.tiktok.internal.Debug;
import app.onlynazril.extension.tiktok.settings.FeatureFlagSettings;

/**
 * AB feature-flag overrides, set in Tweaks and read by every getter of TikTok's AB class.
 * <p>
 * TikTok reads its flags through one class with a getter per type. Each getter is hooked at its
 * {@code return}, so the value the app computed arrives here, the override (if any) replaces it,
 * and the key is recorded for the picker. Without an override the app's own value is returned
 * unchanged, which is the default state: no override is set until one is added in Tweaks.
 * <p>
 * Every read is also recorded, so the picker can list the flags TikTok actually asks for. The
 * recording is a map update; the disk write is throttled inside
 * {@link FeatureFlagSettings#recordSeen}.
 */
public final class FeatureFlags {
    /** One line per key and answer, so a getter read every frame does not flood the log. */
    private static final Map<String, String> LOGGED = new HashMap<>();

    private FeatureFlags() {}

    public static synchronized boolean overrideBoolean(String key, boolean appValue) {
        String override = record("b", key, String.valueOf(appValue));
        Boolean parsed = parseBoolean(override);
        return parsed != null ? parsed : appValue;
    }

    public static synchronized int overrideInt(String key, int appValue) {
        String override = record("i", key, String.valueOf(appValue));
        if (override == null) return appValue;
        try {
            return Integer.parseInt(override.trim());
        } catch (NumberFormatException ignored) {
            return appValue;
        }
    }

    public static synchronized long overrideLong(String key, long appValue) {
        String override = record("j", key, String.valueOf(appValue));
        if (override == null) return appValue;
        try {
            return Long.parseLong(override.trim());
        } catch (NumberFormatException ignored) {
            return appValue;
        }
    }

    public static synchronized float overrideFloat(String key, float appValue) {
        String override = record("f", key, String.valueOf(appValue));
        if (override == null) return appValue;
        try {
            return Float.parseFloat(override.trim());
        } catch (NumberFormatException ignored) {
            return appValue;
        }
    }

    public static synchronized double overrideDouble(String key, double appValue) {
        String override = record("d", key, String.valueOf(appValue));
        if (override == null) return appValue;
        try {
            return Double.parseDouble(override.trim());
        } catch (NumberFormatException ignored) {
            return appValue;
        }
    }

    public static synchronized String overrideString(String key, String appValue) {
        String override = record("s", key, appValue);
        return override != null ? override : appValue;
    }

    /** Records the read and returns the override text, or null when the app's value stands. */
    private static String record(String type, String key, String value) {
        if (key == null || key.isEmpty()) return null;
        Context ctx = AppContext.get();
        if (ctx != null) FeatureFlagSettings.recordSeen(ctx, type, key, value);
        String override = FeatureFlagSettings.override(key);
        if (override != null) {
            String stamp = value + " -> " + override;
            if (!stamp.equals(LOGGED.put(key, stamp))) {
                Debug.print("flags: " + type + " " + key + " = " + stamp);
            }
        }
        return override;
    }

    private static Boolean parseBoolean(String value) {
        if (value == null) return null;
        String text = value.trim().toLowerCase();
        if (text.equals("true") || text.equals("1") || text.equals("on") || text.equals("yes")) {
            return Boolean.TRUE;
        }
        if (text.equals("false") || text.equals("0") || text.equals("off") || text.equals("no")) {
            return Boolean.FALSE;
        }
        return null;
    }
}
