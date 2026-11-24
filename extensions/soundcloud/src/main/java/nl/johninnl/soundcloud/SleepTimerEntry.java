package nl.johninnl.soundcloud;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * Entry points called from the patched SoundCloud bytecode:
 *
 *   invoke-static { p0 }, Lnl/johninnl/soundcloud/SleepTimerEntry;->attach(Landroid/app/Activity;)V
 *   invoke-static { }, Lnl/johninnl/soundcloud/SleepTimerEntry;->open()V
 *
 * AlertDialog needs an Activity context (a window token). attach() is called once from
 * the launcher Activity's onCreate; from then on an ActivityLifecycleCallbacks tracker
 * keeps the most recently created/resumed Activity, so the dialog is shown on the Activity
 * that actually hosts the track menu, not on a finished splash/launcher Activity. Only a
 * WeakReference is kept, so no Activity is leaked.
 */
public final class SleepTimerEntry {
    private static final String TAG = "SleepTimer";

    private static volatile WeakReference<Activity> current = new WeakReference<>(null);
    private static boolean callbacksRegistered;

    public static void attach(Activity activity) {
        if (activity == null) return;
        current = new WeakReference<>(activity);
        Application app = activity.getApplication();
        SleepTimerIds.init(app);
        synchronized (SleepTimerEntry.class) {
            if (!callbacksRegistered && app != null) {
                app.registerActivityLifecycleCallbacks(new Tracker());
                callbacksRegistered = true;
            }
        }
    }

    public static void open() {
        final Activity activity = current.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Log.w(TAG, "No live Activity to show the sleep timer dialog");
            return;
        }
        // The click may arrive on any thread; dialogs must be created on the UI thread.
        activity.runOnUiThread(() -> {
            try {
                SleepTimerDialog.show(activity);
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to show sleep timer dialog", e);
            }
        });
    }

    private static final class Tracker implements Application.ActivityLifecycleCallbacks {
    @Override
    public void onActivityCreated(Activity activity, Bundle state) {
        current = new WeakReference<>(activity);
    }

    @Override public void onActivityStarted(Activity activity) {
        current = new WeakReference<>(activity);
    }

    @Override public void onActivityResumed(Activity activity) {
        current = new WeakReference<>(activity);
    }

    @Override public void onActivityPaused(Activity activity) {}

    @Override public void onActivityStopped(Activity activity) {}

    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (current.get() == activity) {
            current = new WeakReference<>(null);
        }
    }
    }

    private SleepTimerEntry() {}
}
