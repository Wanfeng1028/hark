/*
 * COMPILE-ONLY STUB. Never packaged into the server jar.
 * Mirrors @SystemApi android.companion.virtual.camera.VirtualCameraCallback (AOSP android15-release+).
 */
package android.companion.virtual.camera;

import android.view.Surface;

public interface VirtualCameraCallback {

    /** A consumer configured a stream; write frames for it into {@code surface}. */
    void onStreamConfigured(int streamId, Surface surface, int width, int height, int format);

    /** A consumer requests a frame (default no-op in AOSP). */
    default void onProcessCaptureRequest(int streamId, long frameId) {
    }

    /** The stream is gone; stop using its surface. */
    void onStreamClosed(int streamId);
}
