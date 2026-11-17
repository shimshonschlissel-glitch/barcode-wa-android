package com.barcodewa.app;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A thin shell around the web app, which is bundled in assets/www and served from a private https origin.
 * The page talks to it through the "BarcodeWA" bridge for the things a web page cannot do:
 * open WhatsApp straight in one chat with a photo and caption, pick a contact, share an export file.
 */
public class MainActivity extends ComponentActivity {

    static final String HOST = "appassets.androidplatform.net";
    static final String START = "https://" + HOST + "/index.html";
    static final List<String> WHATSAPP = Arrays.asList("com.whatsapp", "com.whatsapp.w4b");

    private static final int REQ_CONTACT = 1;
    private static final int REQ_SEND = 2;
    private static final int REQ_FILE = 3;
    private static final int REQ_CAMERA = 4;
    private static final int REQ_SCAN = 5;

    /** How the last native scan went, for the page's diagnostics line. */
    static volatile String lastScanInfo = "";

    private WebView web;
    private ScanOverlay scanOverlay;
    private NextBubble bubble;
    private int pendingScanCount = 0;
    private String pendingScanTitle = "";
    private PermissionRequest pendingCamera;
    private ValueCallback<Uri[]> fileCallback;
    private boolean waitingWhatsApp = false;
    private int whatsAppResult = RESULT_OK;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(238, 241, 236));
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        keepClearOfSystemBars(root);
        setContentView(root);
        bubble = new NextBubble(this);
        scanOverlay = new ScanOverlay(this, root, new ScanOverlay.Listener() {
            @Override
            public void onCode(String code, String format, String fileName) {
                js("window.__bcwaNative && window.__bcwaNative.scanItem(" + JSONObject.quote(code) + "," + JSONObject.quote(format)
                        + "," + JSONObject.quote("https://" + HOST + "/__scan/" + fileName) + ")");
            }

            @Override
            public void onClosed() {
                js("window.__bcwaNative && window.__bcwaNative.scanDone('')");
            }
        });
        darkSystemBarIcons();

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "BarcodeWA");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.loadUrl(START);
    }

    // ---------- layout: keep the page out from under the status and navigation bars ----------

    private void keepClearOfSystemBars(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }

    @SuppressWarnings("deprecation")
    private void darkSystemBarIcons() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light, light);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        if (Build.VERSION.SDK_INT < 35) {
            getWindow().setStatusBarColor(Color.rgb(238, 241, 236));
            getWindow().setNavigationBarColor(Color.rgb(238, 241, 236));
        }
    }

    // ---------- serving the bundled web app ----------

    private class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath();
            if (path == null || path.equals("/")) path = "/index.html";
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");
            if (path.contains("..")) return notFound(headers);
            if (path.startsWith("/__scan/")) {
                try {
                    File f = new File(new File(getCacheDir(), "scans"), path.substring("/__scan/".length()));
                    return new WebResourceResponse("image/jpeg", null, 200, "OK", headers, new java.io.FileInputStream(f));
                } catch (IOException e) {
                    return notFound(headers);
                }
            }
            try {
                InputStream in = getAssets().open("www" + path);
                String mime = mimeFor(path);
                String enc = mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json") ? "utf-8" : null;
                return new WebResourceResponse(mime, enc, 200, "OK", headers, in);
            } catch (IOException e) {
                return notFound(headers);
            }
        }

        private WebResourceResponse notFound(Map<String, String> headers) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers, new ByteArrayInputStream(new byte[0]));
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (HOST.equals(u.getHost())) return false;
            // Anything else (a link to a web page) opens in the browser, never inside the app.
            try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (ActivityNotFoundException ignored) { }
            return true;
        }
    }

    static String mimeFor(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".wasm")) return "application/wasm";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".webmanifest")) return "application/manifest+json";
        return "application/octet-stream";
    }

    // ---------- camera permission and file picking for the page ----------

    private class Chrome extends WebChromeClient {
        @Override
        public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> {
                if (!Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                    request.deny();
                    return;
                }
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    if (pendingCamera != null) pendingCamera.deny();
                    pendingCamera = request;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                }
            });
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(i, REQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                callback.onReceiveValue(null);
            }
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_SCAN) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) scanOverlay.show(pendingScanCount, pendingScanTitle);
            else js("window.__bcwaNative && window.__bcwaNative.scanDone('denied')");
            return;
        }
        if (req != REQ_CAMERA || pendingCamera == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            pendingCamera.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
        } else {
            pendingCamera.deny();
        }
        pendingCamera = null;
    }

    // ---------- results from the contact picker, WhatsApp and the file picker ----------

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SEND) {
            whatsAppResult = res;
        } else if (req == REQ_CONTACT) {
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            String[] cols = {ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME};
            try (Cursor c = getContentResolver().query(data.getData(), cols, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    js("window.__bcwaNative && window.__bcwaNative.contact(" + JSONObject.quote(String.valueOf(c.getString(1)))
                            + "," + JSONObject.quote(String.valueOf(c.getString(0))) + ")");
                }
            } catch (Exception ignored) { }
        } else if (req == REQ_FILE) {
            if (fileCallback == null) return;
            Uri[] picked = null;
            if (res == RESULT_OK && data != null && data.getData() != null) picked = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(picked);
            fileCallback = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        bubble.hide();
        boolean viaBubble = bubble.consumeTapped();
        if (waitingWhatsApp) {
            waitingWhatsApp = false;
            js("window.__bcwaNative && window.__bcwaNative.returned(" + whatsAppResult + "," + viaBubble + ")");
        }
    }

    @Override
    protected void onDestroy() {
        if (bubble != null) bubble.hide();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (scanOverlay != null && scanOverlay.isShowing()) { scanOverlay.close(); return; }
        // Close an open dialog first, then step back through the app's screens, then leave.
        web.evaluateJavascript("(function(){var d=document.querySelector('dialog[open]');if(d){d.close();return 1}return 0})()", v -> {
            if ("1".equals(v)) return;
            if (web.canGoBack()) web.goBack();
            else finish();
        });
    }

    private void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private File sharedFile(String name) throws IOException {
        File dir = new File(getCacheDir(), "shared");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdirs");
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "-");
        if (safe.isEmpty() || safe.startsWith(".")) safe = "file" + safe;
        return new File(dir, safe);
    }

    private static void clearOld(File dir, String keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long limit = System.currentTimeMillis() - 10 * 60 * 1000;
        for (File f : files) if (!f.getName().equals(keep) && f.lastModified() < limit) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    // ---------- the bridge the page calls ----------

    private class Bridge {
        @JavascriptInterface
        public String info() {
            try {
                JSONObject o = new JSONObject();
                o.put("version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
                JSONArray apps = new JSONArray();
                for (String p : WHATSAPP) {
                    try { getPackageManager().getPackageInfo(p, 0); apps.put(p); } catch (PackageManager.NameNotFoundException ignored) { }
                }
                o.put("apps", apps);
                return o.toString();
            } catch (Exception e) {
                return "{\"apps\":[]}";
            }
        }

        @JavascriptInterface
        public void pickContact() {
            runOnUiThread(() -> {
                try {
                    startActivityForResult(new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_CONTACT);
                } catch (ActivityNotFoundException ignored) { }
            });
        }

        /** Opens WhatsApp in the chat of `phone` with one photo and its caption. Returns "" or an error to show. */
        @JavascriptInterface
        public String send(String pkg, String phone, String caption, String fileName, String b64) {
            if (!WHATSAPP.contains(pkg)) return "וואטסאפ לא נמצא בטלפון.";
            if (phone == null || !phone.matches("[0-9]{9,15}")) return "מספר הנמען לא תקין.";
            Uri uri;
            try {
                File f = sharedFile(fileName);
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes); }
                clearOld(f.getParentFile(), f.getName());
                uri = Uri.parse("content://" + FilesProvider.AUTHORITY + "/shared/" + f.getName());
            } catch (Exception e) {
                return "לא הצלחתי להכין את התמונה לשליחה.";
            }
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("image/jpeg");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.putExtra(Intent.EXTRA_TEXT, caption);
            i.setClipData(ClipData.newRawUri(caption, uri));
            i.setPackage(pkg);
            i.putExtra("jid", phone + "@s.whatsapp.net");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            final boolean floating = NextBubble.allowed(MainActivity.this);
            runOnUiThread(() -> {
                try {
                    waitingWhatsApp = true;
                    whatsAppResult = RESULT_OK;
                    if (floating) {
                        // WhatsApp opens in its own task, so the "next" bubble can bring this app back
                        // without WhatsApp's screens piling up under it.
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        bubble.show();
                        startActivity(i);
                    } else {
                        startActivityForResult(i, REQ_SEND);
                    }
                } catch (ActivityNotFoundException e) {
                    waitingWhatsApp = false;
                    bubble.hide();
                    js("window.__bcwaNative && window.__bcwaNative.returned(0,false)");
                }
            });
            return "";
        }

        /** Hands a text file (an export) to the system share sheet: Drive, email, WhatsApp... */
        @JavascriptInterface
        public void share(String name, String mime, String content) {
            try {
                File f = sharedFile(name);
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(content.getBytes(StandardCharsets.UTF_8)); }
                Uri uri = Uri.parse("content://" + FilesProvider.AUTHORITY + "/shared/" + f.getName());
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.setClipData(ClipData.newRawUri(f.getName(), uri));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(i, f.getName());
                runOnUiThread(() -> {
                    try { startActivity(chooser); } catch (ActivityNotFoundException ignored) { }
                });
            } catch (IOException ignored) { }
        }

        /** Opens the app's own scanner over the page. Codes come back through window.__bcwaNative.scanItem. */
        @JavascriptInterface
        public void startScan(String count, String title) {
            runOnUiThread(() -> {
                int n;
                try { n = Integer.parseInt(count); } catch (NumberFormatException e) { n = 0; }
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    scanOverlay.show(n, title);
                } else {
                    pendingScanCount = n;
                    pendingScanTitle = title;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_SCAN);
                }
            });
        }

        /** The page's answer about the last scanned code: ok, warn, dup or error. */
        @JavascriptInterface
        public void scanResult(String status, String text, int count) {
            runOnUiThread(() -> scanOverlay.onResult(status, text, count));
        }

        /** Whether the floating "next" button may be shown over WhatsApp. */
        @JavascriptInterface
        public boolean overlayAllowed() {
            return NextBubble.allowed(MainActivity.this);
        }

        /** Opens the system screen where the person allows "display over other apps". */
        @JavascriptInterface
        public void requestOverlay() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException e) {
                    try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); } catch (ActivityNotFoundException ignored) { }
                }
            });
        }

        /** Text on the floating button for the photo about to be sent, e.g. "הבא ‹ 4/10". */
        @JavascriptInterface
        public void setBubbleLabel(String text) {
            runOnUiThread(() -> bubble.setLabel(text));
        }

        @JavascriptInterface
        public String scanStats() {
            return lastScanInfo;
        }

        @JavascriptInterface
        public void keepScreenOn(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }
    }
}
