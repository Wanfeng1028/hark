/*
 * COMPILE-ONLY STUB. Never packaged into the server jar.
 * Subset of @SystemApi android.companion.virtual.camera.VirtualCameraConfig (AOSP android15-release+).
 * Supported stream formats in AOSP: ImageFormat.YUV_420_888 and PixelFormat.RGBA_8888.
 */
package android.companion.virtual.camera;

import java.util.concurrent.Executor;

public final class VirtualCameraConfig {

    public static final int SENSOR_ORIENTATION_0 = 0;
    public static final int SENSOR_ORIENTATION_90 = 90;
    public static final int SENSOR_ORIENTATION_180 = 180;
    public static final int SENSOR_ORIENTATION_270 = 270;

    private VirtualCameraConfig() {
        throw new RuntimeException("Stub!");
    }

    public String getName() {
        throw new RuntimeException("Stub!");
    }

    public int getLensFacing() {
        throw new RuntimeException("Stub!");
    }

    public int getSensorOrientation() {
        throw new RuntimeException("Stub!");
    }

    public static final class Builder {
        public Builder(String name) {
            throw new RuntimeException("Stub!");
        }

        public Builder addStreamConfig(int width, int height, int format, int maximumFramesPerSecond) {
            throw new RuntimeException("Stub!");
        }

        public Builder setSensorOrientation(int sensorOrientation) {
            throw new RuntimeException("Stub!");
        }

        /** CameraMetadata.LENS_FACING_FRONT (0) / LENS_FACING_BACK (1). Mandatory. */
        public Builder setLensFacing(int lensFacing) {
            throw new RuntimeException("Stub!");
        }

        public Builder setVirtualCameraCallback(Executor executor, VirtualCameraCallback callback) {
            throw new RuntimeException("Stub!");
        }

        public VirtualCameraConfig build() {
            throw new RuntimeException("Stub!");
        }
    }
}
