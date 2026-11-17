package com.barcodewa.test;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * One-screen test app. It answers three questions on a real phone:
 *  A. Can WhatsApp be opened straight in one contact's chat with a photo and its caption?
 *  B. Does WhatsApp come back to this app by itself after "send"?
 *  C. Can several photos go in one share, each with its own caption?
 */
public class MainActivity extends Activity {

    private static final int REQ_CONTACT = 1;
    private static final int REQ_SEND = 2;
    private static final String[] CODES = {"TEST-001", "TEST-002", "TEST-003"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView contactView;
    private TextView logView;
    private Button singleButton;
    private CheckBox autoNext;
    private String phone;          // digits only, international form, e.g. 972501234567
    private String contactName;
    private int nextIndex = -1;    // next photo of test A, or -1 when idle
    private boolean waitingReturn = false;
    private long launchedAt;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences prefs = getSharedPreferences("test", MODE_PRIVATE);
        phone = prefs.getString("phone", null);
        contactName = prefs.getString("name", null);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int pad = dp(16);
        col.setPadding(pad, pad, pad, pad);

        TextView title = text("בדיקת שליחה לוואטסאפ", 22, true);
        col.addView(title);
        col.addView(text("הבדיקה שולחת 3 תמונות בדיקה עם הכיתובים TEST-001 עד TEST-003. כדאי לבחור את איש הקשר של עצמך, או מישהו שיודע שזו בדיקה.", 15, false));

        Button pick = button("1. בחר איש קשר");
        pick.setOnClickListener(v -> pickContact());
        col.addView(pick);
        contactView = text("", 15, true);
        col.addView(contactView);

        singleButton = button("");
        singleButton.setOnClickListener(v -> startOrContinueSingle());
        col.addView(singleButton);
        autoNext = new CheckBox(this);
        autoNext.setText("לפתוח לבד את התמונה הבאה כשחוזרים לאפליקציה");
        autoNext.setChecked(true);
        col.addView(autoNext);

        Button multi = button("3. בדיקה ג: 3 תמונות בבת אחת ישר לצ'אט, כיתוב לכל אחת");
        multi.setOnClickListener(v -> sendMultiple(true));
        col.addView(multi);

        Button multiShare = button("4. בדיקה ד: כמו 3, דרך מסך השיתוף הרגיל");
        multiShare.setOnClickListener(v -> sendMultiple(false));
        col.addView(multiShare);

        Button clear = button("נקה יומן");
        clear.setOnClickListener(v -> logView.setText(""));
        col.addView(clear);

        col.addView(text("יומן:", 15, true));
        logView = text("", 14, false);
        logView.setTextIsSelectable(true);
        col.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
        paint();
        log("גרסה " + versionName() + " · וואטסאפ: " + (whatsappPackage() == null ? "לא נמצא" : whatsappPackage()));
    }

    // ---------- contact ----------

    private void pickContact() {
        Intent i = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        try {
            startActivityForResult(i, REQ_CONTACT);
        } catch (ActivityNotFoundException e) {
            log("לא נמצא בוחר אנשי קשר בטלפון.");
        }
    }

