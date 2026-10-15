package io.github.screencamera;

import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceParams;
import android.companion.virtual.camera.VirtualCamera;
import android.companion.virtual.camera.VirtualCameraCallback;
import android.companion.virtual.camera.VirtualCameraConfig;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraMetadata;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.UserHandle;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the VirtualDevice, its virtual camera(s) and the virtual display that hosts the target app.
 *
 * <p>Why a virtual display: cameras are device-aware since Android 14/15. With
 * {@code POLICY_TYPE_CAMERA = DEVICE_POLICY_CUSTOM}, contexts associated with the virtual device
 * see the virtual device's cameras instead of the phone's; contexts of the default device (apps on
 * the phone's own screen) keep seeing the real cameras (AOSP VirtualDeviceParams javadoc for
 * POLICY_TYPE_CAMERA). An activity gets the virtual device's id when it runs on one of that
 * device's virtual displays. So the app that should "see the desktop" must run on our display.
 */
final class VirtualCameraHost implements AutoCloseable {

    interface StreamListener {
        void onStreamsChanged(boolean active, int width, int height);
    }

    // DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED / _ALWAYS_UNLOCKED are @SystemApi/@hide.
    private static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10;
    private static final int VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 << 12;

    private final Options opts;
    private final FrameStore frames;
    private final StreamListener listener;
    private final ExecutorService callbackExecutor = Executors.newSingleThreadExecutor();
    private final List<VirtualCamera> cameras = new ArrayList<>();
    private final Map<String, StreamRenderer> renderers = new HashMap<>();

    private VirtualDeviceManager.VirtualDevice device;
    private VirtualDisplay display;
    private ImageReader displaySink;
    private HandlerThread sinkThread;

    VirtualCameraHost(Options opts, FrameStore frames, StreamListener listener) {
        this.opts = opts;
        this.frames = frames;
        this.listener = listener;
    }

    void start(int associationId) throws Exception {
        VirtualDeviceManager vdm = createVirtualDeviceManager();

        VirtualDeviceParams params = new VirtualDeviceParams.Builder()
                .setName("screen-camera")
                .setDevicePolicy(VirtualDeviceParams.POLICY_TYPE_CAMERA,
                        VirtualDeviceParams.DEVICE_POLICY_CUSTOM)
                .build();
        device = vdm.createVirtualDevice(associationId, params);
        Ln.i("Virtual device created: deviceId=" + device.getDeviceId());
        Ln.event("device_id", device.getDeviceId());

        for (String facing : opts.facings) {
            boolean back = facing.equals("back");
            int lens = back ? CameraMetadata.LENS_FACING_BACK : CameraMetadata.LENS_FACING_FRONT;
            int orientation = back ? opts.orientationBack : opts.orientationFront;
            String label = "screen-camera-" + facing;
            VirtualCameraConfig config = new VirtualCameraConfig.Builder(label)
                    .addStreamConfig(opts.width, opts.height, ImageFormat.YUV_420_888, opts.fps)
                    .setLensFacing(lens)
                    .setSensorOrientation(orientation)
                    .setVirtualCameraCallback(callbackExecutor,
                            new Callback(facing, orientation))
                    .build();
            VirtualCamera camera = device.createVirtualCamera(config);
            cameras.add(camera);
            String id;
            try {
                id = camera.getId();
            } catch (Throwable t) {
                id = "?";
            }
            Ln.i("Virtual camera registered: " + label + " (cameraId=" + id + ", "
                    + opts.width + "x" + opts.height + "@" + opts.fps + ", orientation=" + orientation + ")");
        }

        createDisplay();
    }

    private void createDisplay() {
        sinkThread = new HandlerThread("display-sink");
        sinkThread.start();
        // Our own surface for the virtual display. Its content is discarded here: the desktop
        // shows the display through scrcpy --display-id, which mirrors it independently.
        displaySink = ImageReader.newInstance(opts.displayWidth, opts.displayHeight,
                PixelFormat.RGBA_8888, 2);
        displaySink.setOnImageAvailableListener(reader -> {
            Image img = reader.acquireLatestImage();
            if (img != null) {
                img.close();
            }
        }, new Handler(sinkThread.getLooper()));

        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | VIRTUAL_DISPLAY_FLAG_TRUSTED;
        if (opts.alwaysUnlocked) {
            flags |= VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED;
        }
        VirtualDisplayConfig config = new VirtualDisplayConfig.Builder("screen-camera",
                opts.displayWidth, opts.displayHeight, opts.displayDpi)
                .setSurface(displaySink.getSurface())
                .setFlags(flags)
                .build();
        display = device.createVirtualDisplay(config, null, null);
        if (display == null) {
            throw new IllegalStateException("createVirtualDisplay returned null");
        }
        int displayId = display.getDisplay().getDisplayId();
        Ln.i("Virtual display created: displayId=" + displayId + " (" + opts.displayWidth + "x"
                + opts.displayHeight + "/" + opts.displayDpi + "dpi)");
        Ln.event("display_id", displayId);
    }

