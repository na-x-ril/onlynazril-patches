package app.onlynazril.extension.tiktokHandle.internal;

import android.content.Context;

public final class AppContext {
    private static volatile Context ctx;
    private static boolean announced;

    private AppContext() {}

    public static void set(Context c) {
        ctx = c;
        // The one line that proves the installed APK carries this extension and hooks early enough.
        if (!announced) {
            announced = true;
            Debug.print("extension attached");
        }
    }

    public static Context get() { return ctx; }
}
