package nl.johninnl.soundcloud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Circular time selector. The value is one continuous angle (see {@link SleepTimerMath}), so
 * every full turn adds 60 minutes in either direction and nothing resets at the 60 mark.
 *
 * Gestures: a touch jumps the handle to the finger by the shortest way, dragging follows the
 * finger by deltas (any number of turns), lifting the finger starts an inertial fling that then
 * settles on a whole minute, and holding the finger still saves the value as a favourite.
 *
 * While a timer is running the view is a read-only countdown: the centre shows the time left.
 *
 * The view keeps no reference to an Activity other than the Context it was created with, and it
 * removes all its callbacks when it is detached from the window.
 */
final class SleepTimerDial extends View {
    interface Listener {
        /** Called every time the selected whole minute changes. */
        void onMinutesChanged(int minutes);

        /** Called when the finger is held on the dial. */
        void onLongPressSave(int minutes);
    }

    private static final int ACCENT = 0xFFFF5500; // SoundCloud orange
    /** Haptics fire per minute change but never more often than this (see the audit notes). */
    private static final long HAPTIC_MIN_INTERVAL_MS = 25;
    private static final double SNAP_RATE = 18.0;
    private static final double JUMP_RATE = 9.0;

    private static final int PHASE_IDLE = 0;
    private static final int PHASE_FLING = 1;
    private static final int PHASE_ANIMATE = 2;

    private final int trackColor;
    private final int tickColor;
    private final int textColor;
    private final int subTextColor;
    private final float density;
    private final int touchSlop;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint doneTurnsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint minorTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint majorTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mainTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private float cx;
    private float cy;
    private float ringRadius;
    private float strokeWidth;
    private float handleRadius;
    private float innerTouchRadius;
    private float outerTouchRadius;
    private float[] minorTicks = new float[0];
    private float[] majorTicks = new float[0];

    private final SleepTimerMath.Drag drag = new SleepTimerMath.Drag();
    private final SleepTimerMath.Velocity velocity = new SleepTimerMath.Velocity();
    private final SleepTimerMath.Inertia inertia = new SleepTimerMath.Inertia();

    private Listener listener;
    private boolean running;
    private long remainingMillis;
    private boolean dragging;
    private boolean longPressFired;
    private boolean touchedCentre;
    private float downX;
    private float downY;
    private int lastMinute = SleepTimerMath.MIN_MINUTES;
    private long lastHapticAt;

    private int phase = PHASE_IDLE;
    private long lastFrameAt;
    private double animTarget;
    private double animRate = SNAP_RATE;

    SleepTimerDial(Context context, boolean dark) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        trackColor = dark ? 0x33FFFFFF : 0x1F000000;
        tickColor = dark ? 0x66FFFFFF : 0x66000000;
        textColor = dark ? 0xFFFFFFFF : 0xFF1A1A1A;
        subTextColor = dark ? 0xB3FFFFFF : 0x99000000;

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(trackColor);
        doneTurnsPaint.setStyle(Paint.Style.STROKE);
        doneTurnsPaint.setColor((ACCENT & 0x00FFFFFF) | 0x66000000);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(ACCENT);
        minorTickPaint.setColor(tickColor);
        majorTickPaint.setColor(tickColor);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(0xFFFFFFFF);
        handleRingPaint.setStyle(Paint.Style.STROKE);
        handleRingPaint.setColor(ACCENT);
        mainTextPaint.setTextAlign(Paint.Align.CENTER);
        mainTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        mainTextPaint.setColor(textColor);
        subTextPaint.setTextAlign(Paint.Align.CENTER);
        subTextPaint.setColor(subTextColor);
        setHapticFeedbackEnabled(true);
        setContentDescription("Круговой выбор времени таймера сна");
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    int getSelectedMinutes() {
        // While the handle glides to a chosen value, the chosen value is already the selection.
        return SleepTimerMath.minutesOf(phase == PHASE_ANIMATE ? animTarget : drag.total);
    }

    /** Selects a value; with animate=true the handle glides there (any number of turns). */
    void setSelectedMinutes(int minutes, boolean animate) {
        if (running) return;
        stopMotion();
        double target = SleepTimerMath.degreesOf(minutes);
        if (animate) {
            animTarget = target;
            animRate = JUMP_RATE;
            startFrames(PHASE_ANIMATE);
        } else {
            drag.total = target;
            invalidate();
        }
        lastMinute = SleepTimerMath.minutesOf(target);
        if (listener != null) listener.onMinutesChanged(lastMinute);
    }

