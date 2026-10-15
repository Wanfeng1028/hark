package io.github.screencamera;

import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Minimal process environment for framework code running under {@code app_process} as the shell
 * user (uid 2000), without an installed APK.
 *
 * <p>The idea (a bare {@code ActivityThread} marked as system thread so that a system
 * {@link Context} can be obtained, wrapped in a context that reports the shell package) is the
 * same technique scrcpy's server uses (Apache-2.0, https://github.com/Genymobile/scrcpy). This is
 * an independent, reduced implementation of that idea.
 */
final class Env {
    private static Object activityThread;
    private static Context systemContext;

    private Env() {
    }

    /** Must be called on a thread that already has a Looper (ActivityThread creates a Handler). */
    static synchronized void init() throws ReflectiveOperationException {
        if (systemContext != null) {
            return;
        }
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = atClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        activityThread = ctor.newInstance();

        Field current = atClass.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, activityThread);

        Field systemThread = atClass.getDeclaredField("mSystemThread");
        systemThread.setAccessible(true);
        systemThread.setBoolean(activityThread, true);

        // Android 12+: some code paths (e.g. DisplayManagerGlobal on some OEM builds) need a
        // ConfigurationController. Best effort only.
        try {
            Class<?> ccClass = Class.forName("android.app.ConfigurationController");
            Class<?> atiClass = Class.forName("android.app.ActivityThreadInternal");
            Constructor<?> ccCtor = ccClass.getDeclaredConstructor(atiClass);
            ccCtor.setAccessible(true);
            Object cc = ccCtor.newInstance(activityThread);
            Field ccField = atClass.getDeclaredField("mConfigurationController");
            ccField.setAccessible(true);
            ccField.set(activityThread, cc);
        } catch (Throwable t) {
            Ln.d("ConfigurationController workaround skipped: " + t);
        }

        Method getSystemContext = atClass.getDeclaredMethod("getSystemContext");
        systemContext = (Context) getSystemContext.invoke(activityThread);
    }

    static Context systemContext() {
        if (systemContext == null) {
            throw new IllegalStateException("Env.init() not called");
        }
        return systemContext;
    }

    /** android.os.ServiceManager.getService(name) (hidden API). */
    static IBinder getService(String name) throws ReflectiveOperationException {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method m = sm.getDeclaredMethod("getService", String.class);
        return (IBinder) m.invoke(null, name);
    }

    /** android.os.ServiceManager.checkService(name): non-blocking, null if absent. */
    static IBinder checkService(String name) throws ReflectiveOperationException {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method m = sm.getDeclaredMethod("checkService", String.class);
        return (IBinder) m.invoke(null, name);
    }
}
