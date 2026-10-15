/*
 * COMPILE-ONLY STUB. Never packaged into the server jar.
 * Subset of @SystemApi android.companion.virtual.camera.VirtualCamera (AOSP android15-release+).
 */
package android.companion.virtual.camera;

import java.io.Closeable;

public final class VirtualCamera implements Closeable {
    private VirtualCamera() {
        throw new RuntimeException("Stub!");
    }

    public VirtualCameraConfig getConfig() {
        throw new RuntimeException("Stub!");
    }

    /** The camera id under which cameraserver exposes this camera (hidden API). */
    public String getId() {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void close() {
        throw new RuntimeException("Stub!");
    }
}