    /** Switches between the selector and the read-only countdown. */
    void setRunning(boolean running, long remainingMillis) {
        if (running && !this.running) stopMotion();
        this.running = running;
        this.remainingMillis = Math.max(0, remainingMillis);
        invalidate();
    }

    // ---- geometry ----------------------------------------------------------------------------

    private float dp(float v) {
        return v * density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float size = Math.min(w, h);
        cx = w / 2f;
        cy = h / 2f;
        strokeWidth = size * 0.07f;
        handleRadius = strokeWidth * 0.95f;
        ringRadius = size / 2f - handleRadius - dp(2);
        oval.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius);
        innerTouchRadius = ringRadius * 0.35f;
        outerTouchRadius = size / 2f + dp(8);

        trackPaint.setStrokeWidth(strokeWidth);
        doneTurnsPaint.setStrokeWidth(strokeWidth);
        arcPaint.setStrokeWidth(strokeWidth);
        minorTickPaint.setStrokeWidth(Math.max(1f, dp(1)));
        majorTickPaint.setStrokeWidth(Math.max(2f, dp(2)));
        handleRingPaint.setStrokeWidth(Math.max(2f, dp(2.5f)));
        mainTextPaint.setTextSize(size * 0.22f);
        subTextPaint.setTextSize(size * 0.072f);

