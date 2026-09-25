package app.onlynazril.extension.tiktokHandle.internal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Process;
import android.util.Log;
import android.view.View;

/**
 * Asks for one restart after the app is patched.
 *
 * A freshly patched install can render a name from values the app cached before this extension was
 * there — a name can come out with the handle twice until the process starts again. The extension
 * cannot clear that cache, so it asks instead, once per install: the app's own `lastUpdateTime` is
 * remembered, and a newer one means the APK changed since the last time it asked.
 *
 * Shown from the first feed header, which is the earliest point where an Activity is in hand and
 * the patch has no separate hook of its own for.
 */
public final class RestartPrompt {
    private static final String TAG = "tiktokHandle";
    private static final String PREFS = "tiktokHandle_prefs";
    private static final String KEY_INSTALL = "restart_prompt_install";

    /** One prompt per process: the pref decides across restarts. */
    private static boolean handled;

    private RestartPrompt() {}

    /** Called where a view is in hand; does nothing when this install has already been asked. */
    public static void maybeShow(View view) {
        try {
            if (view == null || handled) return;
            Context context = view.getContext();
            if (context == null) return;

            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long installed = installedAt(context);
            if (installed <= 0 || prefs.getLong(KEY_INSTALL, 0) >= installed) {
                handled = true;
                return;
            }

            Activity activity = activityOf(context);
            if (activity == null || activity.isFinishing()) return;

            handled = true;
            prefs.edit().putLong(KEY_INSTALL, installed).apply();
            Debug.print("restart prompt: asked, install=" + installed);
            activity.runOnUiThread(() -> show(activity));
        } catch (Throwable t) {
            Log.w(TAG, "restart prompt failed", t);
        }
    }

    private static void show(Activity activity) {
        try {
            new AlertDialog.Builder(activity)
                    .setTitle("Restart TikTok once")
                    .setMessage("This app was just patched. Until it is started again it can draw a"
                            + " name twice — a restart clears that, and nothing else is needed.")
                    .setPositiveButton("Restart now", (dialog, which) -> restart(activity))
                    .setNegativeButton("Later", null)
                    .show();
        } catch (Throwable t) {
            Log.w(TAG, "restart prompt failed", t);
        }
    }

    /** The app closes; the next launch is the restart that was asked for. */
    private static void restart(Activity activity) {
        try {
            activity.finishAffinity();
            Process.killProcess(Process.myPid());
        } catch (Throwable t) {
            Log.w(TAG, "restart failed", t);
        }
    }

    private static long installedAt(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .lastUpdateTime;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The Activity behind a view's context, if the chain leads to one. */
    private static Activity activityOf(Context context) {
        Context current = context;
        for (int i = 0; i < 10 && current instanceof ContextWrapper; i++) {
            if (current instanceof Activity) return (Activity) current;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == null || base == current) break;
            current = base;
        }
        return current instanceof Activity ? (Activity) current : null;
    }
}
