package com.barcodewa.app;

/**
 * The "photo was sent" rule, apart from Android so it can be tested (SendSignalTest).
 *
 * WhatsApp (Business, 20.11.2026): on opening a shared photo it asks about the file and opens it twice
 * within half a second, then leaves it alone. Pressing "send" makes it ask about the file once more.
 * So the first request that comes after both opens and after a quiet moment = "sent".
 */
final class SendSignal {

    static final long QUIET_MS = 700;
    static final int OPENING_OPENS = 2;

    private int opens = 0;
    private long last = -1;
    private boolean sent = false;

    void reset() {
        opens = 0;
        last = -1;
        sent = false;
    }

    boolean sent() {
        return sent;
    }

    /** A request for the photo ("type", "query" or "open") at `now` ms. True exactly once: at the "send" request. */
    boolean onAccess(String event, long now) {
        if (sent) return false;
        boolean fire = opens >= OPENING_OPENS && last >= 0 && now - last >= QUIET_MS;
        if ("open".equals(event)) opens++;
        last = now;
        if (fire) sent = true;
        return fire;
    }
}
