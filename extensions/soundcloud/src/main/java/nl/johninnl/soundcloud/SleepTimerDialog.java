package nl.johninnl.soundcloud;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The sleep timer window: a circular selector, favourite chips and the Cancel / Start buttons.
 * It is a plain {@link Dialog} (not an AlertDialog) with a programmatically built layout, so no
 * resources have to be added to the APK. It only talks to the existing timer through the
 * package-private methods of {@link SleepTimer}.
 *
 * Lifetime: one window at a time (a second open() replaces the first). It is dismissed when its
 * Activity is destroyed, all callbacks are removed on dismiss, and only weak references are kept
 * in statics, so neither the Activity nor the Window can leak.
 */
final class SleepTimerDialog {
    private static final String PREFS = "morphe_sleep_timer";
    private static final String KEY_FAVORITES = "favorites";
    private static final int ACCENT = 0xFFFF5500;
    private static final int DEFAULT_MINUTES = 30;

    private static WeakReference<Dialog> showing = new WeakReference<>(null);

    static void show(Activity activity) {
        Dialog previous = showing.get();
        if (previous != null && previous.isShowing()) previous.dismiss();
        new SleepTimerDialog(activity).display();
    }

    private final Activity activity;
    private final Context app;
    private final boolean dark;
    private final float density;
    private final SharedPreferences prefs;
    private final List<Integer> favorites;

    private final int surfaceColor;
    private final int textColor;
    private final int subTextColor;
    private final int chipColor;

    private Dialog dialog;
    private LinearLayout root;
    private SleepTimerDial dial;
    private LinearLayout chipRow;
    private TextView primary;
    private boolean lastRunning;

    private SleepTimerDialog(Activity activity) {
        this.activity = activity;
        this.app = activity.getApplicationContext();
        this.dark = isDark(activity);
        this.density = activity.getResources().getDisplayMetrics().density;
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.favorites = SleepTimerMath.parseFavorites(prefs.getString(KEY_FAVORITES, ""));
        surfaceColor = dark ? 0xFF1E1E1E : 0xFFFFFFFF;
        textColor = dark ? 0xFFFFFFFF : 0xFF1A1A1A;
        subTextColor = dark ? 0xB3FFFFFF : 0x99000000;
        chipColor = dark ? 0xFF303030 : 0xFFEFEFEF;
    }

