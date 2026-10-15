package io.github.screencamera;

import java.util.Locale;

/**
 * Finds or creates the Companion Device Manager (CDM) association that VirtualDeviceManager
 * requires before it will create a virtual device.
 *
 * <p>Public facts this relies on (AOSP android15/16-release):
 * <ul>
 *   <li>{@code VirtualDeviceManagerService#createVirtualDevice} looks up an association owned by
 *       the calling package, and (when the persistent-device-id flag is on) requires its device
 *       profile to be one of AUTOMOTIVE_PROJECTION / APP_STREAMING / NEARBY_DEVICE_STREAMING.</li>
 *   <li>{@code cmd companiondevice associate USER_ID PACKAGE MAC_ADDRESS [DEVICE_PROFILE]}
 *       (CompanionDeviceShellCommand) creates an association; it is only stored if the package
 *       can be added as holder of the profile's role (AssociationRequestsProcessor), so — exactly
 *       like CTS FakeAssociationRule — role qualification is bypassed around the call with
 *       {@code cmd role set-bypassing-role-qualification true|false}.</li>
 *   <li>The shell package already holds CREATE_VIRTUAL_DEVICE / ADD_TRUSTED_DISPLAY through the
 *       {@code android.app.role.SYSTEM_SHELL} role (packages/modules/Permission roles.xml).</li>
 * </ul>
 */
final class Association {
    static final String PROFILE_APP_STREAMING = "android.app.role.COMPANION_DEVICE_APP_STREAMING";

    private Association() {
    }

    static int findOrCreate(Options opts) throws Exception {
        if (opts.associationId >= 0) {
            return opts.associationId;
        }
        int id = find(opts);
        if (id >= 0) {
            Ln.i("Reusing CDM association id=" + id);
            return id;
        }
        Ln.i("Creating CDM association for " + ShellContext.PACKAGE_NAME + " (" + opts.associationMac
                + ", profile " + PROFILE_APP_STREAMING + ")");
        String user = String.valueOf(opts.userId);
        Shell.run("cmd", "role", "set-bypassing-role-qualification", "true");
        try {
            Shell.Result r = Shell.run("cmd", "companiondevice", "associate", user,
                    ShellContext.PACKAGE_NAME, opts.associationMac, PROFILE_APP_STREAMING);
            if (!r.output.trim().isEmpty()) {
                Ln.i("companiondevice associate: " + r.output.trim());
            }
            // Role grant + association store are asynchronous; poll for a short while.
            for (int i = 0; i < 30; i++) {
                id = find(opts);
                if (id >= 0) {
                    Ln.i("Created CDM association id=" + id);
                    return id;
                }
                Thread.sleep(100);
            }
        } finally {
            Shell.run("cmd", "role", "set-bypassing-role-qualification", "false");
        }
        throw new IllegalStateException("Could not create a CDM association for the shell package. "
                + "Check `adb shell cmd companiondevice list " + opts.userId + "` and logcat (CDM_*).");
    }

    /** Parses `cmd companiondevice list USER`: lines "ID | PACKAGE | MAC". */
    private static int find(Options opts) throws Exception {
        Shell.Result r = Shell.run("cmd", "companiondevice", "list", String.valueOf(opts.userId));
        for (String line : r.output.split("\n")) {
            String[] parts = line.split("\\|");
            if (parts.length < 3) {
                continue;
            }
            String idStr = parts[0].trim();
            String pkg = parts[1].trim();
            String mac = parts[2].trim().toLowerCase(Locale.ROOT);
            if (pkg.equals(ShellContext.PACKAGE_NAME) && mac.equals(opts.associationMac)) {
                try {
                    return Integer.parseInt(idStr);
                } catch (NumberFormatException ignored) {
                    // header line
                }
            }
        }
        return -1;
    }
}
