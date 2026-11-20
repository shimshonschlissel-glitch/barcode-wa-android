package com.barcodewa.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Two small buttons that float over WhatsApp while a shipment is being sent: "next" and "stop".
 * Either one brings the app back to the front and closes WhatsApp's screens on the way, so WhatsApp
 * reports whether the photo was really sent. After "next" the page opens the next photo at once
 * (only if it was sent); after "stop" it waits. They can be dragged together; the place is remembered.
 * Needs the "display over other apps" permission, which the person grants once in Settings.
 */
final class NextBubble {

    static final int NONE = 0, NEXT = 1, STOP = 2;

    private static final long MAX_SHOWN_MS = 10 * 60 * 1000;   // never linger over other apps

    private final Context app;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout view;
    private TextView next, stop;
    private WindowManager.LayoutParams lp;
    private String label = "הבא ‹";
    private volatile int tapped = NONE;

    NextBubble(Context context) {
        this.app = context.getApplicationContext();
        this.wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = app.getSharedPreferences("bubble", Context.MODE_PRIVATE);
    }

    static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    void setLabel(String text) {
        if (text != null && !text.trim().isEmpty()) label = text.trim();
        if (next != null) {
            next.setText(label);
            stop.setVisibility(isLast() ? View.GONE : View.VISIBLE);
        }
    }

    /** On the last photo the button reads "סיום ✓" and there is nothing to stop. */
    private boolean isLast() {
        return label.startsWith("סיום");
    }

    /** Which button brought the person back, once: NEXT, STOP, or NONE (they used Back). */
    int consumeTapped() {
        int t = tapped;
        tapped = NONE;
        return t;
    }

    boolean isShowing() {
        return view != null;
    }

    @SuppressWarnings("deprecation")
    void show() {
        if (view != null || !allowed(app)) return;
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        next = pill(label, Color.rgb(14, 122, 68), 18, 18);
        next.setContentDescription("נשלח. חזרה לאפליקציה ופתיחת התמונה הבאה");
        stop = pill("עצור", Color.rgb(48, 48, 48), 15, 14);
        stop.setContentDescription("עצירה: חזרה לאפליקציה בלי לפתוח את התמונה הבאה");
        stop.setVisibility(isLast() ? View.GONE : View.VISIBLE);
        view = new LinearLayout(app);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);   // "next" on the right, "stop" on its left
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.setMarginStart(dp(10));
        view.addView(next);
        view.addView(stop, gap);

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // Default: low on the left, near where WhatsApp's send button sits in a Hebrew layout.
        lp.x = prefs.getInt("x", dp(16));
        lp.y = prefs.getInt("y", dm.heightPixels - dp(230));

        next.setOnTouchListener(dragOrTap(NEXT));
        stop.setOnTouchListener(dragOrTap(STOP));
        try {
            wm.addView(view, lp);
        } catch (RuntimeException e) {
            view = null;
            next = stop = null;
            return;
        }
        main.removeCallbacksAndMessages(null);
        main.postDelayed(this::hide, MAX_SHOWN_MS);
    }

    private TextView pill(String text, int color, int sp, int padH) {
        TextView t = new TextView(app);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setPadding(dp(padH), dp(12), dp(padH), dp(12));
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(28));
        bg.setStroke(dp(2), Color.WHITE);
        t.setBackground(bg);
        t.setElevation(dp(8));
        return t;
    }

    /** A touch on either button drags both; a tap without moving is a press of that button. */
    @SuppressLint("ClickableViewAccessibility")
    private View.OnTouchListener dragOrTap(final int which) {
        final int slop = ViewConfiguration.get(app).getScaledTouchSlop();
        return new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!moved && Math.hypot(dx, dy) > slop) moved = true;
                        if (moved) {
                            lp.x = Math.max(0, Math.round(startX + dx));
                            lp.y = Math.max(0, Math.round(startY + dy));
                            try { wm.updateViewLayout(view, lp); } catch (RuntimeException ignored) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                        else backToApp(which);
                        return true;
                    default:
                        return false;
                }
            }
        };
    }

    void hide() {
        main.removeCallbacksAndMessages(null);
        if (view == null) return;
        try { wm.removeView(view); } catch (RuntimeException ignored) { }
        view = null;
        next = stop = null;
    }

    /**
     * WhatsApp runs in this app's task, above it. CLEAR_TOP closes WhatsApp's screens there, so a photo
     * that wasn't sent yet comes back as "canceled" instead of staying open under the next one.
     * SINGLE_TOP keeps the app's own screen as it is (no reload).
     */
    private void backToApp(int which) {
        tapped = which;
        hide();
        Intent i = new Intent(app, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { app.startActivity(i); } catch (RuntimeException ignored) { tapped = NONE; }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, app.getResources().getDisplayMetrics()));
    }
}
