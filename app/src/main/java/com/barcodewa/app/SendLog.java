package com.barcodewa.app;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Knows when a photo was sent, and keeps a short log of it (shown on the send screen).
 *
 * Found on 20.11 (WhatsApp Business, app 1.6 test): when WhatsApp opens a shared photo it asks about
 * the file and opens it twice within half a second (it makes its own copy), then leaves it alone.
 * When "send" is pressed it asks about the file once more; when nothing is sent, it doesn't.
 * So: a request for the photo after those two opens, following a quiet moment, means "sent".
 * If WhatsApp ever behaves differently the signal is simply missed, and the person confirms by hand.
 *
 * The log is in memory only, for the last 3 photos, in milliseconds since WhatsApp was opened.
 */
final class SendLog {

    private static final int KEEP_SENDS = 3;
    private static final SendSignal signal = new SendSignal();
    private static String file = "";
    private static Runnable onSent, onOpen;
    private static int opens = 0;          // WhatsApp opened the current photo (0 = it hasn't loaded it)
    private static boolean asked = false;  // WhatsApp asked anything about the current photo
    private static final List<Object[]> entries = new ArrayList<>();   // {send number, ms, event, detail}
    private static int sendNo = 0;
    private static long t0 = 0;

    private SendLog() { }

    static synchronized void start(String code, String fileName) {
        sendNo++;
        t0 = SystemClock.elapsedRealtime();
        file = fileName == null ? "" : fileName;
        signal.reset();
        opens = 0;
        asked = false;
        entries.removeIf(e -> (int) e[0] <= sendNo - KEEP_SENDS);
        entries.add(new Object[]{sendNo, 0L, "start", code});
    }

    static void setOnSent(Runnable r) {
        onSent = r;
    }

    /** Called when WhatsApp opens the current photo, i.e. its send screen is loading it. */
    static void setOnOpen(Runnable r) {
        onOpen = r;
    }

    static synchronized int current() {
        return sendNo;
    }

    static synchronized int opens() {
        return opens;
    }

    static synchronized boolean asked() {
        return asked;
    }

    /** True once WhatsApp has shown the "send" signal for the current photo. */
    static synchronized boolean sent() {
        return signal.sent();
    }

    /** WhatsApp asked about (type / query) or opened (open) a file this app shares. */
    static void access(String fileName, String event, String detail) {
        Runnable fire = null, opened = null;
        synchronized (SendLog.class) {
            add(event, detail);
            if (sendNo == 0 || !file.equals(fileName)) return;
            asked = true;
            if ("open".equals(event)) { opens++; opened = onOpen; }
            if (signal.onAccess(event, SystemClock.elapsedRealtime() - t0)) {
                add("sent", "");
                fire = onSent;
            }
        }
        if (opened != null) opened.run();
        if (fire != null) fire.run();
    }

    static synchronized void add(String event, String detail) {
        if (sendNo == 0) return;
        if (entries.size() > 400) entries.remove(0);
        entries.add(new Object[]{sendNo, SystemClock.elapsedRealtime() - t0, event, detail == null ? "" : detail});
    }

    static synchronized String json() {
        JSONArray a = new JSONArray();
        try {
            for (Object[] e : entries) {
                JSONObject o = new JSONObject();
                o.put("n", e[0]);
                o.put("t", e[1]);
                o.put("e", e[2]);
                o.put("d", e[3]);
                a.put(o);
            }
        } catch (Exception ignored) { }
        return a.toString();
    }
}
