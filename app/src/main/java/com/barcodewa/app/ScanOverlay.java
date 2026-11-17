package com.barcodewa.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.Image;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.OptIn;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The app's own scanning screen, drawn over the web page: CameraX preview, ML Kit barcode reading, an aiming box.
 * Each accepted code is saved as a JPEG of that video frame and handed to the page, which stores it and answers
 * with ok / dup / warn / error through {@link #onResult}. The page keeps all the rules about shipments and duplicates.
 */
final class ScanOverlay {

    interface Listener {
        /** A code was accepted. `fileName` is in {@link #dir}. */
        void onCode(String code, String format, String fileName);

        /** The person closed the scanner. */
        void onClosed();
    }

    static final float AIM_FX = 0.86f, AIM_FY = 0.34f;
    private static final int BG = Color.rgb(11, 15, 13);
    private static final int FG = Color.rgb(241, 244, 241);
    private static final int MUTED = Color.rgb(180, 192, 184);
    private static final int OK = Color.rgb(45, 181, 108);
    private static final int WARN = Color.rgb(255, 176, 32);
    private static final int LASER = Color.rgb(255, 91, 69);

    private final ComponentActivity act;
    private final FrameLayout parent;
    private final Listener listener;
    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScanLogic logic = new ScanLogic();
    private final BarcodeScanner scanner;

    private View root;
    private PreviewView preview;
    private AimView aim;
    private TextView counter, last, message, torchBtn;
    private ProcessCameraProvider provider;
    private Camera camera;
    private boolean torchOn = false;
    private volatile boolean active = false;
    private volatile int viewW = 1, viewH = 1;
    private volatile String manualCode = null;   // set by "type the number": the next frame becomes its photo
    private ToneGenerator tone;
    private int frames = 0;
    private long since;

    ScanOverlay(ComponentActivity act, FrameLayout parent, Listener listener) {
        this.act = act;
        this.parent = parent;
        this.listener = listener;
        this.dir = new File(act.getCacheDir(), "scans");
        BarcodeScannerOptions opts = new BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93, Barcode.FORMAT_ITF,
                Barcode.FORMAT_CODABAR, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_PDF417).build();
        scanner = BarcodeScanning.getClient(opts);
    }

    boolean isShowing() {
        return root != null;
    }

    // ---------- opening and closing ----------

    void show(int count, String title) {
        if (root != null) return;
        cleanOld();
        logic.reset();
        frames = 0;
        since = SystemClock.elapsedRealtime();
        root = build(title);
        parent.addView(root, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        counter.setText(String.valueOf(count));
        act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70); } catch (RuntimeException e) { tone = null; }
        active = true;
        startCamera();
    }

    void close() {
        if (root == null) return;
        active = false;
        if (provider != null) provider.unbindAll();
        camera = null;
        torchOn = false;
        parent.removeView(root);
        root = null;
        act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (tone != null) { tone.release(); tone = null; }
        listener.onClosed();
    }

    /** The page's answer about the last code handed over. */
    void onResult(String status, String text, int count) {
        if (root == null) return;
        counter.setText(String.valueOf(count));
        boolean added = "ok".equals(status) || "warn".equals(status);
        message.setText(text);
        message.setTextColor("ok".equals(status) ? Color.rgb(143, 224, 176) : "dup".equals(status) || "warn".equals(status) ? Color.rgb(255, 214, 140) : FG);
        if (added) {
            int i = text.indexOf(": ");
            if ("ok".equals(status) && i > 0) last.setText(text.substring(i + 2));
            aim.flash(OK);
            beep(ToneGenerator.TONE_PROP_BEEP, 90);
            buzz(new long[]{0, 60});
        } else if ("dup".equals(status)) {
            aim.flash(WARN);
            beep(ToneGenerator.TONE_PROP_NACK, 140);
            buzz(new long[]{0, 40, 60, 40});
        }
    }

    // ---------- camera ----------

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(act);
        f.addListener(() -> {
            if (!active) return;
            try {
                provider = f.get();
                Preview pv = new Preview.Builder().build();
                pv.setSurfaceProvider(preview.getSurfaceProvider());
                ResolutionSelector res = new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(new Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(res)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(worker, this::analyze);
                provider.unbindAll();
                camera = provider.bindToLifecycle(act, CameraSelector.DEFAULT_BACK_CAMERA, pv, analysis);
                torchBtn.setVisibility(camera.getCameraInfo().hasFlashUnit() ? View.VISIBLE : View.GONE);
                message.setText("כוון את הברקוד הנכון למרכז המסגרת.");
            } catch (Exception e) {
                message.setText("המצלמה לא נפתחה. סגור ונסה שוב.");
            }
        }, ContextCompat.getMainExecutor(act));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyze(ImageProxy proxy) {
        Image media = proxy.getImage();
        if (!active || media == null) { proxy.close(); return; }
        final int rot = proxy.getImageInfo().getRotationDegrees();
        final boolean turned = rot % 180 != 0;
        final float imgW = turned ? proxy.getHeight() : proxy.getWidth();
        final float imgH = turned ? proxy.getWidth() : proxy.getHeight();

        String typed = manualCode;
        if (typed != null) {
            manualCode = null;
            String file = savePhoto(proxy, rot);
            proxy.close();
            if (file != null) main.post(() -> listener.onCode(typed, "ידני", file));
            return;
        }

        InputImage input = InputImage.fromMediaImage(media, rot);
        scanner.process(input)
                .addOnSuccessListener(worker, codes -> {
                    frames++;
                    List<ScanLogic.Hit> hits = new ArrayList<>();
                    for (Barcode b : codes) {
                        android.graphics.Rect bb = b.getBoundingBox();
                        if (bb == null || b.getRawValue() == null) continue;
                        hits.add(new ScanLogic.Hit(b.getRawValue(), formatName(b.getFormat()), bb.exactCenterX(), bb.exactCenterY()));
                    }
                    ScanLogic.Rect box = ScanLogic.aimRect(imgW, imgH, viewW, viewH, AIM_FX, AIM_FY);
                    ScanLogic.Hit got = logic.offer(hits, box, SystemClock.elapsedRealtime());
                    if (got == null) {
                        if (logic.confirming()) main.post(() -> { if (root != null) message.setText("נקרא, מאמת…"); });
                        return;
                    }
                    String file = savePhoto(proxy, rot);
                    long now = SystemClock.elapsedRealtime();
                    long ms = now - since;
                    int n = frames;
                    since = now;
                    frames = 0;
                    if (file != null) main.post(() -> {
                        MainActivity.lastScanInfo = "ML Kit, " + n + " פריימים, " + ms + "ms מהקודם";
                        listener.onCode(got.value, got.format, file);
                    });
                })
                .addOnCompleteListener(worker, t -> proxy.close());
    }

    /** The frame, upright, as a JPEG in the scans folder. Returns its file name, or null. */
    private String savePhoto(ImageProxy proxy, int rot) {
        try {
            Bitmap bmp = proxy.toBitmap();
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (r != bmp) bmp.recycle();
                bmp = r;
            }
            if (!dir.exists() && !dir.mkdirs()) return null;
            String name = "s" + System.currentTimeMillis() + ".jpg";
            try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
            bmp.recycle();
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    private void cleanOld() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long limit = System.currentTimeMillis() - 60 * 60 * 1000;
        for (File f : files) if (f.lastModified() < limit) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    static String formatName(int f) {
        switch (f) {
            case Barcode.FORMAT_CODE_128: return "Code 128";
            case Barcode.FORMAT_CODE_39: return "Code 39";
            case Barcode.FORMAT_CODE_93: return "Code 93";
            case Barcode.FORMAT_ITF: return "ITF";
            case Barcode.FORMAT_CODABAR: return "Codabar";
            case Barcode.FORMAT_EAN_13: return "EAN-13";
            case Barcode.FORMAT_EAN_8: return "EAN-8";
            case Barcode.FORMAT_UPC_A: return "UPC-A";
            case Barcode.FORMAT_UPC_E: return "UPC-E";
            case Barcode.FORMAT_QR_CODE: return "QR";
            case Barcode.FORMAT_DATA_MATRIX: return "Data Matrix";
            case Barcode.FORMAT_PDF417: return "PDF417";
            case Barcode.FORMAT_AZTEC: return "Aztec";
            default: return "";
        }
    }

    // ---------- feedback ----------

    private void beep(int kind, int ms) {
        if (tone != null) try { tone.startTone(kind, ms); } catch (RuntimeException ignored) { }
    }

    @SuppressWarnings("deprecation")
    private void buzz(long[] pattern) {
        Vibrator v = (Vibrator) act.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        else v.vibrate(pattern, -1);
    }

    // ---------- layout ----------

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, act.getResources().getDisplayMetrics()));
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String label, boolean filled) {
        TextView b = new Button(act);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(filled ? BG : FG);
        b.setMinHeight(dp(52));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(14));
        if (filled) bg.setColor(FG);
        else { bg.setColor(Color.TRANSPARENT); bg.setStroke(dp(2), Color.rgb(58, 71, 64)); }
        b.setBackground(bg);
        b.setStateListAnimator(null);
        return b;
    }

    @SuppressLint("ClickableViewAccessibility")
    private View build(String title) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);
        col.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        col.setClickable(true);   // swallow touches meant for the page underneath

        TextView head = text(16, FG, true);
        head.setText(title == null || title.isEmpty() ? "סריקה" : "סריקה · " + title);
        head.setPadding(dp(16), dp(12), dp(16), dp(10));
        col.addView(head);

        FrameLayout cam = new FrameLayout(act);
        cam.setBackgroundColor(Color.BLACK);
        preview = new PreviewView(act);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        cam.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        aim = new AimView(act);
        cam.addView(aim, new FrameLayout.LayoutParams(-1, -1));
        cam.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { viewW = Math.max(1, r - l); viewH = Math.max(1, b - t); });
        int camH = Math.round(act.getResources().getDisplayMetrics().heightPixels * 0.55f);
        col.addView(cam, new LinearLayout.LayoutParams(-1, camH));

        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(16));

        LinearLayout stats = new LinearLayout(act);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        counter = text(34, FG, true);
        counter.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        stats.addView(counter);
        TextView lbl = text(16, MUTED, false);
        lbl.setText("  נסרקו");
        stats.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1f));
        last = text(15, MUTED, true);
        last.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        last.setTextDirection(View.TEXT_DIRECTION_LTR);
        stats.addView(last);
        body.addView(stats);

        message = text(15, FG, false);
        message.setText("פותח את המצלמה…");
        message.setPadding(0, dp(6), 0, dp(12));
        message.setMinLines(2);
        body.addView(message);

        TextView done = button("סיום", true);
        done.setOnClickListener(v -> close());
        body.addView(done, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        TextView manual = button("הקלד מספר", false);
        manual.setOnClickListener(v -> askManual());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1f);
        row.addView(manual, half);
        torchBtn = button("פנס", false);
        torchBtn.setVisibility(View.GONE);
        torchBtn.setOnClickListener(v -> {
            if (camera == null) return;
            torchOn = !torchOn;
            camera.getCameraControl().enableTorch(torchOn);
            torchBtn.setText(torchOn ? "כבה פנס" : "פנס");
        });
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, -2, 1f);
        half2.setMarginStart(dp(10));
        row.addView(torchBtn, half2);
        body.addView(row);

        col.addView(body, new LinearLayout.LayoutParams(-1, -2));
        return col;
    }

    private void askManual() {
        EditText input = new EditText(act);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        input.setTypeface(Typeface.MONOSPACE);
        input.setHint("המספר שמתחת לברקוד");
        FrameLayout box = new FrameLayout(act);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        new AlertDialog.Builder(act)
                .setTitle("הקלדת מספר")
                .setMessage("התמונה תילקח מהמצלמה עכשיו.")
                .setView(box)
                .setPositiveButton("הוסף", (d, w) -> {
                    String v = input.getText().toString().trim();
                    if (!v.isEmpty()) manualCode = v;
                })
                .setNegativeButton("ביטול", null)
                .show();
        input.requestFocus();
    }

    /** Dims everything outside the aiming box and draws its frame and a red line. */
    private static final class AimView extends View {
        private final Paint dim = new Paint(), frame = new Paint(Paint.ANTI_ALIAS_FLAG), laser = new Paint();
        private int color = Color.argb(235, 255, 255, 255);
        private final Handler h = new Handler(Looper.getMainLooper());

        AimView(Context c) {
            super(c);
            dim.setColor(Color.argb(107, 0, 0, 0));
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(3 * c.getResources().getDisplayMetrics().density);
            laser.setColor(LASER);
        }

        void flash(int c) {
            color = c;
            invalidate();
            h.removeCallbacksAndMessages(null);
            h.postDelayed(() -> { color = Color.argb(235, 255, 255, 255); invalidate(); }, 450);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), ht = getHeight();
            float bw = w * AIM_FX, bh = ht * AIM_FY, x = (w - bw) / 2f, y = (ht - bh) / 2f;
            float r = 10 * getResources().getDisplayMetrics().density;
            RectF box = new RectF(x, y, x + bw, y + bh);
            Path p = new Path();
            p.setFillType(Path.FillType.EVEN_ODD);
            p.addRect(0, 0, w, ht, Path.Direction.CW);
            p.addRoundRect(box, r, r, Path.Direction.CW);
            cv.drawPath(p, dim);
            frame.setColor(color);
            cv.drawRoundRect(box, r, r, frame);
            float ly = y + bh / 2f, lh = 2 * getResources().getDisplayMetrics().density;
            cv.drawRect(x + bw * 0.06f, ly - lh / 2, x + bw * 0.94f, ly + lh / 2, laser);
        }
    }
}
