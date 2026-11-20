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
 * Both bring the app back. "Next" before the photo was sent (see SendLog) shows a card over WhatsApp
 * instead: send now / already sent / skip / stop. With auto-return on, the app comes back by itself
 * a moment after "send" (autoBack). They can be dragged together; the place is remembered.
 * Needs the "display over other apps" permission, which the person grants once in Settings.
 */
final class NextBubble {

    static final int NONE = 0, NEXT = 1, STOP = 2, SKIP = 3, AUTO = 4, CONFIRMED = 5;

    /** Asked when "next" is tapped: is WhatsApp's send screen still open (the photo not sent yet)? */
    interface StillOpen {
        boolean check();
    }

    private static final long MAX_SHOWN_MS = 10 * 60 * 1000;   // never linger over other apps

    private final Context app;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout view, prompt;
    private TextView hint;
    private StillOpen stillOpen;
    private TextView next, stop;
    private WindowManager.LayoutParams lp;
    private String label = "הבא ‹";
    private boolean sentMark = false;      // the photo went: show ✓ on "next"
    private boolean autoReturn = true;
    private volatile int tapped = NONE;

    NextBubble(Context context) {
        this.app = context.getApplicationContext();
        this.wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = app.getSharedPreferences("bubble", Context.MODE_PRIVATE);
    }

    void setStillOpen(StillOpen s) {
        stillOpen = s;
    }

    static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    /** Called for every photo before WhatsApp opens. */
    void setLabel(String text) {
        if (text != null && !text.trim().isEmpty()) label = text.trim();
        sentMark = false;
        if (next != null) {
            next.setText(nextText());
            showFor();
        }
    }

    void setAutoReturn(boolean on) {
        autoReturn = on;
        if (next != null) showFor();
    }

    /** With auto-return the app comes back by itself, so only "stop" floats; otherwise "next" and "stop". */
    private void showFor() {
        next.setVisibility(autoReturn ? View.GONE : View.VISIBLE);
        stop.setVisibility(autoReturn || !isLast() ? View.VISIBLE : View.GONE);
    }

