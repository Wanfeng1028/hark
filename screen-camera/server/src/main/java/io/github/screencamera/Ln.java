package io.github.screencamera;

import android.util.Log;

/** Tiny logger: stdout for the desktop client to read, logcat for `adb logcat -s screen-camera`. */
final class Ln {
    static final String TAG = "screen-camera";
    static volatile boolean verbose = false;

    private Ln() {
    }

    static void d(String msg) {
        if (verbose) {
            System.out.println("[server] D " + msg);
            Log.d(TAG, msg);
        }
    }

    static void i(String msg) {
        System.out.println("[server] I " + msg);
        Log.i(TAG, msg);
    }

    static void w(String msg) {
        System.out.println("[server] W " + msg);
        Log.w(TAG, msg);
    }

    static void e(String msg, Throwable t) {
        System.out.println("[server] E " + msg + (t != null ? ": " + t : ""));
        if (t != null) {
            t.printStackTrace(System.out);
        }
        Log.e(TAG, msg, t);
    }

    /** Machine-readable line consumed by the desktop client: "@key=value". */
    static void event(String key, Object value) {
        System.out.println("@" + key + "=" + value);
        System.out.flush();
    }
}
