package app.onlynazril.extension.tiktokHandle.internal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.Button;

import app.onlynazril.extension.tiktokHandle.ui.ActionView;
import app.onlynazril.extension.tiktokHandle.ui.Tokens;

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

    /**
     * The dialog is built dark on purpose, whatever theme the app is running: a platform Material
     * dialog rather than the app's own dialog style, whose shape follows the app's theme and would
     * come out as whatever that theme says. Material's corners are square-ish and its title and
     * message are light on a dark panel, which is the shape this needs. The buttons keep the screen's
     * monochrome instead of the theme's accent.
     */
    private static void show(Activity activity) {
        try {
            Context dark = new ContextThemeWrapper(
                    activity, android.R.style.Theme_Material_Dialog_Alert);
            AlertDialog prompt = new AlertDialog.Builder(dark)
                    .setTitle("Restart TikTok once")
                    .setMessage("This app was just patched. Until it is started again it can draw a"
                            + " name twice — a restart clears that, and nothing else is needed.")
                    .setPositiveButton("Restart now", (dialog, which) -> restart(activity))
                    .setNegativeButton("Later", null)
                    .create();
            prompt.setOnShowListener(shown -> monochrome(prompt));
            prompt.show();
        } catch (Throwable t) {
            Log.w(TAG, "restart prompt failed", t);
        }
    }

    /**
     * The dialog's own buttons are borderless text, which next to the screen's controls reads as a
     * different kind of thing. They wear the same rounded outline instead, in the prompt's own
     * palette, and in the sentence case the rest of the screen uses.
     */
    private static void monochrome(AlertDialog dialog) {
        try {
            Context context = dialog.getContext();
            style(dialog, AlertDialog.BUTTON_POSITIVE, context, Tokens.ACCENT);
            style(dialog, AlertDialog.BUTTON_NEGATIVE, context, Tokens.TEXT_SECONDARY);
        } catch (Throwable ignored) {
        }
    }

    private static void style(AlertDialog dialog, int which, Context context, int color) {
        Button button = dialog.getButton(which);
        if (button == null) return;
        button.setTextColor(color);
        button.setAllCaps(false);
        button.setBackground(ActionView.outline(context));
        button.setPadding(
                Tokens.dp(context, Tokens.SPACE_6),
                Tokens.dp(context, Tokens.SPACE_2),
                Tokens.dp(context, Tokens.SPACE_6),
                Tokens.dp(context, Tokens.SPACE_2));
    }

    /** Restarts now, from any context that leads to an Activity. */
    public static void restartNow(Context context) {
        try {
            Activity activity = activityOf(context);
            if (activity == null) return;
            restart(activity);
        } catch (Throwable t) {
            Log.w(TAG, "restart failed", t);
        }
    }

    /**
     * Restarts the app rather than only closing it: the launcher activity is put in a fresh task and
     * this process is ended, so the app comes back on its own with the state a fresh install left
     * behind cleared.
     *
     * The exit follows the launch request immediately, with nothing in between. That ordering is the
     * whole trick: the request is already with the system when the process goes, so the activity is
     * brought up by a new process instead of this one. Waiting even a moment lets the activity start
     * here, and then ending the process takes the app down with it — the app opens and dies, which is
     * what a delay produced. `makeRestartActivityTask` is what clears the back stack this process
     * was holding.
     */
    private static void restart(Activity activity) {
        Intent launch = activity.getPackageManager()
                .getLaunchIntentForPackage(activity.getPackageName());
        if (launch == null || launch.getComponent() == null) {
            Debug.print("restart: the launcher activity could not be resolved");
            close(activity);
            return;
        }
        try {
            activity.startActivity(Intent.makeRestartActivityTask(launch.getComponent()));
            Debug.print("restart: a fresh task was started, ending this process now");
            activity.finish();
        } catch (Throwable t) {
            Debug.print("restart: the fresh task did not start (" + t + ")");
            close(activity);
            return;
        }
        Runtime.getRuntime().exit(0);
    }

    /** The fallback: close what is open and end the process, without bringing anything back. */
    private static void close(Activity activity) {
        try {
            activity.finishAffinity();
        } catch (Throwable ignored) {
        }
        Debug.print("restart: ending this process");
        Runtime.getRuntime().exit(0);
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