    int deviceId() {
        return device.getDeviceId();
    }

    /**
     * Best effort: grant CAMERA to {@code pkg} for this virtual device. Device-aware permissions
     * mean a grant on the phone does not carry over to the virtual device (CTS
     * VirtualCameraPermissionTest: "virtualDevice_doesNotHaveCameraPermissionByDefault"). CTS
     * grants it through a device context's PackageManager, which we mirror here. Hidden/system
     * API, so via reflection; failure only means the app will show its own permission prompt.
     */
    void grantCameraPermission(String pkg) {
        try {
            Context deviceContext = ShellContext.get().createDeviceContext(device.getDeviceId());
            PackageManager pm = deviceContext.getPackageManager();
            Method grant = PackageManager.class.getMethod("grantRuntimePermission", String.class,
                    String.class, UserHandle.class);
            Object user = UserHandle.class.getMethod("of", int.class).invoke(null, opts.userId);
            grant.invoke(pm, pkg, "android.permission.CAMERA", user);
            Ln.i("Granted CAMERA to " + pkg + " on virtual device " + device.getDeviceId());
        } catch (Throwable t) {
            Ln.w("Could not pre-grant CAMERA to " + pkg + " on the virtual device (" + t
                    + "). Accept the app's permission prompt on the virtual display instead.");
        }
    }

    private static VirtualDeviceManager createVirtualDeviceManager() throws Exception {
        IBinder binder = Env.getService("virtualdevice"); // Context.VIRTUAL_DEVICE_SERVICE
        if (binder == null) {
            throw new IllegalStateException("virtualdevice system service not found");
        }
        Class<?> stub = Class.forName("android.companion.virtual.IVirtualDeviceManager$Stub");
        Object service = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        Class<?> iface = Class.forName("android.companion.virtual.IVirtualDeviceManager");
        Constructor<VirtualDeviceManager> ctor =
                VirtualDeviceManager.class.getDeclaredConstructor(iface, Context.class);
        ctor.setAccessible(true);
        return ctor.newInstance(service, ShellContext.get());
    }

    private void publishState() {
        int maxW = 0;
        int maxH = 0;
        boolean active;
        synchronized (renderers) {
            active = !renderers.isEmpty();
        }
        if (active) {
            // Report the upright content size the desktop should aim for.
            boolean swap = false;
            for (String f : opts.facings) {
                int o = f.equals("back") ? opts.orientationBack : opts.orientationFront;
                swap |= (o == 90 || o == 270);
            }
            maxW = swap ? opts.height : opts.width;
            maxH = swap ? opts.width : opts.height;
        }
        listener.onStreamsChanged(active, maxW, maxH);
    }

    private final class Callback implements VirtualCameraCallback {
        private final String facing;
        private final int orientation;

        Callback(String facing, int orientation) {
            this.facing = facing;
            this.orientation = orientation;
        }

        @Override
        public void onStreamConfigured(int streamId, Surface surface, int width, int height,
                int format) {
            Ln.i("Stream configured: " + facing + "/" + streamId + " " + width + "x" + height
                    + " format=" + format);
            String key = facing + "/" + streamId;
            StreamRenderer r = new StreamRenderer(facing, streamId, surface, width, height,
                    orientation, opts.fps, frames);
            StreamRenderer old;
            synchronized (renderers) {
                old = renderers.put(key, r);
            }
            if (old != null) {
                old.stop();
            }
            r.start();
            publishState();
        }

        @Override
        public void onProcessCaptureRequest(int streamId, long frameId) {
            // Frames are produced at a fixed rate by StreamRenderer; nothing to do per request.
        }

        @Override
        public void onStreamClosed(int streamId) {
            Ln.i("Stream closed: " + facing + "/" + streamId);
            StreamRenderer r;
            synchronized (renderers) {
                r = renderers.remove(facing + "/" + streamId);
            }
            if (r != null) {
                r.stop();
            }
            publishState();
        }
    }

    @Override
    public void close() {
        synchronized (renderers) {
            for (StreamRenderer r : renderers.values()) {
                r.stop();
            }
            renderers.clear();
        }
        for (VirtualCamera c : cameras) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // best effort
            }
        }
        cameras.clear();
        if (display != null) {
            try {
                display.release();
            } catch (Throwable ignored) {
                // best effort
            }
        }
        if (device != null) {
            try {
                device.close();
            } catch (Throwable ignored) {
                // best effort
            }
        }
        if (displaySink != null) {
            displaySink.close();
        }
        if (sinkThread != null) {
            sinkThread.quitSafely();
        }
        callbackExecutor.shutdownNow();
    }
}
