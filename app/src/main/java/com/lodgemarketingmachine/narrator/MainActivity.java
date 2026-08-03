package com.lodgemarketingmachine.narrator;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.Spanned;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "lodge_marketing_machine_reader";
    private static final String PREF_LAST_CHAPTER = "last_chapter";
    private static final String PREF_TEXT_SIZE = "text_size";
    private static final String PREF_DARK_MODE = "dark_mode";
    private static final String PREF_SPEED = "speed_position";
    private static final String PREF_AUDIO_POSITION_PREFIX = "audio_position_";
    private static final String PREF_READ_POSITION_PREFIX = "read_position_";

    private static final String[] SPEED_LABELS = {
            "0.75×", "1.0×", "1.25×", "1.5×", "1.75×", "2.0×"
    };
    private static final float[] SPEED_VALUES = {
            0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f
    };
    private static final String[] SLEEP_LABELS = {
            "Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes"
    };
    private static final int[] SLEEP_MINUTES = {0, 15, 30, 45, 60};

    private SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Chapter> chapters = new ArrayList<>();

    private LinearLayout root;
    private LinearLayout headerPanel;
    private TabHost tabHost;
    private ImageView coverImage;
    private TextView headerTitle;
    private TextView headerSubtitle;
    private TextView bookTitle;
    private TextView bookAuthor;
    private TextView libraryStatus;
    private ListView libraryList;
    private Button continueButton;

    private TextView readTitle;
    private TextView readBody;
    private ScrollView readScroll;
    private Button fontDownButton;
    private Button fontUpButton;
    private Button darkModeButton;
    private Button readPreviousButton;
    private Button readNextButton;
    private Button listenThisButton;

    private Spinner listenChapterSpinner;
    private Spinner speedSpinner;
    private Spinner sleepSpinner;
    private TextView nowPlaying;
    private TextView playerTime;
    private TextView sleepStatus;
    private SeekBar playerSeek;
    private Button playerPreviousButton;
    private Button playPauseButton;
    private Button playerNextButton;
    private Button playerStopButton;

    private ChapterAdapter libraryAdapter;
    private ArrayAdapter<String> chapterSpinnerAdapter;

    private int currentChapterIndex = 0;
    private int currentAudioIndex = -1;
    private boolean suppressChapterSpinner = false;
    private boolean suppressSleepSpinner = false;
    private float readerTextSize = 18f;
    private boolean darkMode = false;

    private MediaPlayer mediaPlayer;
    private boolean playerPrepared = false;
    private long lastAudioSaveAt = 0L;
    private long sleepDeadline = 0L;

    private final Runnable sleepRunnable = new Runnable() {
        @Override public void run() {
            if (mediaPlayer != null && playerPrepared) {
                try {
                    if (mediaPlayer.isPlaying()) mediaPlayer.pause();
                } catch (IllegalStateException ignored) {}
            }
            sleepDeadline = 0L;
            suppressSleepSpinner = true;
            sleepSpinner.setSelection(0);
            suppressSleepSpinner = false;
            sleepStatus.setText("Sleep timer finished. Playback paused.");
            playPauseButton.setText("Play");
        }
    };

    private final Runnable playerProgressUpdater = new Runnable() {
        @Override public void run() {
            if (mediaPlayer != null && playerPrepared) {
                try {
                    int position = mediaPlayer.getCurrentPosition();
                    int duration = mediaPlayer.getDuration();
                    playerSeek.setMax(Math.max(duration, 1));
                    playerSeek.setProgress(position);
                    playerTime.setText(formatTime(position) + " / " + formatTime(duration));
                    long now = System.currentTimeMillis();
                    if (now - lastAudioSaveAt > 3000L) {
                        saveAudioPosition(position, duration);
                        lastAudioSaveAt = now;
                    }
                } catch (IllegalStateException ignored) {}
            }

            if (sleepDeadline > 0L) {
                long remaining = Math.max(0L, sleepDeadline - System.currentTimeMillis());
                sleepStatus.setText("Sleep timer: " + formatTimeLong(remaining));
            }
            handler.postDelayed(this, 500L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        bindViews();
        setupTabs();
        setupStaticControls();
        loadBook();
        loadCover();
        restoreReaderSettings();
        setupBookControls();
        int savedChapter = preferences.getInt(PREF_LAST_CHAPTER, 0);
        selectChapter(clampChapter(savedChapter), false);
        handler.post(playerProgressUpdater);
    }

    private void bindViews() {
        root = findViewById(R.id.root);
        headerPanel = findViewById(R.id.headerPanel);
        headerTitle = findViewById(R.id.headerTitle);
        headerSubtitle = findViewById(R.id.headerSubtitle);
        coverImage = findViewById(R.id.coverImage);
        bookTitle = findViewById(R.id.bookTitle);
        bookAuthor = findViewById(R.id.bookAuthor);
        libraryStatus = findViewById(R.id.libraryStatus);
        libraryList = findViewById(R.id.libraryList);
        continueButton = findViewById(R.id.continueButton);

        readTitle = findViewById(R.id.readTitle);
        readBody = findViewById(R.id.readBody);
        readScroll = findViewById(R.id.readScroll);
        fontDownButton = findViewById(R.id.fontDownButton);
        fontUpButton = findViewById(R.id.fontUpButton);
        darkModeButton = findViewById(R.id.darkModeButton);
        readPreviousButton = findViewById(R.id.readPreviousButton);
        readNextButton = findViewById(R.id.readNextButton);
        listenThisButton = findViewById(R.id.listenThisButton);

        listenChapterSpinner = findViewById(R.id.listenChapterSpinner);
        speedSpinner = findViewById(R.id.speedSpinner);
        sleepSpinner = findViewById(R.id.sleepSpinner);
        nowPlaying = findViewById(R.id.nowPlaying);
        playerTime = findViewById(R.id.playerTime);
        sleepStatus = findViewById(R.id.sleepStatus);
        playerSeek = findViewById(R.id.playerSeek);
        playerPreviousButton = findViewById(R.id.playerPreviousButton);
        playPauseButton = findViewById(R.id.playPauseButton);
        playerNextButton = findViewById(R.id.playerNextButton);
        playerStopButton = findViewById(R.id.playerStopButton);
    }

    private void setupTabs() {
        tabHost = findViewById(android.R.id.tabhost);
        tabHost.setup();
        tabHost.addTab(tabHost.newTabSpec("library").setIndicator("Library").setContent(R.id.libraryTab));
        tabHost.addTab(tabHost.newTabSpec("read").setIndicator("Read").setContent(R.id.readTab));
        tabHost.addTab(tabHost.newTabSpec("listen").setIndicator("Listen").setContent(R.id.listenTab));
    }

    private void setupStaticControls() {
        speedSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                SPEED_LABELS));
        sleepSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                SLEEP_LABELS));

        speedSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putInt(PREF_SPEED, position).apply();
                applyPlaybackSpeed();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        sleepSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!suppressSleepSpinner) setSleepTimer(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        fontDownButton.setOnClickListener(view -> changeTextSize(-1f));
        fontUpButton.setOnClickListener(view -> changeTextSize(1f));
        darkModeButton.setOnClickListener(view -> {
            darkMode = !darkMode;
            preferences.edit().putBoolean(PREF_DARK_MODE, darkMode).apply();
            applyTheme();
        });

        readPreviousButton.setOnClickListener(view -> selectChapter(currentChapterIndex - 1, true));
        readNextButton.setOnClickListener(view -> selectChapter(currentChapterIndex + 1, true));
        listenThisButton.setOnClickListener(view -> {
            tabHost.setCurrentTabByTag("listen");
            loadAudioForChapter(currentChapterIndex, true);
        });

        playerPreviousButton.setOnClickListener(view -> playPreviousAudio());
        playPauseButton.setOnClickListener(view -> togglePlayback());
        playerNextButton.setOnClickListener(view -> playNextAudio());
        playerStopButton.setOnClickListener(view -> stopPlayback());

        playerSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null && playerPrepared) {
                    try { mediaPlayer.seekTo(progress); } catch (IllegalStateException ignored) {}
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { saveCurrentAudioPosition(); }
        });

        readScroll.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (!chapters.isEmpty()) {
                preferences.edit().putInt(
                        PREF_READ_POSITION_PREFIX + chapters.get(currentChapterIndex).id,
                        scrollY).apply();
            }
        });
    }

    private void loadBook() {
        chapters.clear();
        String title = "The Lodge Marketing Machine";
        String author = "W.Bro Calam";
        String subtitle = "Reader Edition";
        try {
            JSONObject book = new JSONObject(readAssetText("book.json"));
            title = book.optString("title", title);
            author = book.optString("author", author);
            subtitle = book.optString("subtitle", subtitle);
            JSONArray chapterArray = book.getJSONArray("chapters");
            for (int index = 0; index < chapterArray.length(); index++) {
                JSONObject item = chapterArray.getJSONObject(index);
                chapters.add(new Chapter(
                        item.optString("id", "section_" + index),
                        item.optString("title", "Section " + (index + 1)),
                        item.optString("html", ""),
                        item.optString("audio", "")));
            }
        } catch (Exception exception) {
            chapters.add(new Chapter(
                    "unavailable",
                    "Book content unavailable",
                    "<p>The book package could not be loaded.</p>",
                    ""));
        }
        bookTitle.setText(title);
        bookAuthor.setText(author);
        headerTitle.setText(title.toUpperCase(Locale.UK));
        headerSubtitle.setText(subtitle);
        libraryStatus.setText(chapters.size() + " sections included for offline reading and listening.");
    }

    private void loadCover() {
        try (InputStream input = getAssets().open("cover.png")) {
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            if (bitmap != null) coverImage.setImageBitmap(bitmap);
        } catch (Exception exception) {
            coverImage.setVisibility(View.GONE);
        }
    }

    private void restoreReaderSettings() {
        readerTextSize = preferences.getFloat(PREF_TEXT_SIZE, 18f);
        if (readerTextSize < 14f || readerTextSize > 30f) readerTextSize = 18f;
        darkMode = preferences.getBoolean(PREF_DARK_MODE, false);
        int speedPosition = preferences.getInt(PREF_SPEED, 1);
        if (speedPosition < 0 || speedPosition >= SPEED_VALUES.length) speedPosition = 1;
        speedSpinner.setSelection(speedPosition);
        readBody.setTextSize(readerTextSize);
        applyTheme();
    }

    private void setupBookControls() {
        libraryAdapter = new ChapterAdapter();
        libraryList.setAdapter(libraryAdapter);
        libraryList.setOnItemClickListener((parent, view, position, id) -> {
            selectChapter(position, false);
            tabHost.setCurrentTabByTag("read");
        });

        List<String> titles = new ArrayList<>();
        for (Chapter chapter : chapters) titles.add(chapter.title);
        chapterSpinnerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                titles);
        listenChapterSpinner.setAdapter(chapterSpinnerAdapter);
        listenChapterSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!suppressChapterSpinner && position != currentAudioIndex) {
                    loadAudioForChapter(position, false);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        continueButton.setOnClickListener(view -> {
            int chapter = clampChapter(preferences.getInt(PREF_LAST_CHAPTER, 0));
            selectChapter(chapter, false);
            tabHost.setCurrentTabByTag("read");
        });
    }

    private void selectChapter(int requestedIndex, boolean keepReadTab) {
        if (chapters.isEmpty()) return;
        int index = clampChapter(requestedIndex);
        currentChapterIndex = index;
        Chapter chapter = chapters.get(index);
        preferences.edit().putInt(PREF_LAST_CHAPTER, index).apply();
        continueButton.setText("Continue: " + chapter.title);
        readTitle.setText(chapter.title);
        Spanned formatted = Html.fromHtml(chapter.html, Html.FROM_HTML_MODE_LEGACY);
        readBody.setText(formatted);
        readBody.setTextSize(readerTextSize);
        readPreviousButton.setEnabled(index > 0);
        readNextButton.setEnabled(index < chapters.size() - 1);
        listenThisButton.setEnabled(!chapter.audio.isEmpty());
        if (libraryAdapter != null) libraryAdapter.notifyDataSetChanged();

        int savedScroll = preferences.getInt(PREF_READ_POSITION_PREFIX + chapter.id, 0);
        readScroll.post(() -> readScroll.scrollTo(0, Math.max(0, savedScroll)));
        if (keepReadTab) tabHost.setCurrentTabByTag("read");
    }

    private void loadAudioForChapter(int requestedIndex, boolean autoPlay) {
        if (chapters.isEmpty()) return;
        int index = clampChapter(requestedIndex);
        Chapter chapter = chapters.get(index);
        if (chapter.audio.isEmpty()) {
            Toast.makeText(this, "No audio is included for this section.", Toast.LENGTH_LONG).show();
            return;
        }

        saveCurrentAudioPosition();
        releasePlayer();
        currentAudioIndex = index;
        currentChapterIndex = index;
        preferences.edit().putInt(PREF_LAST_CHAPTER, index).apply();
        suppressChapterSpinner = true;
        listenChapterSpinner.setSelection(index);
        suppressChapterSpinner = false;
        nowPlaying.setText("Loading: " + chapter.title);
        playerTime.setText("0:00 / 0:00");
        playerSeek.setProgress(0);
        setPlayerControlsEnabled(false);

        try {
            AssetFileDescriptor descriptor = getAssets().openFd("audio/" + chapter.audio);
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            mediaPlayer.setDataSource(
                    descriptor.getFileDescriptor(),
                    descriptor.getStartOffset(),
                    descriptor.getLength());
            descriptor.close();

            mediaPlayer.setOnPreparedListener(player -> {
                playerPrepared = true;
                int duration = player.getDuration();
                playerSeek.setMax(Math.max(duration, 1));
                int savedPosition = preferences.getInt(PREF_AUDIO_POSITION_PREFIX + chapter.id, 0);
                if (savedPosition > 0 && savedPosition < duration - 3000) player.seekTo(savedPosition);
                nowPlaying.setText(chapter.title);
                playerTime.setText(formatTime(player.getCurrentPosition()) + " / " + formatTime(duration));
                applyPlaybackSpeed();
                setPlayerControlsEnabled(true);
                if (autoPlay) {
                    player.start();
                    playPauseButton.setText("Pause");
                } else {
                    playPauseButton.setText("Play");
                }
                if (libraryAdapter != null) libraryAdapter.notifyDataSetChanged();
            });

            mediaPlayer.setOnCompletionListener(player -> {
                preferences.edit().putInt(PREF_AUDIO_POSITION_PREFIX + chapter.id, 0).apply();
                playPauseButton.setText("Play");
                playNextAudio();
            });

            mediaPlayer.setOnErrorListener((player, what, extra) -> {
                playerPrepared = false;
                nowPlaying.setText("This chapter audio could not be played.");
                setPlayerControlsEnabled(false);
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception exception) {
            releasePlayer();
            nowPlaying.setText("Audio unavailable: " + chapter.title);
            Toast.makeText(this, "The embedded audio could not be opened.", Toast.LENGTH_LONG).show();
        }
    }

    private void togglePlayback() {
        if (currentAudioIndex < 0) {
            loadAudioForChapter(currentChapterIndex, true);
            return;
        }
        if (mediaPlayer == null || !playerPrepared) return;
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                playPauseButton.setText("Play");
                saveCurrentAudioPosition();
            } else {
                mediaPlayer.start();
                playPauseButton.setText("Pause");
            }
        } catch (IllegalStateException ignored) {}
    }

    private void playPreviousAudio() {
        int target = findAudioChapter(currentAudioIndex < 0 ? currentChapterIndex - 1 : currentAudioIndex - 1, -1);
        if (target >= 0) loadAudioForChapter(target, true);
    }

    private void playNextAudio() {
        int target = findAudioChapter(currentAudioIndex < 0 ? currentChapterIndex + 1 : currentAudioIndex + 1, 1);
        if (target >= 0) loadAudioForChapter(target, true);
        else {
            playPauseButton.setText("Play");
            Toast.makeText(this, "You have reached the end of the audiobook.", Toast.LENGTH_SHORT).show();
        }
    }

    private int findAudioChapter(int start, int direction) {
        int index = start;
        while (index >= 0 && index < chapters.size()) {
            if (!chapters.get(index).audio.isEmpty()) return index;
            index += direction;
        }
        return -1;
    }

    private void stopPlayback() {
        if (mediaPlayer == null || !playerPrepared || currentAudioIndex < 0) return;
        try {
            if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            mediaPlayer.seekTo(0);
            playerSeek.setProgress(0);
            playerTime.setText("0:00 / " + formatTime(mediaPlayer.getDuration()));
            preferences.edit().putInt(
                    PREF_AUDIO_POSITION_PREFIX + chapters.get(currentAudioIndex).id,
                    0).apply();
            playPauseButton.setText("Play");
        } catch (IllegalStateException ignored) {}
    }

    private void applyPlaybackSpeed() {
        if (mediaPlayer == null || !playerPrepared) return;
        int position = speedSpinner.getSelectedItemPosition();
        if (position < 0 || position >= SPEED_VALUES.length) position = 1;
        try {
            PlaybackParams params = mediaPlayer.getPlaybackParams();
            params.setSpeed(SPEED_VALUES[position]);
            params.setPitch(1f);
            mediaPlayer.setPlaybackParams(params);
        } catch (Exception ignored) {}
    }

    private void setSleepTimer(int spinnerPosition) {
        handler.removeCallbacks(sleepRunnable);
        int position = spinnerPosition;
        if (position < 0 || position >= SLEEP_MINUTES.length) position = 0;
        int minutes = SLEEP_MINUTES[position];
        if (minutes <= 0) {
            sleepDeadline = 0L;
            sleepStatus.setText("Sleep timer off");
            return;
        }
        sleepDeadline = System.currentTimeMillis() + minutes * 60_000L;
        handler.postDelayed(sleepRunnable, minutes * 60_000L);
        sleepStatus.setText("Sleep timer: " + minutes + " minutes");
    }

    private void setPlayerControlsEnabled(boolean enabled) {
        playPauseButton.setEnabled(enabled);
        playerStopButton.setEnabled(enabled);
        playerPreviousButton.setEnabled(enabled && findAudioChapter(currentAudioIndex - 1, -1) >= 0);
        playerNextButton.setEnabled(enabled && findAudioChapter(currentAudioIndex + 1, 1) >= 0);
    }

    private void saveCurrentAudioPosition() {
        if (mediaPlayer == null || !playerPrepared) return;
        try { saveAudioPosition(mediaPlayer.getCurrentPosition(), mediaPlayer.getDuration()); }
        catch (IllegalStateException ignored) {}
    }

    private void saveAudioPosition(int position, int duration) {
        if (currentAudioIndex < 0 || currentAudioIndex >= chapters.size()) return;
        int value = position;
        if (duration > 0 && position >= duration - 3000) value = 0;
        preferences.edit().putInt(
                PREF_AUDIO_POSITION_PREFIX + chapters.get(currentAudioIndex).id,
                Math.max(value, 0)).apply();
    }

    private void changeTextSize(float change) {
        readerTextSize = Math.max(14f, Math.min(30f, readerTextSize + change));
        readBody.setTextSize(readerTextSize);
        preferences.edit().putFloat(PREF_TEXT_SIZE, readerTextSize).apply();
    }

    private void applyTheme() {
        int background = darkMode ? Color.rgb(18, 20, 24) : Color.rgb(247, 244, 236);
        int panel = darkMode ? Color.rgb(30, 34, 41) : Color.WHITE;
        int primary = darkMode ? Color.rgb(240, 198, 102) : Color.rgb(7, 31, 61);
        int text = darkMode ? Color.rgb(236, 239, 244) : Color.rgb(32, 32, 32);
        int secondary = darkMode ? Color.rgb(180, 185, 194) : Color.rgb(85, 85, 85);

        root.setBackgroundColor(background);
        headerPanel.setBackgroundColor(background);
        findViewById(R.id.libraryTab).setBackgroundColor(background);
        findViewById(R.id.readTab).setBackgroundColor(background);
        findViewById(R.id.listenTab).setBackgroundColor(background);
        libraryList.setBackgroundColor(panel);
        readScroll.setBackgroundColor(panel);

        headerTitle.setTextColor(primary);
        headerSubtitle.setTextColor(darkMode ? Color.rgb(240, 198, 102) : Color.rgb(216, 170, 60));
        bookTitle.setTextColor(primary);
        bookAuthor.setTextColor(secondary);
        libraryStatus.setTextColor(secondary);
        readTitle.setTextColor(primary);
        readBody.setTextColor(text);
        nowPlaying.setTextColor(primary);
        playerTime.setTextColor(secondary);
        sleepStatus.setTextColor(secondary);
        ((TextView)findViewById(R.id.audioDisclosure)).setTextColor(secondary);
        darkModeButton.setText(darkMode ? "Light mode" : "Dark mode");

        Window window = getWindow();
        window.setStatusBarColor(darkMode ? Color.rgb(7, 11, 17) : Color.rgb(7, 31, 61));
        window.setNavigationBarColor(darkMode ? Color.rgb(7, 11, 17) : Color.rgb(7, 31, 61));
        if (libraryAdapter != null) libraryAdapter.notifyDataSetChanged();
    }

    private int clampChapter(int index) {
        if (chapters.isEmpty()) return 0;
        return Math.max(0, Math.min(chapters.size() - 1, index));
    }

    private String readAssetText(String path) throws Exception {
        try (InputStream input = getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String formatTime(int milliseconds) {
        int totalSeconds = Math.max(milliseconds, 0) / 1000;
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) return String.format(Locale.UK, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.UK, "%d:%02d", minutes, seconds);
    }

    private String formatTimeLong(long milliseconds) {
        long totalSeconds = Math.max(milliseconds, 0L) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.UK, "%d:%02d remaining", minutes, seconds);
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try { mediaPlayer.reset(); } catch (Exception ignored) {}
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        playerPrepared = false;
        setPlayerControlsEnabled(false);
    }

    @Override protected void onStop() {
        saveCurrentAudioPosition();
        if (!chapters.isEmpty()) {
            preferences.edit().putInt(
                    PREF_READ_POSITION_PREFIX + chapters.get(currentChapterIndex).id,
                    readScroll.getScrollY()).apply();
        }
        super.onStop();
    }

    @Override public void onBackPressed() {
        if (tabHost.getCurrentTab() != 0) {
            tabHost.setCurrentTab(0);
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        saveCurrentAudioPosition();
        handler.removeCallbacks(playerProgressUpdater);
        handler.removeCallbacks(sleepRunnable);
        releasePlayer();
        super.onDestroy();
    }

    private class ChapterAdapter extends BaseAdapter {
        @Override public int getCount() { return chapters.size(); }
        @Override public Object getItem(int position) { return chapters.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(MainActivity.this)
                        .inflate(android.R.layout.simple_list_item_2, parent, false);
            }
            Chapter chapter = chapters.get(position);
            TextView title = view.findViewById(android.R.id.text1);
            TextView detail = view.findViewById(android.R.id.text2);
            title.setText(chapter.title);
            int savedAudio = preferences.getInt(PREF_AUDIO_POSITION_PREFIX + chapter.id, 0);
            String state = chapter.audio.isEmpty() ? "Read offline" : "Read and listen offline";
            if (savedAudio > 0) state += " • listening position saved";
            detail.setText(state);

            int primary = darkMode ? Color.rgb(236, 239, 244) : Color.rgb(7, 31, 61);
            int secondary = darkMode ? Color.rgb(180, 185, 194) : Color.rgb(90, 90, 90);
            int selected = darkMode ? Color.rgb(49, 57, 69) : Color.rgb(240, 230, 205);
            int normal = darkMode ? Color.rgb(30, 34, 41) : Color.WHITE;
            title.setTextColor(primary);
            detail.setTextColor(secondary);
            view.setBackgroundColor(position == currentChapterIndex ? selected : normal);
            return view;
        }
    }

    private static class Chapter {
        final String id;
        final String title;
        final String html;
        final String audio;

        Chapter(String id, String title, String html, String audio) {
            this.id = id;
            this.title = title;
            this.html = html;
            this.audio = audio;
        }
    }
}
