
package nl.johninnl.soundcloud;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.lang.ref.WeakReference;

public final class SleepTimerEntry {
    private static final String TAG = "SleepTimer";

    private static volatile WeakReference<Activity> current =
            new WeakReference<>(null);

    private static volatile Application application;
    private static boolean callbacksRegistered;

    private static void toast(String message) {
        Application app = application;
        if (app != null) {
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show();
        }
    }

    public static void attach(Activity activity) {
        if (activity == null) return;

        current = new WeakReference<>(activity);

        Application app = activity.getApplication();
        application = app;

        SleepTimerIds.init(app);

        synchronized (SleepTimerEntry.class) {
            if (!callbacksRegistered && app != null) {
                app.registerActivityLifecycleCallbacks(new Tracker());
                callbacksRegistered = true;
            }
        }

        toast("SleepTimer: ATTACH " + activity.getClass().getSimpleName());
    }

    public static void open() {
        toast("SleepTimer: OPEN called");

        Activity activity = current.get();

        if (activity == null) {
            toast("SleepTimer: Activity is NULL");
            return;
        }

        activity.runOnUiThread(() -> {
            Activity latest = current.get();

            if (latest == null ||
                    latest.isFinishing() ||
                    latest.isDestroyed()) {
                toast("SleepTimer: No valid Activity");
                return;
            }

            try {
                SleepTimerDialog.show(latest);
                toast("SleepTimer: Dialog opened");
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to show dialog", e);
                toast("SleepTimer error: " +
                        e.getClass().getSimpleName());
            }
        });
    }

    private static final class Tracker
            implements Application.ActivityLifecycleCallbacks {

        @Override
        public void onActivityCreated(Activity activity, Bundle state) {
            current = new WeakReference<>(activity);
        }

        @Override
        public void onActivityStarted(Activity activity) {
            current = new WeakReference<>(activity);
        }

        @Override
        public void onActivityResumed(Activity activity) {
            current = new WeakReference<>(activity);
        }

        @Override public void onActivityPaused(Activity activity) {}

        @Override public void onActivityStopped(Activity activity) {}

        @Override
        public void onActivitySaveInstanceState(
                Activity activity, Bundle outState) {}

        @Override
        public void onActivityDestroyed(Activity activity) {
            if (current.get() == activity) {
                current = new WeakReference<>(null);
            }
        }
    }

    private SleepTimerEntry() {}
}
