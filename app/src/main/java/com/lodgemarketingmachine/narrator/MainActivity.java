package com.lodgemarketingmachine.narrator;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {
    private static final int PICK_FILE = 41;
    private static final int PICK_AUDIO = 42;
    private static final String API_URL = "https://api.openai.com/v1/audio/speech";

    private static final String PREFS_NAME = "lodge_narrator_secure_preferences";
    private static final String PREF_API_KEY_DATA = "api_key_ciphertext";
    private static final String PREF_API_KEY_IV = "api_key_iv";
    private static final String PREF_VOICE_POSITION = "british_voice_position";
    private static final String KEYSTORE_ALIAS = "LodgeBookNarratorApiKey";

    private static final String[] VOICE_LABELS = {
            "British Warm and Authoritative — Cedar",
            "British Clear and Conversational — Marin",
            "British Deep and Formal — Onyx"
    };

    private static final String[] API_VOICES = {"cedar", "marin", "onyx"};

    private static final String[] VOICE_STYLES = {
            "Use a natural contemporary British English accent with a warm, mature and quietly authoritative tone. Keep the delivery measured, thoughtful and approachable. Do not sound American, theatrical, commercial or overly solemn.",
            "Use a natural contemporary British English accent with clear diction and a warm conversational tone. Sound intelligent, calm and engaging. Do not sound American, synthetic, theatrical or over-enthusiastic.",
            "Use a natural contemporary British English accent with a deeper, formal and dignified delivery. Remain human and conversational rather than ceremonial or dramatic. Do not sound American or like a promotional voice-over."
    };

    private static final String COMMON_INSTRUCTIONS =
            "Narrate this British leadership book faithfully. Use measured pacing, clear diction and gentle emphasis. " +
            "Pause briefly after chapter titles, section headings, quotations and reflective questions. " +
            "Pronounce British and Masonic terminology naturally and consistently. " +
            "Read the supplied text without adding commentary, introductions or omitted wording.";

    private EditText apiKey;
    private Spinner voiceSpinner;
    private ProgressBar progress;
    private TextView status;
    private TextView fileStatus;
    private Button selectButton;
    private Button saveKeyButton;
    private Button forgetKeyButton;
    private Button sampleButton;
    private Button chapterButton;
    private Button fullButton;
    private Button stopButton;

    private Button selectAudioButton;
    private Button playPauseButton;
    private Button playerStopButton;
    private TextView nowPlaying;
    private TextView playerTime;
    private SeekBar playerSeek;
    private MediaPlayer mediaPlayer;
    private final Handler playerHandler = new Handler(Looper.getMainLooper());
    private boolean playerPrepared = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean stopRequested = false;
    private String narrationText = "";
    private SharedPreferences preferences;

    private final Runnable playerProgressUpdater = new Runnable() {
        @Override public void run() {
            if (mediaPlayer != null && playerPrepared) {
                try {
                    int position = mediaPlayer.getCurrentPosition();
                    int duration = mediaPlayer.getDuration();
                    playerSeek.setMax(Math.max(duration, 1));
                    playerSeek.setProgress(position);
                    playerTime.setText(formatTime(position) + " / " + formatTime(duration));
                } catch (IllegalStateException ignored) {}
            }
            playerHandler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        apiKey = findViewById(R.id.apiKey);
        voiceSpinner = findViewById(R.id.voiceSpinner);
        progress = findViewById(R.id.progress);
        status = findViewById(R.id.status);
        fileStatus = findViewById(R.id.fileStatus);
        selectButton = findViewById(R.id.selectButton);
        saveKeyButton = findViewById(R.id.saveKeyButton);
        forgetKeyButton = findViewById(R.id.forgetKeyButton);
        sampleButton = findViewById(R.id.sampleButton);
        chapterButton = findViewById(R.id.chapterButton);
        fullButton = findViewById(R.id.fullButton);
        stopButton = findViewById(R.id.stopButton);

        selectAudioButton = findViewById(R.id.selectAudioButton);
        playPauseButton = findViewById(R.id.playPauseButton);
        playerStopButton = findViewById(R.id.playerStopButton);
        nowPlaying = findViewById(R.id.nowPlaying);
        playerTime = findViewById(R.id.playerTime);
        playerSeek = findViewById(R.id.playerSeek);

        voiceSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, VOICE_LABELS));
        int savedVoicePosition = preferences.getInt(PREF_VOICE_POSITION, 0);
        if (savedVoicePosition < 0 || savedVoicePosition >= VOICE_LABELS.length) savedVoicePosition = 0;
        voiceSpinner.setSelection(savedVoicePosition);
        voiceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putInt(PREF_VOICE_POSITION, position).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        String rememberedKey = loadApiKeySecurely();
        if (!rememberedKey.isEmpty()) {
            apiKey.setText(rememberedKey);
            status.setText("Saved API key restored securely. Ready.");
        }

        stopButton.setEnabled(false);
        playPauseButton.setEnabled(false);
        playerStopButton.setEnabled(false);

        selectButton.setOnClickListener(v -> chooseFile());
        saveKeyButton.setOnClickListener(v -> saveEnteredKey());
        forgetKeyButton.setOnClickListener(v -> forgetSavedKey());
        sampleButton.setOnClickListener(v -> generateSample());
        chapterButton.setOnClickListener(v -> generateChapterOne());
        fullButton.setOnClickListener(v -> generateFullBook());
        stopButton.setOnClickListener(v -> { stopRequested = true; status.setText("Stopping after the current part…"); });
        selectAudioButton.setOnClickListener(v -> chooseAudio());
        playPauseButton.setOnClickListener(v -> togglePlayback());
        playerStopButton.setOnClickListener(v -> stopPlayback());
        playerSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progressValue, boolean fromUser) {
                if (fromUser && mediaPlayer != null && playerPrepared) {
                    try { mediaPlayer.seekTo(progressValue); } catch (IllegalStateException ignored) {}
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        playerHandler.post(playerProgressUpdater);
    }

    private void chooseFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        startActivityForResult(intent, PICK_FILE);
    }

    private void chooseAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        startActivityForResult(intent, PICK_AUDIO);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == PICK_FILE) {
            try {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) {}
                narrationText = readUri(uri);
                fileStatus.setText("Narration file loaded: " + displayName(uri));
                status.setText("Ready");
            } catch (Exception e) { status.setText("Could not open file: " + e.getMessage()); }
        } else if (requestCode == PICK_AUDIO) {
            try {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) {}
                loadAudio(uri, displayName(uri));
            } catch (Exception e) { nowPlaying.setText("Could not open audio: " + e.getMessage()); }
        }
    }

    private String readUri(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new Exception("File could not be read.");
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex("_display_name");
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {}
        return "Selected file";
    }

    private boolean looksLikeApiKey(String key) { return key.startsWith("sk-") && key.length() >= 20; }

    private void saveEnteredKey() {
        String key = apiKey.getText().toString().trim();
        if (!looksLikeApiKey(key)) {
            Toast.makeText(this, "Enter a valid OpenAI API key first.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            saveApiKeySecurely(key);
            status.setText("API key saved securely on this device.");
            Toast.makeText(this, "API key remembered securely.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { status.setText("The API key could not be saved securely: " + e.getMessage()); }
    }

    private void forgetSavedKey() {
        preferences.edit().remove(PREF_API_KEY_DATA).remove(PREF_API_KEY_IV).apply();
        apiKey.setText("");
        status.setText("Saved API key removed.");
        Toast.makeText(this, "Saved API key forgotten.", Toast.LENGTH_SHORT).show();
    }

    private boolean validKey() {
        String key = apiKey.getText().toString().trim();
        if (!looksLikeApiKey(key)) {
            Toast.makeText(this, "Enter a valid OpenAI API key.", Toast.LENGTH_LONG).show();
            return false;
        }
        try { saveApiKeySecurely(key); }
        catch (Exception e) { status.setText("Key is usable now but could not be remembered: " + e.getMessage()); }
        return true;
    }

    private SecretKey getOrCreateEncryptionKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEYSTORE_ALIAS, null)).getSecretKey();
        }
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec keySpec = new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build();
        keyGenerator.init(keySpec);
        return keyGenerator.generateKey();
    }

    private void saveApiKeySecurely(String key) throws Exception {
        SecretKey secretKey = getOrCreateEncryptionKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encrypted = cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
        preferences.edit()
                .putString(PREF_API_KEY_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(PREF_API_KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
    }

    private String loadApiKeySecurely() {
        String encryptedValue = preferences.getString(PREF_API_KEY_DATA, "");
        String ivValue = preferences.getString(PREF_API_KEY_IV, "");
        if (encryptedValue.isEmpty() || ivValue.isEmpty()) return "";
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (!keyStore.containsAlias(KEYSTORE_ALIAS)) return "";
            SecretKey secretKey = ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEYSTORE_ALIAS, null)).getSecretKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, Base64.decode(ivValue, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(encryptedValue, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            preferences.edit().remove(PREF_API_KEY_DATA).remove(PREF_API_KEY_IV).apply();
            return "";
        }
    }

    private void loadAudio(Uri uri, String name) {
        releasePlayer();
        playerPrepared = false;
        playPauseButton.setEnabled(false);
        playerStopButton.setEnabled(false);
        playerSeek.setProgress(0);
        playerTime.setText("0:00 / 0:00");
        nowPlaying.setText("Loading: " + name);
        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.setOnPreparedListener(player -> {
                playerPrepared = true;
                playerSeek.setMax(Math.max(player.getDuration(), 1));
                playerTime.setText("0:00 / " + formatTime(player.getDuration()));
                nowPlaying.setText("Now playing: " + name);
                playPauseButton.setText("Play");
                playPauseButton.setEnabled(true);
                playerStopButton.setEnabled(true);
            });
            mediaPlayer.setOnCompletionListener(player -> {
                playPauseButton.setText("Play");
                playerSeek.setProgress(0);
                playerTime.setText("0:00 / " + formatTime(player.getDuration()));
            });
            mediaPlayer.setOnErrorListener((player, what, extra) -> {
                nowPlaying.setText("This audio file could not be played.");
                playPauseButton.setEnabled(false);
                playerStopButton.setEnabled(false);
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            releasePlayer();
            nowPlaying.setText("Could not load audio: " + e.getMessage());
        }
    }

    private void togglePlayback() {
        if (mediaPlayer == null || !playerPrepared) return;
        try {
            if (mediaPlayer.isPlaying()) { mediaPlayer.pause(); playPauseButton.setText("Play"); }
            else { mediaPlayer.start(); playPauseButton.setText("Pause"); }
        } catch (IllegalStateException e) { nowPlaying.setText("Playback error. Select the audio file again."); }
    }

    private void stopPlayback() {
        if (mediaPlayer == null || !playerPrepared) return;
        try {
            if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            mediaPlayer.seekTo(0);
            playPauseButton.setText("Play");
            playerSeek.setProgress(0);
            playerTime.setText("0:00 / " + formatTime(mediaPlayer.getDuration()));
        } catch (IllegalStateException ignored) {}
    }

    private String formatTime(int milliseconds) {
        int totalSeconds = Math.max(milliseconds, 0) / 1000;
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) return String.format(Locale.UK, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.UK, "%d:%02d", minutes, seconds);
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try { mediaPlayer.reset(); } catch (Exception ignored) {}
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        playerPrepared = false;
    }

    private void generateSample() {
        if (!validKey()) return;
        List<Section> list = new ArrayList<>();
        list.add(new Section("00_british_voice_sample", "British Voice Sample",
                "Every generation inherits a lodge. Every generation has a choice. Every generation leaves a legacy. This is not a book about recruiting men at any cost. It is a book about becoming a lodge worth joining, communicating honestly, selecting carefully and caring properly for those who enter."));
        runGeneration("British voice sample", list);
    }

    private void generateChapterOne() {
        if (!validKey() || !hasText()) return;
        for (Section section : parseChapters(narrationText)) {
            if (section.id.startsWith("chapter_01")) {
                runGeneration("Chapter 1", Collections.singletonList(section));
                return;
            }
        }
        status.setText("Chapter 1 could not be found in the selected file.");
    }

    private void generateFullBook() {
        if (!validKey() || !hasText()) return;
        runGeneration("Full book", parseChapters(narrationText));
    }

    private boolean hasText() {
        if (narrationText.trim().isEmpty()) {
            Toast.makeText(this, "Select Narration_Master.md first.", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private List<Section> parseChapters(String text) {
        String clean = text.replace("\r\n", "\n");
        Pattern pattern = Pattern.compile("(?m)^#\\s+(Chapter\\s+([0-9]+)[^\\n]*|Before We Begin|How to Use This Book|What You Will Build|The Complete Machine|Acknowledgements|Ideas and Influences|About the Author)\\s*$");
        Matcher matcher = pattern.matcher(clean);
        List<Integer> starts = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (matcher.find()) { starts.add(matcher.start()); titles.add(matcher.group(1).trim()); }
        List<Section> output = new ArrayList<>();
        if (starts.isEmpty()) { output.add(new Section("full_book", "Full Book", clean)); return output; }
        if (starts.get(0) > 0) {
            String frontMatter = clean.substring(0, starts.get(0)).trim();
            if (!frontMatter.isEmpty()) output.add(new Section("front_matter", "Front Matter", frontMatter));
        }
        for (int index = 0; index < starts.size(); index++) {
            int end = index + 1 < starts.size() ? starts.get(index + 1) : clean.length();
            String title = titles.get(index);
            String id;
            Matcher number = Pattern.compile("Chapter\\s+([0-9]+)", Pattern.CASE_INSENSITIVE).matcher(title);
            if (number.find()) id = String.format(Locale.UK, "chapter_%02d", Integer.parseInt(number.group(1)));
            else id = title.toLowerCase(Locale.UK).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
            output.add(new Section(id, title, clean.substring(starts.get(index), end).trim()));
        }
        return output;
    }

    private List<String> chunks(String text) {
        List<String> output = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            String value = paragraph.trim();
            if (value.isEmpty()) continue;
            if (current.length() + value.length() + 2 > 3700 && current.length() > 0) {
                output.add(current.toString()); current.setLength(0);
            }
            if (value.length() > 3700) {
                for (int index = 0; index < value.length(); index += 3600)
                    output.add(value.substring(index, Math.min(value.length(), index + 3600)));
            } else {
                if (current.length() > 0) current.append("\n\n");
                current.append(value);
            }
        }
        if (current.length() > 0) output.add(current.toString());
        return output;
    }

    private void runGeneration(String label, List<Section> sections) {
        stopRequested = false;
        setBusy(true);
        progress.setProgress(0);
        final String key = apiKey.getText().toString().trim();
        final int selectedPosition = voiceSpinner.getSelectedItemPosition();
        final String voice = API_VOICES[selectedPosition];
        final String instructions = COMMON_INSTRUCTIONS + " " + VOICE_STYLES[selectedPosition];
        executor.execute(() -> {
            try {
                List<Job> jobs = new ArrayList<>();
                for (Section section : sections) for (String chunk : chunks(section.text)) jobs.add(new Job(section, chunk));
                int completed = 0;
                String currentSection = "";
                File temporaryFile = null;
                OutputStream joinedOutput = null;
                Uri lastSavedUri = null;
                String lastSavedName = null;
                for (Job job : jobs) {
                    if (stopRequested) break;
                    if (!job.section.id.equals(currentSection)) {
                        if (joinedOutput != null) {
                            joinedOutput.close();
                            lastSavedName = currentSection + ".aac";
                            lastSavedUri = saveFile(temporaryFile, lastSavedName, currentSection);
                        }
                        currentSection = job.section.id;
                        temporaryFile = new File(getCacheDir(), currentSection + ".aac");
                        joinedOutput = new BufferedOutputStream(new FileOutputStream(temporaryFile, false));
                    }
                    updateStatus(label + ": part " + (completed + 1) + " of " + jobs.size() + "\n" + job.section.title);
                    joinedOutput.write(requestSpeech(key, voice, instructions, job.text));
                    joinedOutput.flush();
                    completed++;
                    updateProgress((int)Math.round(completed * 100.0 / jobs.size()));
                }
                if (joinedOutput != null) {
                    joinedOutput.close();
                    lastSavedName = currentSection + ".aac";
                    lastSavedUri = saveFile(temporaryFile, lastSavedName, currentSection);
                }
                boolean stopped = stopRequested;
                final Uri playableUri = lastSavedUri;
                final String playableName = lastSavedName;
                runOnUiThread(() -> {
                    setBusy(false);
                    status.setText(stopped ? "Stopped safely. Completed files are in Downloads/LodgeMarketingMachineAudio." : "Complete. Files are in Downloads/LodgeMarketingMachineAudio.");
                    if (playableUri != null) loadAudio(playableUri, playableName);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { setBusy(false); status.setText("Error: " + e.getMessage()); });
            }
        });
    }

    private byte[] requestSpeech(String key, String voice, String instructions, String text) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(API_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(180000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.setRequestProperty("Content-Type", "application/json");
        JSONObject body = new JSONObject();
        body.put("model", "gpt-4o-mini-tts");
        body.put("voice", voice);
        body.put("input", text);
        body.put("instructions", instructions);
        body.put("response_format", "aac");
        body.put("speed", 0.96);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int responseCode = connection.getResponseCode();
        InputStream input = responseCode >= 200 && responseCode < 300 ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) > 0) response.write(buffer, 0, count);
        input.close(); connection.disconnect();
        byte[] data = response.toByteArray();
        if (responseCode < 200 || responseCode >= 300)
            throw new Exception("OpenAI returned " + responseCode + ": " + new String(data, StandardCharsets.UTF_8));
        return data;
    }

    private Uri saveFile(File source, String name, String title) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/aac");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LodgeMarketingMachineAudio");
        values.put(MediaStore.Audio.Media.TITLE, title);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("Could not create output file.");
        try (InputStream input = new FileInputStream(source); OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new Exception("Could not open output file.");
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
        }
        return uri;
    }

    private void setBusy(boolean busy) {
        runOnUiThread(() -> {
            selectButton.setEnabled(!busy);
            saveKeyButton.setEnabled(!busy);
            forgetKeyButton.setEnabled(!busy);
            sampleButton.setEnabled(!busy);
            chapterButton.setEnabled(!busy);
            fullButton.setEnabled(!busy);
            stopButton.setEnabled(busy);
        });
    }

    private void updateStatus(String value) { runOnUiThread(() -> status.setText(value)); }
    private void updateProgress(int value) { runOnUiThread(() -> progress.setProgress(value)); }

    @Override protected void onDestroy() {
        playerHandler.removeCallbacks(playerProgressUpdater);
        releasePlayer();
        executor.shutdownNow();
        super.onDestroy();
    }

    private static class Section {
        final String id, title, text;
        Section(String id, String title, String text) { this.id = id; this.title = title; this.text = text; }
    }

    private static class Job {
        final Section section; final String text;
        Job(Section section, String text) { this.section = section; this.text = text; }
    }
}
