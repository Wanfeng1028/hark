/*
 * COMPILE-ONLY STUB. Never packaged into the server jar.
 * Subset of @SystemApi android.companion.virtual.VirtualDeviceParams (AOSP android15/16-release).
 */
package android.companion.virtual;

public final class VirtualDeviceParams {

    public static final int DEVICE_POLICY_DEFAULT = 0;
    public static final int DEVICE_POLICY_CUSTOM = 1;

    public static final int POLICY_TYPE_SENSORS = 0;
    public static final int POLICY_TYPE_AUDIO = 1;
    public static final int POLICY_TYPE_RECENTS = 2;
    public static final int POLICY_TYPE_ACTIVITY = 3;
    public static final int POLICY_TYPE_CLIPBOARD = 4;
    /** Since API 35 (flag virtual_camera). */
    public static final int POLICY_TYPE_CAMERA = 5;

    private VirtualDeviceParams() {
        throw new RuntimeException("Stub!");
    }

    public static final class Builder {
        public Builder() {
            throw new RuntimeException("Stub!");
        }

        public Builder setName(String name) {
            throw new RuntimeException("Stub!");
        }

        public Builder setDevicePolicy(int policyType, int devicePolicy) {
            throw new RuntimeException("Stub!");
        }

        public VirtualDeviceParams build() {
            throw new RuntimeException("Stub!");
        }
    }
}
