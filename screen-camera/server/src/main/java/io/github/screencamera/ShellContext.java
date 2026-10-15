package io.github.screencamera;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;

/**
 * A Context that identifies as the shell package ({@code com.android.shell}, uid 2000), which is
 * the identity this process really has when started with {@code adb shell app_process}.
 *
 * <p>VirtualDeviceManagerService checks that the AttributionSource's uid equals the binder calling
 * uid and that the package belongs to that uid, then looks up a CDM association owned by that
 * package (AOSP VirtualDeviceManagerService#createVirtualDevice, android15/16-release).
 */
final class ShellContext extends ContextWrapper {
    static final String PACKAGE_NAME = "com.android.shell";
    static final int SHELL_UID = 2000;

    private static ShellContext instance;

    static synchronized ShellContext get() {
        if (instance == null) {
            instance = new ShellContext(Env.systemContext());
        }
        return instance;
    }

    private ShellContext(Context base) {
        super(base);
    }

    @Override
    public String getPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public String getOpPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public AttributionSource getAttributionSource() {
        return new AttributionSource.Builder(SHELL_UID).setPackageName(PACKAGE_NAME).build();
    }

    /** VirtualDeviceInternal calls context.getApplicationContext(); keep our overrides. */
    @Override
    public Context getApplicationContext() {
        return this;
    }
}