        float outer = ringRadius - strokeWidth * 0.9f;
        minorTicks = new float[48 * 4];
        majorTicks = new float[12 * 4];
        int minor = 0;
        int major = 0;
        for (int i = 0; i < 60; i++) {
            double a = Math.toRadians(i * 6.0);
            float sin = (float) Math.sin(a);
            float cos = (float) Math.cos(a);
            boolean isMajor = i % 5 == 0;
            float inner = outer - (isMajor ? strokeWidth * 0.75f : strokeWidth * 0.35f);
            float[] target = isMajor ? majorTicks : minorTicks;
            int at = isMajor ? major++ * 4 : minor++ * 4;
            target[at] = cx + outer * sin;
            target[at + 1] = cy - outer * cos;
            target[at + 2] = cx + inner * sin;
            target[at + 3] = cy - inner * cos;
        }
    }

    // ---- drawing -----------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        double total = running
            ? Math.max(0, remainingMillis / 60_000.0 * SleepTimerMath.DEGREES_PER_MINUTE)
            : drag.total;

        canvas.drawCircle(cx, cy, ringRadius, trackPaint);
        canvas.drawLines(minorTicks, minorTickPaint);
        canvas.drawLines(majorTicks, majorTickPaint);

        int turns = SleepTimerMath.turnsBehind(total);
        double sweep = SleepTimerMath.sweepOf(total);
        if (turns > 0) canvas.drawCircle(cx, cy, ringRadius, doneTurnsPaint);
        if (sweep > 0) canvas.drawArc(oval, -90f, (float) sweep, false, arcPaint);

        double theta = Math.toRadians(sweep);
        float hx = cx + ringRadius * (float) Math.sin(theta);
        float hy = cy - ringRadius * (float) Math.cos(theta);
        float hr = dragging ? handleRadius * 1.18f : handleRadius;
        canvas.drawCircle(hx, hy, hr, handlePaint);
        canvas.drawCircle(hx, hy, hr, handleRingPaint);

        String main;
        String sub;
        String sub2 = null;
        if (running) {
            main = SleepTimerMath.formatClock(remainingMillis);
            sub = "осталось";
            mainTextPaint.setTextSize(Math.min(getWidth(), getHeight()) * (main.length() > 5 ? 0.15f : 0.19f));
        } else {
            int minutes = SleepTimerMath.minutesOf(drag.total);
            main = String.valueOf(minutes);
            sub = "мин";
            if (minutes >= 60) sub2 = SleepTimerMath.formatMinutes(minutes);
            mainTextPaint.setTextSize(Math.min(getWidth(), getHeight()) * (main.length() > 3 ? 0.17f : 0.22f));
        }
        Paint.FontMetrics mf = mainTextPaint.getFontMetrics();
        Paint.FontMetrics sf = subTextPaint.getFontMetrics();
        float mainH = mf.descent - mf.ascent;
        float subH = sf.descent - sf.ascent;
        float gap = dp(2);
        float block = mainH + gap + subH + (sub2 != null ? gap + subH : 0);
        float top = cy - block / 2f;
        canvas.drawText(main, cx, top - mf.ascent, mainTextPaint);
        float y = top + mainH + gap;
        canvas.drawText(sub, cx, y - sf.ascent, subTextPaint);
        if (sub2 != null) canvas.drawText(sub2, cx, y + subH + gap - sf.ascent, subTextPaint);
    }

    // ---- touch -------------------------------------------------------------------------------

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (!dragging || longPressFired) return;
            longPressFired = true;
            int minutes = getSelectedMinutes();
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (listener != null) listener.onLongPressSave(minutes);
        }
    };

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (running) return false;
        float dx = event.getX() - cx;
        float dy = event.getY() - cy;
        double dist = Math.hypot(dx, dy);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (dist > outerTouchRadius) return false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                stopMotion();
                velocity.clear();
                touchedCentre = dist < innerTouchRadius;
                if (!touchedCentre) drag.down(SleepTimerMath.angleOf(dx, dy));
                dragging = true;
                longPressFired = false;
                downX = event.getX();
                downY = event.getY();
                velocity.add(event.getEventTime(), drag.total);
                notifyMinuteChange(true);
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return false;
                if (longPressFired) return true;
                if (Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop) removeCallbacks(longPress);
                if (dist >= innerTouchRadius) {
                    // Leaving the centre mid-gesture starts following from the current finger angle.
                    if (touchedCentre) {
                        drag.down(SleepTimerMath.angleOf(dx, dy));
                        touchedCentre = false;
                    } else {
                        drag.move(SleepTimerMath.angleOf(dx, dy));
                    }
                    velocity.add(event.getEventTime(), drag.total);
                    notifyMinuteChange(true);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) return false;
                removeCallbacks(longPress);
                dragging = false;
                boolean cancelled = event.getActionMasked() == MotionEvent.ACTION_CANCEL;
                if (!longPressFired && !cancelled && !touchedCentre) {
                    inertia.start(velocity.perSecond(event.getEventTime()));
                }
                if (inertia.active()) {
                    startFrames(PHASE_FLING);
                } else {
                    animTarget = SleepTimerMath.snapDegrees(drag.total);
                    animRate = SNAP_RATE;
                    startFrames(PHASE_ANIMATE);
                }
                invalidate();
                return true;
            }
            default:
                return true;
        }
    }

    // ---- motion ------------------------------------------------------------------------------

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            double dt = Math.min(0.05, Math.max(0.0, (now - lastFrameAt) / 1000.0));
            lastFrameAt = now;
            boolean again = false;

            if (phase == PHASE_FLING) {
                double delta = inertia.step(dt);
                double before = drag.total;
                drag.total = SleepTimerMath.clampDegrees(drag.total + delta);
                boolean hitEnd = drag.total == before && delta != 0;
                if (hitEnd) inertia.stop();
                notifyMinuteChange(true);
                if (inertia.active()) {
                    again = true;
                } else {
                    animTarget = SleepTimerMath.snapDegrees(drag.total);
                    animRate = SNAP_RATE;
                    phase = PHASE_ANIMATE;
                }
            }
            if (phase == PHASE_ANIMATE) {
                double diff = animTarget - drag.total;
                if (Math.abs(diff) < 0.05) {
                    drag.total = animTarget;
                    phase = PHASE_IDLE;
                } else {
                    drag.total += diff * (1.0 - Math.exp(-animRate * dt));
                    again = true;
                }
                notifyMinuteChange(false);
            }
            invalidate();
            if (again) postOnAnimation(this);
        }
    };

    private void startFrames(int newPhase) {
        phase = newPhase;
        lastFrameAt = SystemClock.uptimeMillis();
        removeCallbacks(frame);
        postOnAnimation(frame);
    }

    private void stopMotion() {
        removeCallbacks(frame);
        removeCallbacks(longPress);
        inertia.stop();
        phase = PHASE_IDLE;
    }

    /** Notifies on every new whole minute; the haptic tick is tied to this change only. */
    private void notifyMinuteChange(boolean haptic) {
        int minutes = SleepTimerMath.minutesOf(drag.total);
        if (minutes == lastMinute) return;
        lastMinute = minutes;
        if (phase == PHASE_ANIMATE) return; // gliding to an already announced value
        if (haptic) tick();
        if (listener != null) listener.onMinutesChanged(minutes);
    }

    private void tick() {
        long now = SystemClock.uptimeMillis();
        if (now - lastHapticAt < HAPTIC_MIN_INTERVAL_MS) return;
        lastHapticAt = now;
        performHapticFeedback(Build.VERSION.SDK_INT >= 34
            ? HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            : HapticFeedbackConstants.CLOCK_TICK);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopMotion();
        super.onDetachedFromWindow();
    }
}
