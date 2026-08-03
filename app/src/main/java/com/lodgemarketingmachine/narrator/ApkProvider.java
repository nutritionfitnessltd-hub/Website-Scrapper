package com.lodgemarketingmachine.narrator;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public class ApkProvider extends ContentProvider {
    private static final String OUTPUT_NAME = "The_Lodge_Marketing_Machine_Reader_Edition.apk";

    @Override public boolean onCreate() {
        return true;
    }

    private File resolveFile(Uri uri) throws FileNotFoundException {
        if (getContext() == null || uri == null || !uri.getLastPathSegment().equals(OUTPUT_NAME)) {
            throw new FileNotFoundException("Unknown file.");
        }
        File file = new File(new File(getContext().getCacheDir(), "reader_install"), OUTPUT_NAME);
        if (!file.exists()) throw new FileNotFoundException("Reader Edition APK is missing.");
        return file;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolveFile(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = resolveFile(uri);
            MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            cursor.addRow(new Object[]{OUTPUT_NAME, file.length()});
            return cursor;
        } catch (FileNotFoundException exception) {
            return null;
        }
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
}
