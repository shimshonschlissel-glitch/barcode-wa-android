package com.barcodewa.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class ScanLogicTest {

    private static List<ScanLogic.Hit> one(String v, String f, float x, float y) {
        return Collections.singletonList(new ScanLogic.Hit(v, f, x, y));
    }

    private final ScanLogic.Rect box = new ScanLogic.Rect(100, 100, 800, 300);   // centre 500,250

    @Test
    public void readsNeededByKind() {
        assertEquals(1, ScanLogic.needReads("QR"));
        assertEquals(1, ScanLogic.needReads("Data Matrix"));
        assertEquals(2, ScanLogic.needReads("Code 128"));
        assertEquals(2, ScanLogic.needReads("EAN-13"));
        assertEquals(3, ScanLogic.needReads("Code 39"));
        assertEquals(3, ScanLogic.needReads(null));
    }

    @Test
    public void code128NeedsTwoMatchingReads() {
        ScanLogic l = new ScanLogic();
        assertNull(l.offer(one("GWD005006602", "Code 128", 500, 250), box, 0));
        ScanLogic.Hit h = l.offer(one("GWD005006602", "Code 128", 500, 250), box, 50);
        assertNotNull(h);
        assertEquals("GWD005006602", h.value);
    }

    @Test
    public void qrAcceptedOnFirstRead() {
        assertNotNull(new ScanLogic().offer(one("https://x.test/1", "QR", 500, 250), box, 0));
    }

    @Test
    public void differentReadRestartsTheCount() {
        ScanLogic l = new ScanLogic();
        assertNull(l.offer(one("AAA111", "Code 128", 500, 250), box, 0));
        assertNull(l.offer(one("AAA112", "Code 128", 500, 250), box, 40));   // misread resets
        assertNotNull(l.offer(one("AAA112", "Code 128", 500, 250), box, 80));
    }

    @Test
    public void staleCandidateIsDropped() {
        ScanLogic l = new ScanLogic();
        assertNull(l.offer(one("AAA111", "Code 128", 500, 250), box, 0));
        assertNull(l.offer(one("AAA111", "Code 128", 500, 250), box, 2000));   // too long ago, counts as first again
        assertNotNull(l.offer(one("AAA111", "Code 128", 500, 250), box, 2040));
    }

    @Test
    public void sameCodeIsNotTakenTwiceWhileHeld() {
        ScanLogic l = new ScanLogic();
        assertNotNull(l.offer(one("QQ", "QR", 500, 250), box, 0));
        assertNull(l.offer(one("QQ", "QR", 500, 250), box, 500));
        assertNull(l.offer(one("QQ", "QR", 500, 250), box, 1900));   // still in view: held keeps renewing
        // back after a short look away: still the same reading (the page would only say "already listed")
        assertNull(l.offer(one("QQ", "QR", 500, 250), box, 1900 + ScanLogic.HELD_MS + 10));
        // out of view long enough, then back: a new reading
        assertNotNull(l.offer(one("QQ", "QR", 500, 250), box, 1900 + ScanLogic.HELD_MS + 10 + ScanLogic.RECENT_MS));
    }

    @Test
    public void aimWanderingBetweenNeighboursDoesNotTakeACodeTwice() {
        // 20.11: on a sheet with several codes the aim went A, B, A within a second, and A was listed twice.
        ScanLogic l = new ScanLogic();
        assertNotNull(l.offer(one("buy now", "QR", 500, 250), box, 0));
        assertNull(l.offer(one("01234565", "UPC-E", 500, 250), box, 150));
        assertNotNull(l.offer(one("01234565", "UPC-E", 500, 250), box, 250));
        assertNull(l.offer(one("buy now", "QR", 500, 250), box, 400));
        assertNull(l.offer(one("buy now", "QR", 500, 250), box, 500));
        assertNull(l.offer(one("01234565", "UPC-E", 500, 250), box, 700));
        assertNull(l.offer(one("01234565", "UPC-E", 500, 250), box, 800));
    }

    @Test
    public void picksTheCodeNearestTheCentreAndIgnoresOutside() {
        List<ScanLogic.Hit> found = Arrays.asList(
                new ScanLogic.Hit("EDGE", "Code 128", 150, 130),
                new ScanLogic.Hit("CENTRE", "Code 128", 520, 260),
                new ScanLogic.Hit("OUTSIDE", "Code 128", 500, 600));
        assertEquals("CENTRE", ScanLogic.pick(found, box).value);
        assertNull(ScanLogic.pick(one("OUTSIDE", "QR", 50, 50), box));
    }

    @Test
    public void aimRectMatchesTheWebFormula() {
        // 1280x720 landscape picture shown in a 390x490 portrait box, aim 86% x 34%
        ScanLogic.Rect r = ScanLogic.aimRect(1280, 720, 390, 490, 0.86f, 0.34f);
        float s = Math.max(390f / 1280, 490f / 720);
        assertEquals(390 * 0.86 / s, r.w, 0.01);
        assertEquals(490 * 0.34 / s, r.h, 0.01);
        assertEquals((1280 - r.w) / 2, r.x, 0.01);
        assertEquals((720 - r.h) / 2, r.y, 0.01);
        // portrait picture (rotated frame) in the same box
        ScanLogic.Rect p = ScanLogic.aimRect(720, 1280, 390, 490, 0.86f, 0.34f);
        assertEquals(720 * 0.86, p.w, 1.0);
    }
}
