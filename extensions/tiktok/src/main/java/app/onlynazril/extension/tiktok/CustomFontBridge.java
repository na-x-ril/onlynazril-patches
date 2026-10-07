package app.onlynazril.extension.tiktok;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;

import app.onlynazril.extension.tiktok.internal.Debug;
import app.onlynazril.extension.tiktok.internal.RestartPrompt;
import app.onlynazril.extension.tiktok.settings.CustomFontSettings;

/**
 * The custom-font patch's hooks.
 *
 * The app resolves every face it draws through one static
 * factory, and the patch answers that factory. While the
 * switch says so and an imported font is in use, the answer
 * is that file's face; otherwise the answer is nothing, and
 * the app's own resolution runs as it always did.
 *
 * The factory's callers ask for faces by weight (bold,
 * medium), which one file cannot carry, so a heavier style
 * draws in the file's own face. Nothing here measures text:
 * the app keeps its own metrics, and only the face changes.
 *
 * The picker rides on the foundation activity's
 * onActivityResult, which every activity result in the app
 * passes through. Only the picker's own request code is
 * kept, and only a result that carries a file is imported.
 */
public final class CustomFontBridge {
    private static final int LOG_CAP = 8;

    /** The picker's request code: high enough that the app's own codes never reach it. */
    public static final int REQUEST_PICK_FONT = 0x4F4E54;

    private static int logged;

    private CustomFontBridge() {}

    /** The face every surface draws with, or null to keep the app's own. */
    public static Typeface typeface() {
        try {
            Typeface font = CustomFontSettings.typeface();
            if (font == null) return null;
            report("customfont: font served");
            return font;
        } catch (Throwable t) {
            report("customfont: font lookup failed (" + t + ")");
            return null;
        }
    }

    /**
     * The foundation activity's result delivery, hooked at its entry.
     *
     * A picked font is copied into the app's own files before the
     * prompt asks for the restart the already-laid-out text needs;
     * anything else falls through untouched.
     */
    public static void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        try {
            if (requestCode != REQUEST_PICK_FONT) return;
            if (resultCode != Activity.RESULT_OK || data == null) return;
            Uri uri = data.getData();
            if (uri == null) return;
            boolean imported = CustomFontSettings.importFont(activity, uri);
            report("customfont: import " + (imported ? "succeeded" : "failed"));
            if (imported) RestartPrompt.askNow(activity);
        } catch (Throwable t) {
            report("customfont: import handling failed (" + t + ")");
        }
    }

    private static void report(String message) {
        if (logged++ < LOG_CAP) Debug.print(message);
    }
}
