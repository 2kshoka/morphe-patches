package nl.johninnl.soundcloud;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

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
    Activity activity = current.get();

    if (activity == null) {
        return;
    }

    activity.runOnUiThread(() -> {
        Activity latest = current.get();

        if (latest == null || latest.isFinishing() || latest.isDestroyed()) {
            Toast.makeText(activity, "SleepTimer: No valid Activity", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            SleepTimerDialog.show(latest);
            Toast.makeText(latest, "SleepTimer: Dialog opened", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(latest, "SleepTimer error: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
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
