package com.lodgemarketingmachine.narrator;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.widget.Button;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ExportPackageButton extends Button {
    private static final String AUDIO_RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/LodgeMarketingMachineAudio/";
    private static final String PACKAGE_NAME = "The_Lodge_Marketing_Machine_Audiobook_Package.zip";

    public ExportPackageButton(Context context) {
        super(context);
        initialise();
    }

    public ExportPackageButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialise();
    }

    public ExportPackageButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialise();
    }

    private void initialise() {
        setText("Export Audiobook Package ZIP");
        setAllCaps(false);
        setOnClickListener(view -> exportPackage());
    }

    private void exportPackage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(getContext(), "Package export requires Android 10 or later.", Toast.LENGTH_LONG).show();
            return;
        }

        setEnabled(false);
        setText("Preparing package…");

        new Thread(() -> {
            try {
                List<AudioFile> files = findFinishedAudioFiles();
                if (files.isEmpty()) {
                    postResult("No finished chapters found", "Generate at least one finished chapter first.");
                    return;
                }

                Uri packageUri = createPackageFile();
                if (packageUri == null) throw new Exception("Android could not create the ZIP file.");

                boolean complete = false;
                try (OutputStream rawOutput = getContext().getContentResolver().openOutputStream(packageUri, "w");
                     ZipOutputStream zipOutput = new ZipOutputStream(rawOutput)) {
                    if (rawOutput == null) throw new Exception("The ZIP output could not be opened.");

                    writeManifest(zipOutput, files);
                    writeReadme(zipOutput, files.size());

                    byte[] buffer = new byte[64 * 1024];
                    for (int index = 0; index < files.size(); index++) {
                        AudioFile file = files.get(index);
                        final int current = index + 1;
                        post(() -> setText("Adding chapter " + current + " of " + files.size() + "…"));

                        ZipEntry entry = new ZipEntry("audio/" + file.displayName);
                        zipOutput.putNextEntry(entry);
                        try (InputStream input = getContext().getContentResolver().openInputStream(file.uri)) {
                            if (input == null) throw new Exception("Could not read " + file.displayName);
                            int count;
                            while ((count = input.read(buffer)) > 0) zipOutput.write(buffer, 0, count);
                        }
                        zipOutput.closeEntry();
                    }
                    complete = true;
                } finally {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContext().getContentResolver().update(packageUri, values, null, null);
                    if (!complete) getContext().getContentResolver().delete(packageUri, null, null);
                }

                postResult("Export Audiobook Package ZIP", "Saved to Downloads/LodgeMarketingMachineAudio/" + PACKAGE_NAME);
            } catch (Exception exception) {
                postResult("Export Audiobook Package ZIP", "Export failed: " + exception.getMessage());
            }
        }).start();
    }

    private List<AudioFile> findFinishedAudioFiles() {
        List<AudioFile> result = new ArrayList<>();
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED
        };
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND (" +
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? OR " +
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?)";
        String[] arguments = {AUDIO_RELATIVE_PATH, "%.m4a", "%.aac"};

        try (Cursor cursor = getContext().getContentResolver().query(
                collection,
                projection,
                selection,
                arguments,
                MediaStore.MediaColumns.DISPLAY_NAME + " ASC")) {
            if (cursor == null) return result;
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                String name = cursor.getString(nameColumn);
                long size = cursor.isNull(sizeColumn) ? 0L : cursor.getLong(sizeColumn);
                long modified = cursor.isNull(modifiedColumn) ? 0L : cursor.getLong(modifiedColumn);
                if (name == null || name.startsWith(".")) continue;
                result.add(new AudioFile(ContentUris.withAppendedId(collection, id), name, size, modified));
            }
        }

        Collections.sort(result, Comparator.comparing(audioFile -> audioFile.displayName.toLowerCase(Locale.UK)));
        return result;
    }

    private Uri createPackageFile() {
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;

        String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        String[] arguments = {AUDIO_RELATIVE_PATH, PACKAGE_NAME};
        try (Cursor cursor = getContext().getContentResolver().query(
                collection,
                new String[]{MediaStore.MediaColumns._ID},
                selection,
                arguments,
                null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    getContext().getContentResolver().delete(ContentUris.withAppendedId(collection, id), null, null);
                }
            }
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, PACKAGE_NAME);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, AUDIO_RELATIVE_PATH);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        return getContext().getContentResolver().insert(collection, values);
    }

    private void writeManifest(ZipOutputStream output, List<AudioFile> files) throws Exception {
        JSONObject manifest = new JSONObject();
        manifest.put("format", "lodge-marketing-machine-audiobook-package");
        manifest.put("version", 1);
        manifest.put("book_title", "The Lodge Marketing Machine");
        manifest.put("author", "W.Bro Calam");
        manifest.put("audio_directory", "audio");
        manifest.put("chapter_count", files.size());
        manifest.put("created_utc", utcTimestamp());

        JSONArray chapters = new JSONArray();
        for (int index = 0; index < files.size(); index++) {
            AudioFile file = files.get(index);
            JSONObject chapter = new JSONObject();
            chapter.put("order", index + 1);
            chapter.put("file", "audio/" + file.displayName);
            chapter.put("display_name", file.displayName);
            chapter.put("title", humanTitle(file.displayName));
            chapter.put("size_bytes", file.size);
            chapter.put("modified_unix", file.modified);
            chapters.put(chapter);
        }
        manifest.put("chapters", chapters);

        ZipEntry entry = new ZipEntry("manifest.json");
        output.putNextEntry(entry);
        output.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private void writeReadme(ZipOutputStream output, int chapterCount) throws Exception {
        String text = "The Lodge Marketing Machine — Audiobook Package\n\n" +
                "This package contains " + chapterCount + " finished chapter audio files and a manifest.json file.\n" +
                "Upload this ZIP to the book-production chat so the self-contained Reader Edition APK can be built.\n" +
                "The final Reader Edition will include the book text, cover and chapter audio for offline reading and listening.\n";
        ZipEntry entry = new ZipEntry("README.txt");
        output.putNextEntry(entry);
        output.write(text.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private String humanTitle(String fileName) {
        String value = fileName.replaceFirst("(?i)\\.(m4a|aac)$", "");
        value = value.replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim();
        if (value.toLowerCase(Locale.UK).startsWith("chapter ")) return capitalise(value);
        if (value.matches("^[0-9]{1,2} .*")) return capitalise(value);
        return capitalise(value);
    }

    private String capitalise(String value) {
        if (value.isEmpty()) return value;
        return value.substring(0, 1).toUpperCase(Locale.UK) + value.substring(1);
    }

    private String utcTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.UK);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private void postResult(String buttonText, String message) {
        post(() -> {
            setEnabled(true);
            setText(buttonText);
            Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
        });
    }

    private static class AudioFile {
        final Uri uri;
        final String displayName;
        final long size;
        final long modified;

        AudioFile(Uri uri, String displayName, long size, long modified) {
            this.uri = uri;
            this.displayName = displayName;
            this.size = size;
            this.modified = modified;
        }
    }
}