    /** A note across the top of WhatsApp that doesn't take touches (e.g. "choose the recipient"). */
    @SuppressWarnings("deprecation")
    void showHint(String text) {
        if (!allowed(app)) return;
        if (hint != null) { hint.setText(text); return; }
        hint = text(text, 16, true, Color.WHITE);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 14, 122, 68));
        bg.setCornerRadius(dp(16));
        hint.setBackground(bg);
        hint.setElevation(dp(8));
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        WindowManager.LayoutParams hlp = new WindowManager.LayoutParams(Math.min(dm.widthPixels - dp(32), dp(420)), WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        hlp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hlp.y = dp(120);
        try { wm.addView(hint, hlp); } catch (RuntimeException e) { hint = null; }
    }

    void hideHint() {
        if (hint == null) return;
        try { wm.removeView(hint); } catch (RuntimeException ignored) { }
        hint = null;
    }

    private String nextText() {
        return sentMark ? "✓ " + label : label;
    }

    /** The photo went and the app isn't coming back by itself: mark "next" with ✓ (and close the card if it's up). */
    void markSent() {
        sentMark = true;
        if (prompt != null) { removePrompt(); show(); }
        else if (next != null) next.setText(nextText());
    }

    /** The photo went: come back to the app by itself. */
    void autoBack() {
        SendLog.add("tap", "auto");
        backToApp(AUTO);
    }

    boolean isUp() {
        return view != null || prompt != null;
    }

    boolean hintShowing() {
        return hint != null;
    }

    /** On the last photo the button reads "סיום ✓" and there is nothing to stop. */
    private boolean isLast() {
        return label.startsWith("סיום");
    }

    /** What brought the person back, once: NEXT, STOP, SKIP, CONFIRMED, AUTO, or NONE (they used Back). */
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
        next = pill(nextText(), Color.rgb(14, 122, 68), 18, 18);
        next.setContentDescription("נשלח. חזרה לאפליקציה ופתיחת התמונה הבאה");
        stop = pill("עצור", Color.rgb(48, 48, 48), 15, 14);
        stop.setContentDescription("עצירה: חזרה לאפליקציה בלי לפתוח את התמונה הבאה");
        view = new LinearLayout(app);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);   // "next" on the right, "stop" on its left
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.setMarginStart(dp(10));
        view.addView(next);
        view.addView(stop, gap);
        showFor();

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
                        else onTap(which);
                        return true;
                    default:
                        return false;
                }
            }
        };
    }

    void hide() {
        main.removeCallbacksAndMessages(null);
        removePrompt();
        removeButtons();
        hideHint();
    }

    private void removeButtons() {
        if (view == null) return;
        try { wm.removeView(view); } catch (RuntimeException ignored) { }
        view = null;
        next = stop = null;
    }

    private void removePrompt() {
        if (prompt == null) return;
        try { wm.removeView(prompt); } catch (RuntimeException ignored) { }
        prompt = null;
    }

    private void onTap(int which) {
        SendLog.add("tap", which == NEXT ? "next" : which == STOP ? "stop" : "skip");
        if (which == NEXT && stillOpen != null && stillOpen.check()) showPrompt();
        else backToApp(which);
    }

    /**
     * "Next" was tapped but the photo wasn't sent (no signal yet): ask over WhatsApp instead of going back.
     * Sending in WhatsApp meanwhile closes the card (markSent / autoBack).
     */
    private void showPrompt() {
        removeButtons();
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        prompt = new LinearLayout(app);
        prompt.setOrientation(LinearLayout.VERTICAL);
        prompt.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        prompt.setPadding(dp(20), dp(18), dp(20), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), Color.rgb(210, 210, 210));
        prompt.setBackground(bg);
        prompt.setElevation(dp(12));

        TextView title = text("התמונה עוד לא נשלחה", 19, true, Color.rgb(26, 26, 26));
        TextView body = text(autoReturn ? "לוחצים \"שלח\" בוואטסאפ, והאפליקציה תחזור לבד. או:" : "לוחצים \"שלח\" בוואטסאפ, ואז \"הבא\". או:",
                15, false, Color.rgb(80, 80, 80));
        body.setPadding(0, dp(4), 0, dp(14));
        TextView send = choice("אשלח עכשיו", Color.rgb(14, 122, 68), Color.WHITE);
        TextView done = choice("כבר שלחתי", Color.rgb(232, 232, 232), Color.rgb(26, 26, 26));
        TextView skip = choice("דלג עליה", Color.rgb(232, 232, 232), Color.rgb(26, 26, 26));
        TextView halt = choice("עצור", Color.rgb(48, 48, 48), Color.WHITE);
        LinearLayout row = new LinearLayout(app);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half2.setMarginStart(dp(10));
        row.addView(done, half);
        row.addView(skip, half2);
        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams fullGap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fullGap.topMargin = dp(10);
        LinearLayout.LayoutParams fullGap2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fullGap2.topMargin = dp(10);
        prompt.addView(title);
        prompt.addView(body);
        prompt.addView(send, full);
        prompt.addView(row, fullGap);
        prompt.addView(halt, fullGap2);

        send.setOnClickListener(v -> { SendLog.add("tap", "dismiss"); removePrompt(); show(); });
        done.setOnClickListener(v -> { SendLog.add("tap", "confirm"); backToApp(CONFIRMED); });
        skip.setOnClickListener(v -> { SendLog.add("tap", "skip"); backToApp(SKIP); });
        halt.setOnClickListener(v -> { SendLog.add("tap", "stop"); backToApp(STOP); });

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams plp = new WindowManager.LayoutParams(Math.min(dm.widthPixels - dp(32), dp(420)),
                WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        plp.gravity = Gravity.CENTER;
        try {
            wm.addView(prompt, plp);
        } catch (RuntimeException e) {
            prompt = null;
            backToApp(NEXT);
        }
    }

    private TextView text(String s, int sp, boolean bold, int color) {
        TextView t = new TextView(app);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView choice(String s, int bgColor, int fg) {
        TextView t = text(s, 16, true, fg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(13), dp(12), dp(13));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(24));
        t.setBackground(bg);
        return t;
    }

    /**
     * Brings the app's own task to the front; SINGLE_TOP keeps its screen as it is (no reload).
     * The activity is started while the floating window is still up: since Android 15 an app may open
     * a screen from the background only while one of its overlay windows is visible.
     */
    private void backToApp(int which) {
        tapped = which;
        Intent i = new Intent(app, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { app.startActivity(i); } catch (RuntimeException ignored) { tapped = NONE; }
        hide();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, app.getResources().getDisplayMetrics()));
    }
}
