/*
 * COMPILE-ONLY STUB. Never packaged into the server jar.
 *
 * Mirrors the subset of the @SystemApi / @hide surface of
 * android.companion.virtual.VirtualDeviceManager that screen-camera uses, as it
 * exists in AOSP android15-release and android16-release
 * (frameworks/base/core/java/android/companion/virtual/VirtualDeviceManager.java).
 * At runtime the real framework class from the device's boot classpath is used.
 *
 * Written from the public AOSP signatures; contains no implementation.
 */
package android.companion.virtual;

import android.companion.virtual.camera.VirtualCamera;
import android.companion.virtual.camera.VirtualCameraConfig;
import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;

import java.util.concurrent.Executor;

public final class VirtualDeviceManager {

    /** @hide constructor in AOSP: VirtualDeviceManager(IVirtualDeviceManager, Context). Called via reflection. */
    private VirtualDeviceManager() {
        throw new RuntimeException("Stub!");
    }

    /** @SystemApi, requires CREATE_VIRTUAL_DEVICE. */
    public VirtualDevice createVirtualDevice(int associationId, VirtualDeviceParams params) {
        throw new RuntimeException("Stub!");
    }

    /** @SystemApi inner class VirtualDeviceManager.VirtualDevice. */
    public static class VirtualDevice implements AutoCloseable {
        private VirtualDevice() {
            throw new RuntimeException("Stub!");
        }

        public int getDeviceId() {
            throw new RuntimeException("Stub!");
        }

        public Context createContext() {
            throw new RuntimeException("Stub!");
        }

        public VirtualDisplay createVirtualDisplay(VirtualDisplayConfig config, Executor executor,
                VirtualDisplay.Callback callback) {
            throw new RuntimeException("Stub!");
        }

        /** Available since API 35 (flag android.companion.virtual.flags.virtual_camera). */
        public VirtualCamera createVirtualCamera(VirtualCameraConfig config) {
            throw new RuntimeException("Stub!");
        }

        @Override
        public void close() {
            throw new RuntimeException("Stub!");
        }
    }
}
