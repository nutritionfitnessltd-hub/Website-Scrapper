package com.lodgemarketingmachine.narrator;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ArrayAdapter;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_NARRATION_FILE = 41;
    private static final String API_URL = "https://api.openai.com/v1/audio/speech";

    private static final String PREFS_NAME = "lodge_narrator_preferences";
    private static final String PREF_VOICE_POSITION = "british_voice_position";
    private static final String PREF_NARRATION_URI = "narration_master_uri";
    private static final String PREF_PLAYBACK_PREFIX = "playback_";

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

    private SharedPreferences preferences;
    private SecureApiKeyStore secureApiKeyStore;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler playerHandler = new Handler(Looper.getMainLooper());

    private EditText apiKey;
    private Spinner voiceSpinner;
    private ProgressBar generationProgress;
    private TextView generationStatus;
    private TextView narrationFileStatus;
    private Button selectNarrationButton;
    private Button saveKeyButton;
    private Button forgetKeyButton;
    private Button sampleButton;
    private Button chapterOneButton;
    private Button fullBookButton;
    private Button stopGenerationButton;

    private TextView libraryStatus;
    private ListView chapterList;
    private Button refreshLibraryButton;
    private TextView nowPlaying;
    private SeekBar playerSeek;
    private TextView playerTime;
    private Button previousButton;
    private Button playPauseButton;
    private Button nextButton;
    private Button playerStopButton;

    private final List<ChapterAudioStore.Entry> libraryEntries = new ArrayList<>();
    private ChapterAdapter chapterAdapter;
    private MediaPlayer mediaPlayer;
    private boolean playerPrepared = false;
    private int currentIndex = -1;
    private long lastPositionSaveAt = 0L;
    private volatile boolean stopRequested = false;
    private String narrationText = "";

    private final Runnable playerProgressUpdater = new Runnable() {
        @Override public void run() {
            if (mediaPlayer != null && playerPrepared && currentIndex >= 0 && currentIndex < libraryEntries.size()) {
                try {
                    int position = mediaPlayer.getCurrentPosition();
                    int duration = mediaPlayer.getDuration();
                    playerSeek.setMax(Math.max(duration, 1));
                    playerSeek.setProgress(position);
                    playerTime.setText(formatTime(position) + " / " + formatTime(duration));
                    long now = System.currentTimeMillis();
                    if (now - lastPositionSaveAt > 5000L) {
                        savePlaybackPosition(libraryEntries.get(currentIndex), position, duration);
                        lastPositionSaveAt = now;
                        chapterAdapter.notifyDataSetChanged();
                    }
                } catch (IllegalStateException ignored) {}
            }
            playerHandler.postDelayed(this, 500L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        secureApiKeyStore = new SecureApiKeyStore(this);
        bindViews();
        setupTabs();
        setupVoiceSelection();
        setupActions();
        restoreSecureKey();
        restoreNarrationFile();
        refreshLibrary(null, false);
        playerHandler.post(playerProgressUpdater);
    }

    private void bindViews() {
        apiKey = findViewById(R.id.apiKey);
        voiceSpinner = findViewById(R.id.voiceSpinner);
        generationProgress = findViewById(R.id.progress);
        generationStatus = findViewById(R.id.status);
        narrationFileStatus = findViewById(R.id.fileStatus);
        selectNarrationButton = findViewById(R.id.selectButton);
        saveKeyButton = findViewById(R.id.saveKeyButton);
        forgetKeyButton = findViewById(R.id.forgetKeyButton);
        sampleButton = findViewById(R.id.sampleButton);
        chapterOneButton = findViewById(R.id.chapterButton);
        fullBookButton = findViewById(R.id.fullButton);
        stopGenerationButton = findViewById(R.id.stopButton);

        libraryStatus = findViewById(R.id.libraryStatus);
        chapterList = findViewById(R.id.chapterList);
        refreshLibraryButton = findViewById(R.id.refreshLibraryButton);
        nowPlaying = findViewById(R.id.nowPlaying);
        playerSeek = findViewById(R.id.playerSeek);
        playerTime = findViewById(R.id.playerTime);
        previousButton = findViewById(R.id.previousButton);
        playPauseButton = findViewById(R.id.playPauseButton);
        nextButton = findViewById(R.id.nextButton);
        playerStopButton = findViewById(R.id.playerStopButton);

        chapterAdapter = new ChapterAdapter();
        chapterList.setAdapter(chapterAdapter);
        stopGenerationButton.setEnabled(false);
        setPlayerButtons(false);
    }

    private void setupTabs() {
        TabHost tabHost = findViewById(android.R.id.tabhost);
        tabHost.setup();
        tabHost.addTab(tabHost.newTabSpec("generate")
                .setIndicator("Generate")
                .setContent(R.id.generateTab));
        tabHost.addTab(tabHost.newTabSpec("library")
                .setIndicator("Chapter Library")
                .setContent(R.id.libraryTab));
    }

    private void setupVoiceSelection() {
        voiceSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                VOICE_LABELS));
        int savedPosition = preferences.getInt(PREF_VOICE_POSITION, 0);
        if (savedPosition < 0 || savedPosition >= VOICE_LABELS.length) savedPosition = 0;
        voiceSpinner.setSelection(savedPosition);
        voiceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putInt(PREF_VOICE_POSITION, position).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupActions() {
        selectNarrationButton.setOnClickListener(view -> chooseNarrationFile());
        saveKeyButton.setOnClickListener(view -> saveEnteredKey());
        forgetKeyButton.setOnClickListener(view -> forgetSavedKey());
        sampleButton.setOnClickListener(view -> generateSample());
        chapterOneButton.setOnClickListener(view -> generateChapterOne());
        fullBookButton.setOnClickListener(view -> generateFullBook());
        stopGenerationButton.setOnClickListener(view -> {
            stopRequested = true;
            generationStatus.setText("Stopping after the current audio request…");
        });

        refreshLibraryButton.setOnClickListener(view -> refreshLibrary(null, false));
        chapterList.setOnItemClickListener((parent, view, position, id) -> loadChapter(position, true));
        previousButton.setOnClickListener(view -> playPrevious());
        playPauseButton.setOnClickListener(view -> togglePlayback());
        nextButton.setOnClickListener(view -> playNext());
        playerStopButton.setOnClickListener(view -> stopPlayback());
        playerSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progressValue, boolean fromUser) {
                if (fromUser && mediaPlayer != null && playerPrepared) {
                    try { mediaPlayer.seekTo(progressValue); } catch (IllegalStateException ignored) {}
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                saveCurrentPlaybackPosition();
            }
        });
    }

    private void restoreSecureKey() {
        String remembered = secureApiKeyStore.load();
        if (!remembered.isEmpty()) {
            apiKey.setText(remembered);
            generationStatus.setText("Saved API key restored securely. Ready.");
        }
    }

    private void restoreNarrationFile() {
        String savedUri = preferences.getString(PREF_NARRATION_URI, "");
        if (savedUri.isEmpty()) return;
        try {
            loadNarrationFile(Uri.parse(savedUri));
        } catch (Exception exception) {
            preferences.edit().remove(PREF_NARRATION_URI).apply();
            narrationFileStatus.setText("Select the Narration Master file");
        }
    }

    private void chooseNarrationFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        startActivityForResult(intent, PICK_NARRATION_FILE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_NARRATION_FILE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {}
        try {
            loadNarrationFile(uri);
            preferences.edit().putString(PREF_NARRATION_URI, uri.toString()).apply();
            generationStatus.setText("Narration Master loaded. Ready.");
            refreshLibrary(null, false);
        } catch (Exception exception) {
            generationStatus.setText("Could not open the narration file: " + exception.getMessage());
        }
    }

    private void loadNarrationFile(Uri uri) throws Exception {
        narrationText = readUri(uri);
        narrationFileStatus.setText("Loaded: " + displayName(uri));
    }

    private String readUri(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new Exception("The file could not be read.");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex("_display_name");
                if (column >= 0) return cursor.getString(column);
            }
        } catch (Exception ignored) {}
        return "Narration_Master.md";
    }

    private boolean looksLikeApiKey(String value) {
        return value.startsWith("sk-") && value.length() >= 20;
    }

    private boolean validKey() {
        String key = apiKey.getText().toString().trim();
        if (!looksLikeApiKey(key)) {
            Toast.makeText(this, "Enter a valid OpenAI API key.", Toast.LENGTH_LONG).show();
            return false;
        }
        try { secureApiKeyStore.save(key); }
        catch (Exception exception) {
            generationStatus.setText("The key can be used now but could not be remembered securely.");
        }
        return true;
    }

    private void saveEnteredKey() {
        String key = apiKey.getText().toString().trim();
        if (!looksLikeApiKey(key)) {
            Toast.makeText(this, "Enter a valid OpenAI API key first.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            secureApiKeyStore.save(key);
            generationStatus.setText("API key saved securely on this device.");
            Toast.makeText(this, "API key remembered securely.", Toast.LENGTH_SHORT).show();
        } catch (Exception exception) {
            generationStatus.setText("The API key could not be saved securely: " + exception.getMessage());
        }
    }

    private void forgetSavedKey() {
        secureApiKeyStore.clear();
        apiKey.setText("");
        generationStatus.setText("Saved API key removed.");
    }

    private boolean hasNarrationText() {
        if (narrationText.trim().isEmpty()) {
            Toast.makeText(this, "Select Narration_Master.md first.", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private void generateSample() {
        if (!validKey()) return;
        NarrationDocument.Section sample = new NarrationDocument.Section(
                "british_voice_sample",
                "British Voice Sample",
                "Every generation inherits a lodge. Every generation has a choice. Every generation leaves a legacy. " +
                        "This is not a book about recruiting men at any cost. It is a book about becoming a lodge worth joining, " +
                        "communicating honestly, selecting carefully and caring properly for those who enter.");
        runGeneration("British voice sample", Collections.singletonList(sample), false);
    }

    private void generateChapterOne() {
        if (!validKey() || !hasNarrationText()) return;
        for (NarrationDocument.Section section : NarrationDocument.parse(narrationText)) {
            if (section.id.equals("chapter_01")) {
                runGeneration("Chapter 1", Collections.singletonList(section), false);
                return;
            }
        }
        generationStatus.setText("Chapter 1 could not be found in the Narration Master.");
    }

    private void generateFullBook() {
        if (!validKey() || !hasNarrationText()) return;
        runGeneration("Full book", NarrationDocument.parse(narrationText), true);
    }

    private void runGeneration(
            String label,
            List<NarrationDocument.Section> requestedSections,
            boolean skipFinishedChapters) {
        stopRequested = false;
        setGenerationBusy(true);
        generationProgress.setProgress(0);
        final String key = apiKey.getText().toString().trim();
        final int voicePosition = voiceSpinner.getSelectedItemPosition();
        final String voice = API_VOICES[voicePosition];
        final String instructions = COMMON_INSTRUCTIONS + " " + VOICE_STYLES[voicePosition];

        executor.execute(() -> {
            try {
                Set<String> existingNames = skipFinishedChapters
                        ? ChapterAudioStore.existingDisplayNames(this)
                        : new HashSet<>();
                List<NarrationDocument.Section> sections = new ArrayList<>();
                int totalParts = 0;
                for (NarrationDocument.Section section : requestedSections) {
                    String fileName = ChapterAudioStore.fileNameFor(section);
                    if (skipFinishedChapters && existingNames.contains(fileName)) continue;
                    sections.add(section);
                    totalParts += NarrationDocument.chunks(section.text).size();
                }

                if (sections.isEmpty()) {
                    runOnUiThread(() -> {
                        setGenerationBusy(false);
                        generationProgress.setProgress(100);
                        generationStatus.setText("Every chapter is already generated and stored in the Chapter Library.");
                        refreshLibrary(null, false);
                    });
                    return;
                }

                int completedParts = 0;
                Uri lastSavedUri = null;
                boolean stopped = false;

                for (NarrationDocument.Section section : sections) {
                    if (stopRequested) { stopped = true; break; }
                    List<String> textParts = NarrationDocument.chunks(section.text);
                    File buildDirectory = new File(getCacheDir(), "chapter_build_" + section.id);
                    ChapterAudioStore.deleteRecursively(buildDirectory);
                    if (!buildDirectory.mkdirs() && !buildDirectory.isDirectory()) {
                        throw new Exception("Could not create temporary chapter storage.");
                    }
                    List<File> audioParts = new ArrayList<>();
                    boolean chapterComplete = true;
                    try {
                        for (int partIndex = 0; partIndex < textParts.size(); partIndex++) {
                            if (stopRequested) {
                                chapterComplete = false;
                                stopped = true;
                                break;
                            }
                            updateGenerationStatus(label + ": generating part " + (partIndex + 1)
                                    + " of " + textParts.size() + "\n" + section.title);
                            byte[] audio = requestSpeech(key, voice, instructions, textParts.get(partIndex));
                            File partFile = new File(buildDirectory,
                                    String.format(Locale.UK, "part_%03d.aac", partIndex + 1));
                            try (OutputStream output = new FileOutputStream(partFile)) {
                                output.write(audio);
                            }
                            audioParts.add(partFile);
                            completedParts++;
                            int percent = (int)Math.round(completedParts * 100.0 / Math.max(totalParts, 1));
                            updateGenerationProgress(percent);
                        }

                        if (!chapterComplete) break;
                        updateGenerationStatus("Joining " + audioParts.size() + " audio parts into one chapter…\n" + section.title);
                        String fileName = ChapterAudioStore.fileNameFor(section);
                        lastSavedUri = ChapterAudioStore.saveMergedChapter(
                                this,
                                audioParts,
                                fileName,
                                section.title);
                        Uri savedForUi = lastSavedUri;
                        runOnUiThread(() -> refreshLibrary(savedForUi, false));
                    } finally {
                        ChapterAudioStore.deleteRecursively(buildDirectory);
                    }
                }

                Uri finalSavedUri = lastSavedUri;
                boolean finalStopped = stopped;
                runOnUiThread(() -> {
                    setGenerationBusy(false);
                    refreshLibrary(finalSavedUri, finalSavedUri != null);
                    if (finalStopped) {
                        generationStatus.setText("Stopped safely. Finished chapters remain in the Chapter Library; the incomplete chapter was discarded.");
                    } else {
                        generationProgress.setProgress(100);
                        generationStatus.setText("Complete. Each finished chapter is stored as one chapter file in the Chapter Library.");
                    }
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    setGenerationBusy(false);
                    generationStatus.setText("Error: " + exception.getMessage());
                });
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
        InputStream input = responseCode >= 200 && responseCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) > 0) response.write(buffer, 0, count);
        input.close();
        connection.disconnect();
        byte[] data = response.toByteArray();
        if (responseCode < 200 || responseCode >= 300) {
            throw new Exception("OpenAI returned " + responseCode + ": "
                    + new String(data, StandardCharsets.UTF_8));
        }
        return data;
    }

    private void refreshLibrary(Uri selectUri, boolean autoplay) {
        String currentName = currentIndex >= 0 && currentIndex < libraryEntries.size()
                ? libraryEntries.get(currentIndex).displayName
                : null;
        libraryEntries.clear();
        libraryEntries.addAll(ChapterAudioStore.list(this));
        currentIndex = -1;

        if (selectUri != null) {
            for (int index = 0; index < libraryEntries.size(); index++) {
                if (libraryEntries.get(index).uri.toString().equals(selectUri.toString())) {
                    currentIndex = index;
                    break;
                }
            }
        } else if (currentName != null) {
            for (int index = 0; index < libraryEntries.size(); index++) {
                if (libraryEntries.get(index).displayName.equals(currentName)) {
                    currentIndex = index;
                    break;
                }
            }
        }

        chapterAdapter.notifyDataSetChanged();
        updateLibraryStatus();
        setPlayerButtons(currentIndex >= 0 || !libraryEntries.isEmpty());
        if (currentIndex >= 0) {
            chapterList.setSelection(currentIndex);
            if (selectUri != null) loadChapter(currentIndex, autoplay);
        } else if (libraryEntries.isEmpty()) {
            releasePlayer();
            nowPlaying.setText("No chapters generated yet");
            playerTime.setText("0:00 / 0:00");
            playerSeek.setProgress(0);
        }
    }

    private void updateLibraryStatus() {
        if (narrationText.trim().isEmpty()) {
            libraryStatus.setText(libraryEntries.size() + " audio files in the Chapter Library");
            return;
        }
        List<NarrationDocument.Section> expected = NarrationDocument.parse(narrationText);
        Set<String> existing = new HashSet<>();
        for (ChapterAudioStore.Entry entry : libraryEntries) existing.add(entry.displayName);
        int complete = 0;
        for (NarrationDocument.Section section : expected) {
            if (existing.contains(ChapterAudioStore.fileNameFor(section))) complete++;
        }
        libraryStatus.setText(complete + " of " + expected.size()
                + " book sections complete • " + libraryEntries.size() + " audio files stored");
    }

    private void loadChapter(int index, boolean autoplay) {
        if (index < 0 || index >= libraryEntries.size()) return;
        saveCurrentPlaybackPosition();
        releasePlayer();
        currentIndex = index;
        ChapterAudioStore.Entry entry = libraryEntries.get(index);
        nowPlaying.setText("Loading: " + entry.title);
        playerTime.setText("0:00 / " + formatTime(entry.durationMs));
        playerSeek.setProgress(0);
        playPauseButton.setText("Play");
        setPlayerButtons(false);
        chapterAdapter.notifyDataSetChanged();

        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(this, entry.uri);
            mediaPlayer.setOnPreparedListener(player -> {
                playerPrepared = true;
                int duration = player.getDuration();
                int savedPosition = getSavedPlaybackPosition(entry);
                if (savedPosition > 0 && savedPosition < duration - 10000) {
                    player.seekTo(savedPosition);
                } else {
                    clearPlaybackPosition(entry);
                }
                playerSeek.setMax(Math.max(duration, 1));
                playerSeek.setProgress(Math.max(savedPosition, 0));
                playerTime.setText(formatTime(Math.max(savedPosition, 0)) + " / " + formatTime(duration));
                nowPlaying.setText(entry.title);
                setPlayerButtons(true);
                if (autoplay) {
                    player.start();
                    playPauseButton.setText("Pause");
                }
                chapterAdapter.notifyDataSetChanged();
            });
            mediaPlayer.setOnCompletionListener(player -> {
                clearPlaybackPosition(entry);
                playPauseButton.setText("Play");
                chapterAdapter.notifyDataSetChanged();
                if (currentIndex + 1 < libraryEntries.size()) loadChapter(currentIndex + 1, true);
                else {
                    player.seekTo(0);
                    playerSeek.setProgress(0);
                    playerTime.setText("0:00 / " + formatTime(player.getDuration()));
                }
            });
            mediaPlayer.setOnErrorListener((player, what, extra) -> {
                playerPrepared = false;
                nowPlaying.setText("This chapter could not be played. Refresh the library and try again.");
                setPlayerButtons(false);
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception exception) {
            releasePlayer();
            nowPlaying.setText("Could not load the chapter: " + exception.getMessage());
        }
    }

    private void togglePlayback() {
        if (currentIndex < 0 && !libraryEntries.isEmpty()) {
            loadChapter(0, true);
            return;
        }
        if (mediaPlayer == null || !playerPrepared) return;
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                playPauseButton.setText("Play");
                saveCurrentPlaybackPosition();
            } else {
                mediaPlayer.start();
                playPauseButton.setText("Pause");
            }
        } catch (IllegalStateException ignored) {}
    }

    private void stopPlayback() {
        if (mediaPlayer == null || !playerPrepared || currentIndex < 0) return;
        try {
            if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            mediaPlayer.seekTo(0);
            clearPlaybackPosition(libraryEntries.get(currentIndex));
            playerSeek.setProgress(0);
            playerTime.setText("0:00 / " + formatTime(mediaPlayer.getDuration()));
            playPauseButton.setText("Play");
            chapterAdapter.notifyDataSetChanged();
        } catch (IllegalStateException ignored) {}
    }

    private void playPrevious() {
        if (mediaPlayer != null && playerPrepared) {
            try {
                if (mediaPlayer.getCurrentPosition() > 5000) {
                    mediaPlayer.seekTo(0);
                    return;
                }
            } catch (IllegalStateException ignored) {}
        }
        if (currentIndex > 0) loadChapter(currentIndex - 1, true);
        else if (!libraryEntries.isEmpty()) loadChapter(0, true);
    }

    private void playNext() {
        if (libraryEntries.isEmpty()) return;
        if (currentIndex < 0) loadChapter(0, true);
        else if (currentIndex + 1 < libraryEntries.size()) loadChapter(currentIndex + 1, true);
    }

    private void saveCurrentPlaybackPosition() {
        if (mediaPlayer == null || !playerPrepared || currentIndex < 0 || currentIndex >= libraryEntries.size()) return;
        try {
            savePlaybackPosition(
                    libraryEntries.get(currentIndex),
                    mediaPlayer.getCurrentPosition(),
                    mediaPlayer.getDuration());
        } catch (IllegalStateException ignored) {}
    }

    private void savePlaybackPosition(ChapterAudioStore.Entry entry, int position, int duration) {
        if (duration > 0 && position >= duration - 10000) {
            clearPlaybackPosition(entry);
            return;
        }
        preferences.edit().putInt(playbackKey(entry), Math.max(position, 0)).apply();
    }

    private int getSavedPlaybackPosition(ChapterAudioStore.Entry entry) {
        return preferences.getInt(playbackKey(entry), 0);
    }

    private void clearPlaybackPosition(ChapterAudioStore.Entry entry) {
        preferences.edit().remove(playbackKey(entry)).apply();
    }

    private String playbackKey(ChapterAudioStore.Entry entry) {
        return PREF_PLAYBACK_PREFIX + Integer.toHexString(entry.displayName.hashCode());
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try { mediaPlayer.reset(); } catch (Exception ignored) {}
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        playerPrepared = false;
        playPauseButton.setText("Play");
    }

    private void setPlayerButtons(boolean enabled) {
        previousButton.setEnabled(enabled && !libraryEntries.isEmpty());
        playPauseButton.setEnabled(enabled && !libraryEntries.isEmpty());
        nextButton.setEnabled(enabled && !libraryEntries.isEmpty());
        playerStopButton.setEnabled(enabled && currentIndex >= 0);
    }

    private void setGenerationBusy(boolean busy) {
        runOnUiThread(() -> {
            selectNarrationButton.setEnabled(!busy);
            saveKeyButton.setEnabled(!busy);
            forgetKeyButton.setEnabled(!busy);
            sampleButton.setEnabled(!busy);
            chapterOneButton.setEnabled(!busy);
            fullBookButton.setEnabled(!busy);
            stopGenerationButton.setEnabled(busy);
        });
    }

    private void updateGenerationStatus(String text) {
        runOnUiThread(() -> generationStatus.setText(text));
    }

    private void updateGenerationProgress(int value) {
        runOnUiThread(() -> generationProgress.setProgress(value));
    }

    private String formatTime(long milliseconds) {
        long totalSeconds = Math.max(milliseconds, 0L) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) return String.format(Locale.UK, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.UK, "%d:%02d", minutes, seconds);
    }

    @Override protected void onPause() {
        saveCurrentPlaybackPosition();
        super.onPause();
    }

    @Override protected void onDestroy() {
        playerHandler.removeCallbacks(playerProgressUpdater);
        saveCurrentPlaybackPosition();
        releasePlayer();
        executor.shutdownNow();
        super.onDestroy();
    }

    private final class ChapterAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(MainActivity.this);

        @Override public int getCount() { return libraryEntries.size(); }
        @Override public ChapterAudioStore.Entry getItem(int position) { return libraryEntries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) row = inflater.inflate(android.R.layout.simple_list_item_2, parent, false);
            TextView title = row.findViewById(android.R.id.text1);
            TextView detail = row.findViewById(android.R.id.text2);
            ChapterAudioStore.Entry entry = getItem(position);
            String prefix = position == currentIndex ? "▶  " : "";
            title.setText(prefix + entry.title);
            title.setTextColor(getResources().getColor(R.color.navy));
            int savedPosition = getSavedPlaybackPosition(entry);
            String state = savedPosition > 0
                    ? "Continue at " + formatTime(savedPosition)
                    : "Ready";
            detail.setText(formatTime(entry.durationMs) + "  •  " + state);
            row.setActivated(position == currentIndex);
            return row;
        }
    }
}
