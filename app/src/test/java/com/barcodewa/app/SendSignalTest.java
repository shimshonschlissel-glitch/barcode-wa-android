package com.barcodewa.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Replays the requests WhatsApp Business made in the 20.11.2026 test (app 1.6), in ms since it opened. */
public class SendSignalTest {

    /** Returns the time of the "sent" signal, or -1. */
    private static long replay(Object... seq) {
        SendSignal s = new SendSignal();
        for (int i = 0; i < seq.length; i += 2) {
            if (s.onAccess((String) seq[i], ((Number) seq[i + 1]).longValue())) return ((Number) seq[i + 1]).longValue();
        }
        assertFalse(s.sent());
        return -1;
    }

    @Test
    public void photo1_sentAtAbout4s() {
        assertEquals(3900, replay("type", 200, "type", 200, "query", 200, "type", 300, "open", 400, "type", 400,
                "type", 400, "open", 400, "type", 3900));
    }

    @Test
    public void photo2_notSent_nextTapped() {
        assertEquals(-1, replay("type", 100, "query", 100, "type", 100, "type", 100, "open", 200, "type", 200,
                "type", 200, "open", 300));
    }

    @Test
    public void photo3_notSent_stopTapped() {
        assertEquals(-1, replay("type", 100, "type", 100, "query", 100, "type", 100, "open", 200, "type", 200,
                "type", 200, "open", 300));
    }

    @Test
    public void slowPhone_pauseInsideTheOpening_isNotASend() {
        // The second open comes late: the pause before it must not count.
        assertEquals(5000, replay("type", 100, "open", 300, "type", 1200, "open", 1250, "type", 1300, "type", 5000));
    }

    @Test
    public void firesOnlyOnce() {
        SendSignal s = new SendSignal();
        s.onAccess("open", 100);
        s.onAccess("open", 150);
        assertTrue(s.onAccess("type", 4000));
        assertFalse(s.onAccess("type", 9000));
        assertTrue(s.sent());
        s.reset();
        assertFalse(s.sent());
    }

    @Test
    public void quickSend_isMissed_soThePersonConfirms() {
        // "Send" pressed right after the photo loaded: too close to tell apart. Safe side: no signal.
        assertEquals(-1, replay("open", 200, "open", 300, "type", 600));
    }
}