    static String normalize(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("[^0-9]", "");
        if (d.startsWith("00")) d = d.substring(2);
        else if (d.startsWith("0")) d = "972" + d.substring(1);
        return d.length() >= 8 ? d : null;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CONTACT) {
            if (res != RESULT_OK || data == null || data.getData() == null) { log("לא נבחר איש קשר."); return; }
            String[] cols = {ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME};
            try (Cursor c = getContentResolver().query(data.getData(), cols, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String n = normalize(c.getString(0));
                    if (n == null) { log("המספר של איש הקשר לא נראה תקין: " + c.getString(0)); return; }
                    phone = n;
                    contactName = c.getString(1);
                    getSharedPreferences("test", MODE_PRIVATE).edit().putString("phone", phone).putString("name", contactName).apply();
                    log("נבחר: " + contactName + " (" + phone + ")");
                    nextIndex = -1;
                    paint();
                }
            } catch (Exception e) {
                log("לא הצלחתי לקרוא את איש הקשר: " + e.getClass().getSimpleName());
            }
        } else if (req == REQ_SEND) {
            log("וואטסאפ החזיר תשובה (קוד " + res + ").");
        }
    }

    // ---------- tests A and B: one photo at a time ----------

    private void startOrContinueSingle() {
        if (!ready()) return;
        if (nextIndex < 0 || nextIndex >= CODES.length) {
            nextIndex = 0;
            log("— בדיקה א+ב התחילה —");
        }
        sendOne();
    }

    private void sendOne() {
        if (nextIndex < 0 || nextIndex >= CODES.length) return;
        Uri uri = imageUri(nextIndex);
        if (uri == null) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("image/jpeg");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.putExtra(Intent.EXTRA_TEXT, CODES[nextIndex]);
        i.setClipData(ClipData.newRawUri(CODES[nextIndex], uri));
        i.setPackage(whatsappPackage());
        i.putExtra("jid", phone + "@s.whatsapp.net");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            waitingReturn = true;
            launchedAt = SystemClock.elapsedRealtime();
            log("נפתחה תמונה " + (nextIndex + 1) + " מתוך " + CODES.length + " (" + CODES[nextIndex] + ")");
            startActivityForResult(i, REQ_SEND);
        } catch (ActivityNotFoundException e) {
            waitingReturn = false;
            log("וואטסאפ לא נפתח: " + e.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!waitingReturn) return;
        waitingReturn = false;
        long secs = (SystemClock.elapsedRealtime() - launchedAt) / 1000;
        log("חזרת לאפליקציה אחרי " + secs + " שניות.");
        nextIndex++;
        if (nextIndex >= CODES.length) {
            log("— בדיקה א+ב הסתיימה. בדוק בצ'אט: 3 תמונות, וכיתוב נכון מתחת לכל אחת? —");
            nextIndex = -1;
        } else if (autoNext.isChecked()) {
            handler.postDelayed(this::sendOne, 700);
        }
        paint();
    }

    // ---------- tests C and D: all photos in one share ----------

    private void sendMultiple(boolean direct) {
        if (direct && !ready()) return;
        ArrayList<Uri> uris = new ArrayList<>();
        ArrayList<CharSequence> captions = new ArrayList<>();
        for (int n = 0; n < CODES.length; n++) {
            Uri u = imageUri(n);
            if (u == null) return;
            uris.add(u);
            captions.add(CODES[n]);
        }
        Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
        i.setType("image/jpeg");
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        i.putCharSequenceArrayListExtra(Intent.EXTRA_TEXT, captions);
        ClipData clip = ClipData.newRawUri(CODES[0], uris.get(0));
        for (int n = 1; n < uris.size(); n++) clip.addItem(new ClipData.Item(uris.get(n)));
        i.setClipData(clip);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            if (direct) {
                i.setPackage(whatsappPackage());
                i.putExtra("jid", phone + "@s.whatsapp.net");
                log("— בדיקה ג: נשלחו 3 תמונות בבת אחת ישר לצ'אט —");
                startActivity(i);
            } else {
                log("— בדיקה ד: 3 תמונות בבת אחת דרך מסך השיתוף —");
                startActivity(Intent.createChooser(i, "שליחת 3 תמונות בדיקה"));
            }
        } catch (ActivityNotFoundException e) {
            log("וואטסאפ לא נפתח: " + e.getMessage());
        }
    }

    // ---------- helpers ----------

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException e) { return "?"; }
    }

    private boolean ready() {
        if (whatsappPackage() == null) { log("וואטסאפ לא מותקן בטלפון."); return false; }
        if (phone == null) { log("קודם בחר איש קשר (כפתור 1)."); return false; }
        return true;
    }

    private String whatsappPackage() {
        PackageManager pm = getPackageManager();
        for (String p : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
            try { pm.getPackageInfo(p, 0); return p; } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return null;
    }

    /** A test photo: a white card with the caption in large letters, saved where FilesProvider serves it. */
    private Uri imageUri(int n) {
        try {
            File dir = new File(getCacheDir(), "shared");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdirs");
            File f = new File(dir, CODES[n] + ".jpg");
            if (!f.exists()) {
                Bitmap bmp = Bitmap.createBitmap(1080, 720, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(bmp);
                cv.drawColor(Color.rgb(238, 241, 236));
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.rgb(21, 33, 28));
                p.setTextAlign(Paint.Align.CENTER);
                p.setTypeface(Typeface.MONOSPACE);
                p.setTextSize(120);
                cv.drawText(CODES[n], 540, 380, p);
                p.setTypeface(Typeface.DEFAULT);
                p.setTextSize(48);
                cv.drawText("תמונת בדיקה " + (n + 1) + " מתוך " + CODES.length, 540, 500, p);
                try (FileOutputStream out = new FileOutputStream(f)) { bmp.compress(Bitmap.CompressFormat.JPEG, 88, out); }
                bmp.recycle();
            }
            return Uri.parse("content://" + FilesProvider.AUTHORITY + "/shared/" + f.getName());
        } catch (Exception e) {
            log("לא הצלחתי ליצור תמונת בדיקה: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private void paint() {
        contactView.setText(phone == null ? "לא נבחר איש קשר." : "איש קשר: " + contactName + " (" + phone + ")");
        if (nextIndex > 0 && nextIndex < CODES.length) singleButton.setText("2. המשך בדיקה א+ב: תמונה " + (nextIndex + 1) + " מתוך " + CODES.length);
        else singleButton.setText("2. בדיקה א+ב: 3 תמונות אחת־אחת ישר לצ'אט");
    }

    private void log(String line) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        if (logView != null) logView.append(t + "  " + line + "\n");
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
