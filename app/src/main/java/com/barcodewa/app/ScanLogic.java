package com.barcodewa.app;

import java.util.List;

/**
 * Decides which barcode in a frame counts, and when a reading is trusted. No Android types, so it is unit-tested.
 * Mirrors the web app's live-scan rules: only codes inside the aiming box, the one nearest its centre wins,
 * and a code is accepted after enough matching reads in a row (by kind). The code just accepted is ignored
 * until it has been out of view for a moment, so holding the camera still does not add it twice.
 */
final class ScanLogic {

    static final class Hit {
        final String value;
        final String format;
        final float cx;
        final float cy;

        Hit(String value, String format, float cx, float cy) {
            this.value = value;
            this.format = format;
            this.cx = cx;
            this.cy = cy;
        }
    }

    /** Aiming box in image coordinates. */
    static final class Rect {
        final float x, y, w, h;

        Rect(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(float px, float py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }

    static final long HELD_MS = 1500;
    static final long CAND_MS = 800;

    private String held;
    private long heldAt;
    private String cand;
    private int count;
    private long candAt;

    /** 2D codes carry error correction: one read. 1D codes with a check digit: two. Others: three. */
    static int needReads(String format) {
        switch (format == null ? "" : format) {
            case "QR":
            case "Data Matrix":
            case "PDF417":
            case "Aztec":
                return 1;
            case "Code 128":
            case "EAN-13":
            case "EAN-8":
            case "UPC-A":
            case "UPC-E":
            case "ITF":
                return 2;
            default:
                return 3;
        }
    }

    /**
     * The part of an image of size imgW x imgH that the aiming box covers, when the image is shown with
     * "fill centre" scaling in a view of viewW x viewH and the box takes fx by fy of the view.
     */
    static Rect aimRect(float imgW, float imgH, float viewW, float viewH, float fx, float fy) {
        float s = Math.max(viewW / imgW, viewH / imgH);
        float w = Math.min(imgW, viewW * fx / s);
        float h = Math.min(imgH, viewH * fy / s);
        return new Rect((imgW - w) / 2f, (imgH - h) / 2f, w, h);
    }

    /** Nearest to the centre of the box among those inside it, or null. */
    static Hit pick(List<Hit> found, Rect box) {
        Hit best = null;
        double bestD = Double.MAX_VALUE;
        float mx = box.x + box.w / 2f, my = box.y + box.h / 2f;
        for (Hit h : found) {
            if (h.value == null || h.value.trim().isEmpty() || !box.contains(h.cx, h.cy)) continue;
            double d = Math.pow(h.cx - mx, 2) + Math.pow(h.cy - my, 2);
            if (d < bestD) {
                bestD = d;
                best = h;
            }
        }
        return best;
    }

    /** Feed one frame's results. Returns the accepted code, or null. `reading` is set when a code is being confirmed. */
    synchronized Hit offer(List<Hit> found, Rect box, long now) {
        if (held != null && now - heldAt > HELD_MS) held = null;
        if (cand != null && now - candAt > CAND_MS) {
            cand = null;
            count = 0;
        }
        Hit hit = found == null ? null : pick(found, box);
        if (hit == null) return null;
        String v = hit.value.trim();
        if (v.equals(held)) {
            heldAt = now;
            return null;
        }
        if (v.equals(cand)) count++;
        else {
            cand = v;
            count = 1;
        }
        candAt = now;
        if (count < needReads(hit.format)) return null;
        cand = null;
        count = 0;
        held = v;
        heldAt = now;
        return new Hit(v, hit.format, hit.cx, hit.cy);
    }

    /** True while a code has been seen but not yet confirmed. */
    synchronized boolean confirming() {
        return cand != null;
    }

    /** Forget everything, e.g. when the scanner opens again. */
    synchronized void reset() {
        held = null;
        cand = null;
        count = 0;
    }
}
