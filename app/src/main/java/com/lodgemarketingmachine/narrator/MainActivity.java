package com.lodgemarketingmachine.narrator;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final String[] PART_PREFIXES = {
            "LMM_Reader_Part_1_of_4",
            "LMM_Reader_Part_2_of_4",
            "LMM_Reader_Part_3_of_4",
            "LMM_Reader_Part_4_of_4"
    };
    private static final String[] PART_ENTRY_NAMES = {
            "reader.apk.part1",
            "reader.apk.part2",
            "reader.apk.part3",
            "reader.apk.part4"
    };
    private static final long EXPECTED_SIZE = 272961334L;
    private static final String EXPECTED_SHA256 = "9efd70678f4c8ce574e00cdf6d51c675f2a2e28e1407ca2c1d1d9f6d7b9a901b";
    private static final String OUTPUT_NAME = "The_Lodge_Marketing_Machine_Reader_Edition.apk";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Uri> partUris = new ArrayList<>();
    private TextView[] partViews;
    private TextView status;
    private ProgressBar progress;
    private Button checkButton;
    private Button buildButton;
    private File outputApk;
    private boolean pendingInstall = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        partViews = new TextView[]{
                findViewById(R.id.part1Status),
                findViewById(R.id.part2Status),
                findViewById(R.id.part3Status),
                findViewById(R.id.part4Status)
        };
        status = findViewById(R.id.status);
        progress = findViewById(R.id.progress);
        checkButton = findViewById(R.id.checkButton);
        buildButton = findViewById(R.id.buildButton);

        File outputDirectory = new File(getCacheDir(), "reader_install");
        if (!outputDirectory.exists()) outputDirectory.mkdirs();
        outputApk = new File(outputDirectory, OUTPUT_NAME);

        checkButton.setOnClickListener(view -> checkDownloads());
        buildButton.setOnClickListener(view -> buildAndInstall());
        checkDownloads();
    }

    @Override protected void onResume() {
        super.onResume();
        if (pendingInstall && getPackageManager().canRequestPackageInstalls() && outputApk.exists()) {
            pendingInstall = false;
            launchInstaller();
        }
    }

    private void checkDownloads() {
        partUris.clear();
        boolean allFound = true;
        for (int index = 0; index < PART_PREFIXES.length; index++) {
            Uri uri = findNewestDownload(PART_PREFIXES[index]);
            partUris.add(uri);
            if (uri == null) {
                partViews[index].setText("Part " + (index + 1) + ": NOT FOUND");
                allFound = false;
            } else {
                partViews[index].setText("Part " + (index + 1) + ": found");
            }
        }
        buildButton.setEnabled(allFound);
        status.setText(allFound
                ? "All four parts found. Ready to build the Reader Edition."
                : "Download every part, then tap Check Downloads Again.");
    }

    private Uri findNewestDownload(String prefix) {
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.MediaColumns._ID};
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
        String[] arguments = {prefix + "%.zip"};
        String sort = MediaStore.MediaColumns.DATE_ADDED + " DESC";
        try (Cursor cursor = getContentResolver().query(collection, projection, selection, arguments, sort)) {
            if (cursor != null && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(collection, cursor.getLong(0));
            }
        }
        return null;
    }

    private void buildAndInstall() {
        checkDownloads();
        if (partUris.size() != 4 || partUris.contains(null)) {
            Toast.makeText(this, "All four parts must be downloaded first.", Toast.LENGTH_LONG).show();
            return;
        }

        setBusy(true);
        progress.setProgress(0);
        status.setText("Building Reader Edition…");

        executor.execute(() -> {
            try {
                if (outputApk.exists() && !outputApk.delete()) {
                    throw new Exception("Could not replace the previous temporary installer file.");
                }

                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long written = 0L;
                byte[] buffer = new byte[64 * 1024];

                try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(outputApk))) {
                    for (int partIndex = 0; partIndex < 4; partIndex++) {
                        final int displayPart = partIndex + 1;
                        updateStatus("Joining part " + displayPart + " of 4…");
                        try (InputStream raw = getContentResolver().openInputStream(partUris.get(partIndex));
                             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
                            if (raw == null) throw new Exception("Part " + displayPart + " could not be opened.");
                            ZipEntry entry;
                            boolean foundEntry = false;
                            while ((entry = zip.getNextEntry()) != null) {
                                if (!entry.isDirectory() && entry.getName().endsWith(PART_ENTRY_NAMES[partIndex])) {
                                    foundEntry = true;
                                    int count;
                                    while ((count = zip.read(buffer)) > 0) {
                                        output.write(buffer, 0, count);
                                        digest.update(buffer, 0, count);
                                        written += count;
                                        final int percentage = (int)Math.min(100L, (written * 100L) / EXPECTED_SIZE);
                                        updateProgress(percentage);
                                    }
                                    zip.closeEntry();
                                    break;
                                }
                            }
                            if (!foundEntry) throw new Exception("Part " + displayPart + " is not the expected Reader Edition file.");
                        }
                    }
                    output.flush();
                }

                if (written != EXPECTED_SIZE) {
                    throw new Exception("The rebuilt app has the wrong size. Download the four parts again.");
                }

                String actualSha = toHex(digest.digest());
                if (!EXPECTED_SHA256.equals(actualSha)) {
                    throw new Exception("Verification failed. One of the downloaded parts is incomplete.");
                }

                runOnUiThread(() -> {
                    setBusy(false);
                    progress.setProgress(100);
                    status.setText("Reader Edition verified. Opening Android installer…");
                    requestPermissionOrInstall();
                });
            } catch (Exception exception) {
                if (outputApk.exists()) outputApk.delete();
                runOnUiThread(() -> {
                    setBusy(false);
                    progress.setProgress(0);
                    status.setText("Build failed: " + exception.getMessage());
                });
            }
        });
    }

    private void requestPermissionOrInstall() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = true;
            status.setText("Allow LMM Reader Installer to install unknown apps, then return here.");
            Intent settingsIntent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settingsIntent);
            return;
        }
        launchInstaller();
    }

    private void launchInstaller() {
        if (!outputApk.exists() || outputApk.length() != EXPECTED_SIZE) {
            status.setText("The rebuilt APK is missing. Tap Build and Install again.");
            return;
        }
        Uri contentUri = Uri.parse("content://" + getPackageName() + ".provider/" + OUTPUT_NAME);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception exception) {
            status.setText("Android could not open the installer: " + exception.getMessage());
        }
    }

    private void setBusy(boolean busy) {
        checkButton.setEnabled(!busy);
        buildButton.setEnabled(!busy && !partUris.contains(null) && partUris.size() == 4);
    }

    private void updateStatus(String text) {
        runOnUiThread(() -> status.setText(text));
    }

    private void updateProgress(int value) {
        runOnUiThread(() -> progress.setProgress(value));
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(String.format(Locale.UK, "%02x", value & 0xff));
        return builder.toString();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
