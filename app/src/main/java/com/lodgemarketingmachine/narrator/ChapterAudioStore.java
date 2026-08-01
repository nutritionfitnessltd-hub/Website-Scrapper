package com.lodgemarketingmachine.narrator;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ChapterAudioStore {
    static final String RELATIVE_DIRECTORY = Environment.DIRECTORY_DOWNLOADS + "/LodgeMarketingMachineAudio/";

    private ChapterAudioStore() {}

    static List<Entry> list(Context context) {
        List<Entry> entries = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return entries;

        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.TITLE,
                MediaStore.MediaColumns.DURATION,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.MIME_TYPE
        };
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                + MediaStore.MediaColumns.MIME_TYPE + " LIKE ?";
        String[] arguments = {RELATIVE_DIRECTORY, "audio/%"};

        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arguments,
                MediaStore.MediaColumns.DISPLAY_NAME + " COLLATE NOCASE ASC")) {
            if (cursor == null) return entries;
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE);
            int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION);
            int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                String displayName = safe(cursor.getString(nameColumn));
                String title = safe(cursor.getString(titleColumn));
                long duration = cursor.isNull(durationColumn) ? 0L : cursor.getLong(durationColumn);
                long dateAdded = cursor.isNull(dateColumn) ? 0L : cursor.getLong(dateColumn);
                Uri uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, Long.toString(id));
                if (title.isEmpty()) title = titleFromFileName(displayName);
                if (duration <= 0) duration = readDuration(context, uri);
                entries.add(new Entry(uri, displayName, title, duration, dateAdded));
            }
        } catch (Exception ignored) {}
        return entries;
    }

    static Set<String> existingDisplayNames(Context context) {
        Set<String> names = new HashSet<>();
        for (Entry entry : list(context)) names.add(entry.displayName);
        return names;
    }

    static Uri saveMergedChapter(
            Context context,
            List<File> aacParts,
            String displayName,
            String title) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new Exception("Chapter storage requires Android 10 or later.");
        }
        if (aacParts.isEmpty()) throw new Exception("No audio parts were generated.");

        File merged = new File(context.getCacheDir(), "merged_" + System.nanoTime() + ".m4a");
        mergeAacParts(aacParts, merged);
        deleteExisting(context, displayName);

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.TITLE, title);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIRECTORY);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("Could not create the finished chapter file.");
        try (InputStream input = new FileInputStream(merged);
             OutputStream output = resolver.openOutputStream(uri, "w")) {
            if (output == null) throw new Exception("Could not open the finished chapter file.");
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
        } catch (Exception exception) {
            resolver.delete(uri, null, null);
            throw exception;
        } finally {
            merged.delete();
        }

        ContentValues complete = new ContentValues();
        complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, complete, null, null);
        return uri;
    }

    static String fileNameFor(NarrationDocument.Section section) {
        Matcher matcher = Pattern.compile("chapter_(\\d+)", Pattern.CASE_INSENSITIVE).matcher(section.id);
        String cleanTitle = section.title.replaceAll("(?i)^Chapter\\s+\\d+\\s*[—:–-]?\\s*", "").trim();
        if (cleanTitle.isEmpty()) cleanTitle = section.title;
        cleanTitle = sanitise(cleanTitle);
        if (matcher.find()) {
            return String.format(Locale.UK, "%02d - %s.m4a", Integer.parseInt(matcher.group(1)), cleanTitle);
        }
        return "00 - " + sanitise(section.title) + ".m4a";
    }

    static String titleFromFileName(String displayName) {
        return displayName
                .replaceFirst("(?i)\\.(m4a|mp4|aac|mp3)$", "")
                .replaceFirst("^\\d{2}\\s*-\\s*", "")
                .trim();
    }

    static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static void deleteExisting(Context context, String displayName) {
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                + MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        context.getContentResolver().delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                selection,
                new String[]{RELATIVE_DIRECTORY, displayName});
    }

    private static void mergeAacParts(List<File> parts, File output) throws Exception {
        MediaMuxer muxer = null;
        boolean started = false;
        long timelineUs = 0L;
        long lastWrittenUs = -1L;
        int outputTrack = -1;
        try {
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            for (File part : parts) {
                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(part.getAbsolutePath());
                    int inputTrack = findAudioTrack(extractor);
                    if (inputTrack < 0) throw new Exception("An audio part could not be read.");
                    extractor.selectTrack(inputTrack);
                    MediaFormat format = extractor.getTrackFormat(inputTrack);
                    if (!started) {
                        outputTrack = muxer.addTrack(format);
                        muxer.start();
                        started = true;
                    }

                    int maximumSize = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                            ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                            : 1024 * 1024;
                    ByteBuffer buffer = ByteBuffer.allocateDirect(Math.max(maximumSize, 65536));
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                            ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            : 24000;
                    long frameDurationUs = Math.max(1L, 1024L * 1_000_000L / Math.max(sampleRate, 1));

                    while (true) {
                        buffer.clear();
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) break;
                        long localTime = Math.max(0L, extractor.getSampleTime());
                        long presentationTime = timelineUs + localTime;
                        if (presentationTime <= lastWrittenUs) presentationTime = lastWrittenUs + 1L;
                        info.set(0, size, presentationTime, extractor.getSampleFlags());
                        muxer.writeSampleData(outputTrack, buffer, info);
                        lastWrittenUs = presentationTime;
                        extractor.advance();
                    }
                    timelineUs = Math.max(timelineUs, lastWrittenUs + frameDurationUs);
                } finally {
                    extractor.release();
                }
            }
            if (!started) throw new Exception("The finished chapter could not be assembled.");
        } finally {
            if (muxer != null) {
                try { if (started) muxer.stop(); } catch (Exception ignored) {}
                try { muxer.release(); } catch (Exception ignored) {}
            }
        }
    }

    private static int findAudioTrack(MediaExtractor extractor) {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            MediaFormat format = extractor.getTrackFormat(index);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return index;
        }
        return -1;
    }

    private static long readDuration(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception ignored) {
            return 0L;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private static String sanitise(String value) {
        String clean = value.replaceAll("[\\\\/:*?\"<>|]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.length() > 100) clean = clean.substring(0, 100).trim();
        return clean.isEmpty() ? "Untitled Chapter" : clean;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    static final class Entry {
        final Uri uri;
        final String displayName;
        final String title;
        final long durationMs;
        final long dateAdded;

        Entry(Uri uri, String displayName, String title, long durationMs, long dateAdded) {
            this.uri = uri;
            this.displayName = displayName;
            this.title = title;
            this.durationMs = durationMs;
            this.dateAdded = dateAdded;
        }
    }
}
