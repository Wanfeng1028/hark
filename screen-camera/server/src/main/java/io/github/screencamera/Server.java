package io.github.screencamera;

import android.os.Build;
import android.os.Looper;

/**
 * Entry point, started from the desktop with:
 *
 * <pre>
 * adb push screen-camera-server.jar /data/local/tmp/
 * adb shell CLASSPATH=/data/local/tmp/screen-camera-server.jar \
 *     app_process / io.github.screencamera.Server [key=value ...]
 * </pre>
 *
 * Runs as uid 2000 (shell). No root, no APK install.
 */
public final class Server {
    public static final String VERSION = "0.1.0";

    private Server() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            Ln.e("Fatal", t);
            Ln.event("fatal", String.valueOf(t).replace('\n', ' '));
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Options opts = Options.parse(args);
        Ln.verbose = opts.verbose;
        Ln.i("screen-camera server " + VERSION + " on Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL);

        if (Build.VERSION.SDK_INT < 35) {
            throw new UnsupportedOperationException("Virtual cameras need Android 15 (API 35) or newer; "
                    + "this device is API " + Build.VERSION.SDK_INT);
        }

        Looper.prepareMainLooper();
        Env.init();

        if (Env.checkService("virtual_camera") == null) {
            throw new UnsupportedOperationException("The native 'virtual_camera' service is not running "
                    + "on this device (`adb shell service check virtual_camera`). It is an optional AOSP "
                    + "component (frameworks/av/services/camera/virtualcamera) that this ROM does not ship "
                    + "or start, so VirtualDeviceManager cannot create virtual cameras here.");
        }

        int associationId = Association.findOrCreate(opts);

        FrameStore frames = new FrameStore();
        FrameServer frameServer = new FrameServer(opts, frames);
        VirtualCameraHost host = new VirtualCameraHost(opts, frames, frameServer);
        Runtime.getRuntime().addShutdownHook(new Thread(host::close, "cleanup"));

        host.start(associationId);
        if (opts.grantPackage != null) {
            host.grantCameraPermission(opts.grantPackage);
        }

        Thread serverThread = new Thread(() -> {
            try {
                frameServer.serve();
            } catch (Throwable t) {
                Ln.e("Frame server stopped", t);
                System.exit(2);
            }
        }, "frame-server");
        serverThread.setDaemon(true);
        serverThread.start();

        Ln.event("ready", "1");
        Ln.i("Ready. Launch an app on the virtual display (am start --display <id> ...) and open its "
                + "camera; it will see the desktop region.");

        // Keep the main looper alive for any framework callbacks posted to it. The process ends
        // when adb shell is closed (SIGHUP) or the desktop client kills it; VirtualDeviceManager
        // then tears the device down via binder death of our token.
        Looper.loop();
    }
}
