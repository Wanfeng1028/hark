package io.github.screencamera;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses "key=value" arguments passed on the app_process command line. */
final class Options {
    /** Camera stream size advertised to apps (landscape, like a real sensor). */
    int width = 1280;
    int height = 720;
    int fps = 30;
    /** Which lens facings to register: "back", "front" or both. */
    List<String> facings = new ArrayList<>(List.of("back", "front"));
    /** Sensor orientation per facing, mimicking typical phones (back 90, front 270). */
    int orientationBack = 90;
    int orientationFront = 270;

    /** The virtual display that hosts the app which will see the virtual camera. */
    int displayWidth = 1080;
    int displayHeight = 1920;
    int displayDpi = 420;
    boolean alwaysUnlocked = false;

    /** Local abstract socket name; the client does `adb forward tcp:N localabstract:<name>`. */
    String socketName = "screen-camera";

    /** Fake companion-device MAC used for the CDM association (locally administered). */
    String associationMac = "02:53:43:41:4d:01";
    /** -1 = find or create automatically. */
    int associationId = -1;
    int userId = 0;

    /** Package to pre-grant CAMERA for on the virtual device (best effort). */
    String grantPackage = null;

    boolean verbose = false;

    static Options parse(String[] args) {
        Options o = new Options();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("Expected key=value, got: " + arg);
            }
            String k = arg.substring(0, eq).toLowerCase(Locale.ROOT);
            String v = arg.substring(eq + 1);
            switch (k) {
                case "width": o.width = Integer.parseInt(v); break;
                case "height": o.height = Integer.parseInt(v); break;
                case "fps": o.fps = Integer.parseInt(v); break;
                case "facing": {
                    o.facings = new ArrayList<>();
                    for (String f : v.split(",")) {
                        f = f.trim().toLowerCase(Locale.ROOT);
                        if (!f.equals("back") && !f.equals("front")) {
                            throw new IllegalArgumentException("facing must be back and/or front");
                        }
                        o.facings.add(f);
                    }
                    break;
                }
                case "orientation_back": o.orientationBack = checkOrientation(v); break;
                case "orientation_front": o.orientationFront = checkOrientation(v); break;
                case "display_width": o.displayWidth = Integer.parseInt(v); break;
                case "display_height": o.displayHeight = Integer.parseInt(v); break;
                case "display_dpi": o.displayDpi = Integer.parseInt(v); break;
                case "always_unlocked": o.alwaysUnlocked = Boolean.parseBoolean(v); break;
                case "socket": o.socketName = v; break;
                case "association_mac": o.associationMac = v.toLowerCase(Locale.ROOT); break;
                case "association_id": o.associationId = Integer.parseInt(v); break;
                case "user": o.userId = Integer.parseInt(v); break;
                case "grant": o.grantPackage = v.isEmpty() ? null : v; break;
                case "verbose": o.verbose = Boolean.parseBoolean(v); break;
                default:
                    throw new IllegalArgumentException("Unknown option: " + k);
            }
        }
        if (o.width <= 0 || o.height <= 0 || o.fps <= 0 || o.fps > 60) {
            throw new IllegalArgumentException("Invalid width/height/fps");
        }
        if (o.facings.isEmpty()) {
            throw new IllegalArgumentException("At least one facing is required");
        }
        return o;
    }

    private static int checkOrientation(String v) {
        int o = Integer.parseInt(v);
        if (o != 0 && o != 90 && o != 180 && o != 270) {
            throw new IllegalArgumentException("orientation must be 0/90/180/270");
        }
        return o;
    }
}
