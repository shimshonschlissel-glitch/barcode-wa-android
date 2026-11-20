package com.barcodewa.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Serves files in cache/shared/ (the photo being sent, an export) to WhatsApp and share targets, read-only, only through granted URIs. */
public class FilesProvider extends ContentProvider {

    static final String AUTHORITY = "com.barcodewa.app.files";

    private final Handler main = new Handler(Looper.getMainLooper());

    /** Test log (1.6): only the photo being sent, not exports. */
    private void log(Uri uri, String event, String detail) {
        String p = uri.getPath() == null ? "" : uri.getPath();
        if (!p.toLowerCase(java.util.Locale.ROOT).endsWith(".jpg")) return;
        String who;
        try { who = getCallingPackage(); } catch (RuntimeException e) { who = null; }
        SendLog.add(event, (who == null ? "" : who.replaceFirst("^com\\.whatsapp", "wa")) + (detail.isEmpty() ? "" : " " + detail));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        if (getContext() == null || uri.getPath() == null) throw new FileNotFoundException();
        try {
            File base = new File(getContext().getCacheDir(), "shared").getCanonicalFile();
            File f = new File(getContext().getCacheDir(), uri.getPath()).getCanonicalFile();
            if (!f.getPath().startsWith(base.getPath() + File.separator) || !f.isFile()) throw new FileNotFoundException(uri.toString());
            return f;
        } catch (IOException e) {
            throw new FileNotFoundException(uri.toString());
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        log(uri, "open", mode);
        final long opened = SystemClock.elapsedRealtime();
        final boolean photo = f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jpg");
        try {
            // Same read-only file as before; the listener only says when WhatsApp lets go of it.
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY, main, e -> {
                if (photo) SendLog.add("close", "אחרי " + (SystemClock.elapsedRealtime() - opened) + "ms");
            });
        } catch (IOException e) {
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try { f = fileFor(uri); } catch (FileNotFoundException e) { return null; }
        log(uri, "query", projection == null ? "" : String.join(",", projection).replace("_display_name", "name").replace("_size", "size"));
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String p = uri.getPath() == null ? "" : uri.getPath().toLowerCase(java.util.Locale.ROOT);
        if (p.endsWith(".csv")) return "text/csv";
        log(uri, "type", "");
        return "image/jpeg";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