    /** SoundCloud has its own light/dark setting, so the theme's background colour decides first. */
    private static boolean isDark(Context context) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.colorBackground, value, true)
                && value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            int c = value.data;
            double luminance = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c);
            return luminance < 128;
        }
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private GradientDrawable rounded(int fill, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private void display() {
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int width = Math.min(dp(360), metrics.widthPixels - dp(32));
        // The dial is a square: bounded by the width and, in landscape, by the available height.
        int dialSize = (int) Math.min(dp(260), Math.min(width - dp(40), metrics.heightPixels * 0.42f));

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(rounded(surfaceColor, 28));
        root.setPadding(dp(20), dp(18), dp(20), dp(14));

        TextView title = text("Таймер сна", 18, textColor, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dial = new SleepTimerDial(activity, dark);
        LinearLayout.LayoutParams dialParams = new LinearLayout.LayoutParams(dialSize, dialSize);
        dialParams.gravity = Gravity.CENTER_HORIZONTAL;
        dialParams.topMargin = dp(10);
        root.addView(dial, dialParams);

        TextView hint = text(" ",
            12, subTextColor, false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(6);
        root.addView(hint, hintParams);

        TextView favTitle = text("Сохранённое", 13, subTextColor, true);
        LinearLayout.LayoutParams favTitleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        favTitleParams.topMargin = dp(12);
        root.addView(favTitle, favTitleParams);

        HorizontalScrollView chipScroll = new HorizontalScrollView(activity);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(activity);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(chipRow, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams chipScrollParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipScrollParams.topMargin = dp(6);
        root.addView(chipScroll, chipScrollParams);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        TextView cancel = button("Отмена", false);
        cancel.setOnClickListener(v -> dialog.dismiss());
        primary = button("Запуск", true);
        primary.setOnClickListener(v -> onPrimary());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        primaryParams.leftMargin = dp(8);
        buttons.addView(cancel, cancelParams);
        buttons.addView(primary, primaryParams);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsParams.topMargin = dp(16);
        root.addView(buttons, buttonsParams);

        ScrollView scroll = new ScrollView(activity); // keeps the buttons reachable in landscape
        scroll.addView(root, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dial.setSelectedMinutes(DEFAULT_MINUTES, false);
        dial.setListener(new SleepTimerDial.Listener() {
            @Override public void onMinutesChanged(int minutes) { updateChipSelection(); }
            @Override public void onLongPressSave(int minutes) { saveFavorite(minutes); }
        });
        rebuildChips();

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(scroll);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        final Runnable tick = new Runnable() {
            @Override public void run() {
                refresh();
                root.postDelayed(this, 1000);
            }
        };
        final Application application = activity.getApplication();
        final Application.ActivityLifecycleCallbacks closeWithActivity = new DismissWithActivity(activity, dialog);
        if (application != null) application.registerActivityLifecycleCallbacks(closeWithActivity);
        dialog.setOnDismissListener(d -> {
            root.removeCallbacks(tick);
            if (application != null) application.unregisterActivityLifecycleCallbacks(closeWithActivity);
            if (showing.get() == dialog) showing.clear();
        });

        showing = new WeakReference<>(dialog);
        dialog.show();
        refresh();
        root.postDelayed(tick, 1000);
    }

    private TextView button(String label, boolean filled) {
        TextView view = text(label, 14, filled ? 0xFFFFFFFF : ACCENT, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), dp(10), dp(16), dp(10));
        view.setBackground(rounded(filled ? ACCENT : Color.TRANSPARENT, 20));
        view.setClickable(true);
        return view;
    }

    private void onPrimary() {
        if (SleepTimer.isRunning()) {
            SleepTimer.stop(app);   // existing logic: cancels the pending pause
            refresh();              // the window stays open and returns to the selector
        } else {
            SleepTimer.start(app, dial.getSelectedMinutes()); // existing logic
            dialog.dismiss();
        }
    }

    /** Syncs the window with the real timer once a second (it may also finish while this is open). */
    private void refresh() {
        boolean running = SleepTimer.isRunning();
        long remaining = SleepTimer.remainingMillis();
        dial.setRunning(running, remaining);
        if (running != lastRunning) {
            lastRunning = running;
            primary.setText(running ? "Отключить таймер" : "Запустить таймер");
            setChipsEnabled(!running);
        }
    }

    // ---- favourites ------------------------------------------------------------------------

    private void saveFavorite(int minutes) {
        if (SleepTimerMath.addFavorite(favorites, minutes)) {
            persist();
            rebuildChips();
            Toast.makeText(app, "Сохранено: " + SleepTimerMath.formatMinutes(minutes), Toast.LENGTH_SHORT).show();
        } else {
            if (Build.VERSION.SDK_INT >= 30) dial.performHapticFeedback(HapticFeedbackConstants.REJECT);
            Toast.makeText(app, "Уже сохранено", Toast.LENGTH_SHORT).show();
        }
    }

    private void removeFavorite(int minutes) {
        if (SleepTimerMath.removeFavorite(favorites, minutes)) {
            persist();
            rebuildChips();
            Toast.makeText(app, SleepTimerMath.formatMinutes(minutes) + "удалено из сохраненных",
                Toast.LENGTH_SHORT).show();
        }
    }

    private void persist() {
        prefs.edit().putString(KEY_FAVORITES, SleepTimerMath.serializeFavorites(favorites)).apply();
    }

    private void rebuildChips() {
        chipRow.removeAllViews();
        if (favorites.isEmpty()) {
            chipRow.addView(text("Ещё нет", 13, subTextColor, false));
            return;
        }
        for (final int minutes : new ArrayList<>(favorites)) {
            TextView chip = text(SleepTimerMath.formatMinutes(minutes), 14, textColor, false);
            chip.setTag(minutes);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));
            chip.setClickable(true);
            chip.setOnClickListener(v -> {
                if (!SleepTimer.isRunning()) dial.setSelectedMinutes(minutes, true);
            });
            chip.setOnLongClickListener(v -> {
                removeFavorite(minutes);
                return true;
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = dp(8);
            chipRow.addView(chip, params);
        }
        updateChipSelection();
        setChipsEnabled(!SleepTimer.isRunning());
    }

    private void updateChipSelection() {
        int selected = dial.getSelectedMinutes();
        for (int i = 0; i < chipRow.getChildCount(); i++) {
            View child = chipRow.getChildAt(i);
            if (!(child.getTag() instanceof Integer)) continue;
            boolean isSelected = ((Integer) child.getTag()) == selected;
            GradientDrawable bg = rounded(isSelected ? ((ACCENT & 0x00FFFFFF) | 0x33000000) : chipColor, 18);
            if (isSelected) bg.setStroke(dp(1.5f), ACCENT);
            child.setBackground(bg);
        }
    }

    private void setChipsEnabled(boolean enabled) {
        chipRow.setAlpha(enabled ? 1f : 0.4f);
    }

    /** Closes the window together with its Activity, so no Window is ever leaked. */
    private static final class DismissWithActivity implements Application.ActivityLifecycleCallbacks {
        private final WeakReference<Activity> target;
        private final WeakReference<Dialog> dialog;

        DismissWithActivity(Activity activity, Dialog dialog) {
            this.target = new WeakReference<>(activity);
            this.dialog = new WeakReference<>(dialog);
        }

        @Override public void onActivityDestroyed(Activity activity) {
            if (target.get() != activity) return;
            Dialog d = dialog.get();
            if (d != null && d.isShowing()) d.dismiss();
        }

        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityResumed(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
    }
}
