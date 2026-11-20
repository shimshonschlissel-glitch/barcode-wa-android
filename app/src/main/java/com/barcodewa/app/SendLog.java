package com.barcodewa.app;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Test log (app 1.6): when WhatsApp reads the photo from this app, and when the person taps a button,
 * in milliseconds since WhatsApp was opened for that photo. Shows whether WhatsApp reads the photo
 * again at the moment "send" is pressed, which would tell the app the photo was sent.
 * Kept in memory only, for the last 3 photos.
 */
final class SendLog {

    private static final int KEEP_SENDS = 3;
    private static final List<Object[]> entries = new ArrayList<>();   // {send number, ms, event, detail}
    private static int sendNo = 0;
    private static long t0 = 0;

    private SendLog() { }

    static synchronized void start(String code) {
        sendNo++;
        t0 = SystemClock.elapsedRealtime();
        entries.removeIf(e -> (int) e[0] <= sendNo - KEEP_SENDS);
        entries.add(new Object[]{sendNo, 0L, "start", code});
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
