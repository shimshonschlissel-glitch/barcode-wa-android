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
import android.widget.TextView;

/**
 * A small "next" button that floats over WhatsApp while a shipment is being sent.
 * Tapping it brings the app back to the front (the page then opens the next photo at once),
 * instead of pressing Back twice. It can be dragged; its place is remembered.
 * Needs the "display over other apps" permission, which the person grants once in Settings.
 */
final class NextBubble {

    private static final long MAX_SHOWN_MS = 10 * 60 * 1000;   // never linger over other apps

    private final Context app;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView view;
    private WindowManager.LayoutParams lp;
    private String label = "הבא ‹";
    private volatile boolean tapped = false;

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
        if (view != null) view.setText(label);
    }

    /** True once if the person came back by tapping the bubble (not with Back). */
    boolean consumeTapped() {
        boolean t = tapped;
        tapped = false;
        return t;
    }

    boolean isShowing() {
        return view != null;
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("ClickableViewAccessibility")
    void show() {
        if (view != null || !allowed(app)) return;
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        int pad = dp(18);
        view = new TextView(app);
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        view.setPadding(pad, dp(12), pad, dp(12));
        view.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(14, 122, 68));
        bg.setCornerRadius(dp(28));
        bg.setStroke(dp(2), Color.WHITE);
        view.setBackground(bg);
        view.setElevation(dp(8));
        view.setContentDescription("חזרה לאפליקציה ופתיחת התמונה הבאה");

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // Default: low on the left, near where WhatsApp's send button sits in a Hebrew layout.
        lp.x = prefs.getInt("x", dp(16));
        lp.y = prefs.getInt("y", dm.heightPixels - dp(230));

        final int slop = ViewConfiguration.get(app).getScaledTouchSlop();
        view.setOnTouchListener(new android.view.View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(android.view.View v, MotionEvent e) {
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
                        else backToApp();
                        return true;
                    default:
                        return false;
                }
            }
        });
        try {
            wm.addView(view, lp);
        } catch (RuntimeException e) {
            view = null;
            return;
        }
        main.removeCallbacksAndMessages(null);
        main.postDelayed(this::hide, MAX_SHOWN_MS);
    }

    void hide() {
        main.removeCallbacksAndMessages(null);
        if (view == null) return;
        try { wm.removeView(view); } catch (RuntimeException ignored) { }
        view = null;
    }

    private void backToApp() {
        tapped = true;
        hide();
        Intent i = new Intent(app, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { app.startActivity(i); } catch (RuntimeException ignored) { tapped = false; }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, app.getResources().getDisplayMetrics()));
    }
}
