package com.ldj.djspdf;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

public class GenericFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.ldj.djspdf.fileprovider";

    public static Uri getUriForFile(Context context, File file) {
        return Uri.parse("content://" + AUTHORITY + "/" + file.getName());
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context context = getContext();
        if (context == null) throw new FileNotFoundException("Context is null");
        String fileName = uri.getLastPathSegment();
        if (fileName == null) throw new FileNotFoundException("Invalid URI path");
        File file = new File(context.getCacheDir(), fileName);
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: " + file.getAbsolutePath());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context context = getContext();
        String fileName = uri.getLastPathSegment();
        File file = (context != null && fileName != null) ? new File(context.getCacheDir(), fileName) : null;
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        if (file != null && file.exists()) {
            cursor.addRow(new Object[]{file.getName(), file.length()});
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name != null) {
            if (name.endsWith(".apk")) {
                return "application/vnd.android.package-archive";
            }
            if (name.endsWith(".pdf")) {
                return "application/pdf";
            }
        }
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
