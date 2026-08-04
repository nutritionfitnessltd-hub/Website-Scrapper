package com.lodgemarketingmachine.narrator;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Html;
import android.text.Spanned;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int PICK_BOOK_FILE = 201;
    private static final int PICK_AUDIO_FOLDER = 202;

    private static final String PREFS_NAME = "lodge_reader_external_media";
    private static final String PREF_BOOK_URI = "book_uri";
    private static final String PREF_AUDIO_FOLDER_URI = "audio_folder_uri";
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
    private final List<AudioFile> audioFiles = new ArrayList<>();

    private LinearLayout root;
    private LinearLayout headerPanel;
    private TabHost tabHost;

    private Button chooseBookButton;
    private Button chooseAudioFolderButton;
    private Button refreshAudioButton;
    private Button continueButton;
    private TextView bookFileStatus;
    private TextView audioFolderStatus;
    private TextView libraryStatus;
    private ListView libraryList;

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

    private Uri selectedBookUri;
    private Uri selectedAudioFolderUri;
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
        restoreReaderSettings();
        restoreSelectedLocations();
        handler.post(playerProgressUpdater);
    }

    private void bindViews() {
        root = findViewById(R.id.root);
        headerPanel = findViewById(R.id.headerPanel);
        chooseBookButton = findViewById(R.id.chooseBookButton);
        chooseAudioFolderButton = findViewById(R.id.chooseAudioFolderButton);
        refreshAudioButton = findViewById(R.id.refreshAudioButton);
        continueButton = findViewById(R.id.continueButton);
        bookFileStatus = findViewById(R.id.bookFileStatus);
        audioFolderStatus = findViewById(R.id.audioFolderStatus);
        libraryStatus = findViewById(R.id.libraryStatus);
        libraryList = findViewById(R.id.libraryList);

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
        chooseBookButton.setOnClickListener(view -> chooseBookFile());
        chooseAudioFolderButton.setOnClickListener(view -> chooseAudioFolder());
        refreshAudioButton.setOnClickListener(view -> {
            if (selectedAudioFolderUri == null) chooseAudioFolder();
            else scanAudioFolder(selectedAudioFolderUri);
        });
        continueButton.setOnClickListener(view -> {
            if (chapters.isEmpty()) {
                Toast.makeText(this, "Choose the book file first.", Toast.LENGTH_LONG).show();
                return;
            }
            selectChapter(preferences.getInt(PREF_LAST_CHAPTER, 0), false);
            tabHost.setCurrentTabByTag("read");
        });

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

        setPlayerControlsEnabled(false);
        rebuildChapterControls();
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

    private void restoreSelectedLocations() {
        String bookUriValue = preferences.getString(PREF_BOOK_URI, "");
        if (!bookUriValue.isEmpty()) {
            try {
                selectedBookUri = Uri.parse(bookUriValue);
                loadBookFile(selectedBookUri, false);
            } catch (Exception exception) {
                selectedBookUri = null;
                preferences.edit().remove(PREF_BOOK_URI).apply();
                bookFileStatus.setText("Choose Narration_Master.md again");
            }
        }

        String folderUriValue = preferences.getString(PREF_AUDIO_FOLDER_URI, "");
        if (!folderUriValue.isEmpty()) {
            try {
                selectedAudioFolderUri = Uri.parse(folderUriValue);
                scanAudioFolder(selectedAudioFolderUri);
            } catch (Exception exception) {
                selectedAudioFolderUri = null;
                preferences.edit().remove(PREF_AUDIO_FOLDER_URI).apply();
                audioFolderStatus.setText("Choose the audio folder again");
            }
        }
    }

    private void chooseBookFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/plain", "text/markdown", "text/x-markdown", "application/octet-stream"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_BOOK_FILE);
    }

    private void chooseAudioFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, PICK_AUDIO_FOLDER);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}

        if (requestCode == PICK_BOOK_FILE) {
            selectedBookUri = uri;
            preferences.edit().putString(PREF_BOOK_URI, uri.toString()).apply();
            loadBookFile(uri, true);
        } else if (requestCode == PICK_AUDIO_FOLDER) {
            selectedAudioFolderUri = uri;
            preferences.edit().putString(PREF_AUDIO_FOLDER_URI, uri.toString()).apply();
            scanAudioFolder(uri);
        }
    }

    private void loadBookFile(Uri uri, boolean announce) {
        try {
            String markdown = readTextUri(uri);
            List<Chapter> parsed = parseNarrationMaster(markdown);
            if (parsed.size() < 5) throw new Exception("The selected file does not look like the Narration Master.");
            chapters.clear();
            chapters.addAll(parsed);
            bookFileStatus.setText("Book: " + displayName(uri));
            mapAudioFilesToChapters();
            rebuildChapterControls();
            int saved = clampChapter(preferences.getInt(PREF_LAST_CHAPTER, 0));
            selectChapter(saved, false);
            updateLibraryStatus();
            if (announce) Toast.makeText(this, chapters.size() + " book sections loaded.", Toast.LENGTH_SHORT).show();
        } catch (Exception exception) {
            chapters.clear();
            rebuildChapterControls();
            bookFileStatus.setText("Could not open book: " + exception.getMessage());
            libraryStatus.setText("Choose Narration_Master.md");
        }
    }

    private String readTextUri(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new Exception("File could not be read.");
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {}
        return "Selected file";
    }

    private List<Chapter> parseNarrationMaster(String markdown) {
        String clean = markdown.replace("\r\n", "\n").replace('\r', '\n');
        Pattern headingPattern = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
        Matcher matcher = headingPattern.matcher(clean);
        List<Heading> headings = new ArrayList<>();
        while (matcher.find()) headings.add(new Heading(matcher.start(), matcher.end(), matcher.group(1).trim()));

        List<Chapter> result = new ArrayList<>();
        int beforeIndex = -1;
        for (int index = 0; index < headings.size(); index++) {
            if (headings.get(index).title.equalsIgnoreCase("Before We Begin")) {
                beforeIndex = index;
                break;
            }
        }

        if (beforeIndex > 0) {
            String frontMatter = clean.substring(0, headings.get(beforeIndex).start).trim();
            result.add(new Chapter("front_matter", "Front Matter", markdownToHtml(frontMatter)));
        }

        int startIndex = beforeIndex >= 0 ? beforeIndex : 0;
        for (int index = startIndex; index < headings.size(); index++) {
            Heading heading = headings.get(index);
            int end = index + 1 < headings.size() ? headings.get(index + 1).start : clean.length();
            String body = clean.substring(heading.end, end).trim();
            if (heading.title.toLowerCase(Locale.UK).contains("narration master") ||
                    heading.title.equalsIgnoreCase("AI Disclosure and Title")) {
                continue;
            }
            result.add(new Chapter(sectionId(heading.title), heading.title, markdownToHtml(body)));
        }
        return result;
    }

    private String sectionId(String title) {
        Matcher chapterNumber = Pattern.compile("(?i)^Chapter\\s+(\\d+)").matcher(title);
        if (chapterNumber.find()) {
            return String.format(Locale.UK, "chapter_%02d", Integer.parseInt(chapterNumber.group(1)));
        }
        return normalise(title).replace(' ', '_');
    }

    private String markdownToHtml(String markdown) {
        StringBuilder html = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();
        boolean listOpen = false;
        for (String rawLine : markdown.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                if (paragraph.length() > 0) {
                    html.append("<p>").append(inlineMarkdown(paragraph.toString())).append("</p>");
                    paragraph.setLength(0);
                }
                if (listOpen) {
                    html.append("</ul>");
                    listOpen = false;
                }
                continue;
            }

            if (line.startsWith("### ")) {
                flushParagraph(html, paragraph);
                if (listOpen) { html.append("</ul>"); listOpen = false; }
                html.append("<h3>").append(inlineMarkdown(line.substring(4))).append("</h3>");
            } else if (line.startsWith("## ")) {
                flushParagraph(html, paragraph);
                if (listOpen) { html.append("</ul>"); listOpen = false; }
                html.append("<h2>").append(inlineMarkdown(line.substring(3))).append("</h2>");
            } else if (line.startsWith("# ")) {
                flushParagraph(html, paragraph);
                if (listOpen) { html.append("</ul>"); listOpen = false; }
                html.append("<h1>").append(inlineMarkdown(line.substring(2))).append("</h1>");
            } else if (line.startsWith("> ")) {
                flushParagraph(html, paragraph);
                if (listOpen) { html.append("</ul>"); listOpen = false; }
                html.append("<blockquote>").append(inlineMarkdown(line.substring(2))).append("</blockquote>");
            } else if (line.matches("^[-*]\\s+.*")) {
                flushParagraph(html, paragraph);
                if (!listOpen) { html.append("<ul>"); listOpen = true; }
                html.append("<li>").append(inlineMarkdown(line.substring(2))).append("</li>");
            } else {
                if (listOpen) { html.append("</ul>"); listOpen = false; }
                if (paragraph.length() > 0) paragraph.append(' ');
                paragraph.append(line);
            }
        }
        flushParagraph(html, paragraph);
        if (listOpen) html.append("</ul>");
        return html.toString();
    }

    private void flushParagraph(StringBuilder html, StringBuilder paragraph) {
        if (paragraph.length() > 0) {
            html.append("<p>").append(inlineMarkdown(paragraph.toString())).append("</p>");
            paragraph.setLength(0);
        }
    }

    private String inlineMarkdown(String value) {
        String escaped = escapeHtml(value);
        escaped = escaped.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        escaped = escaped.replaceAll("(?<!\\*)\\*([^*]+?)\\*(?!\\*)", "<i>$1</i>");
        return escaped;
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void scanAudioFolder(Uri treeUri) {
        audioFiles.clear();
        audioFolderStatus.setText("Scanning selected folder…");
        new Thread(() -> {
            try {
                String rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
                scanDocumentChildren(treeUri, rootDocumentId, 0);
                Collections.sort(audioFiles, Comparator.comparing(file -> naturalSortKey(file.name)));
                runOnUiThread(() -> {
                    mapAudioFilesToChapters();
                    rebuildChapterControls();
                    audioFolderStatus.setText("Audio folder: " + folderDisplayName(treeUri) + " — " + audioFiles.size() + " files found");
                    updateLibraryStatus();
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    audioFolderStatus.setText("Could not scan folder: " + exception.getMessage());
                    updateLibraryStatus();
                });
            }
        }).start();
    }

    private void scanDocumentChildren(Uri treeUri, String parentDocumentId, int depth) {
        if (depth > 3) return;
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                String documentId = cursor.getString(0);
                String displayName = cursor.getString(1);
                String mimeType = cursor.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    scanDocumentChildren(treeUri, documentId, depth + 1);
                } else if (isAudioFile(displayName, mimeType)) {
                    Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                    synchronized (audioFiles) {
                        audioFiles.add(new AudioFile(displayName, documentUri));
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private boolean isAudioFile(String name, String mimeType) {
        String lower = name == null ? "" : name.toLowerCase(Locale.UK);
        return (mimeType != null && mimeType.startsWith("audio/")) ||
                lower.endsWith(".m4a") || lower.endsWith(".aac") ||
                lower.endsWith(".mp3") || lower.endsWith(".wav") ||
                lower.endsWith(".ogg");
    }

    private String folderDisplayName(Uri treeUri) {
        try {
            String documentId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
            try (Cursor cursor = getContentResolver().query(documentUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
            }
        } catch (Exception ignored) {}
        return "Selected folder";
    }

    private void mapAudioFilesToChapters() {
        for (Chapter chapter : chapters) {
            chapter.audioUri = null;
            chapter.audioName = "";
        }
        if (chapters.isEmpty() || audioFiles.isEmpty()) return;

        Set<Uri> used = new HashSet<>();
        for (Chapter chapter : chapters) {
            AudioFile best = null;
            int bestScore = 0;
            for (AudioFile audio : audioFiles) {
                if (used.contains(audio.uri)) continue;
                int score = matchScore(chapter, audio);
                if (score > bestScore) {
                    bestScore = score;
                    best = audio;
                }
            }
            if (best != null && bestScore >= 60) {
                chapter.audioUri = best.uri;
                chapter.audioName = best.name;
                used.add(best.uri);
            }
        }
    }

    private int matchScore(Chapter chapter, AudioFile audio) {
        String fileName = stripExtension(audio.name).replaceAll("\\s*\\(\\d+\\)\\s*$", "");
        String fileWithoutNumber = fileName.replaceFirst("^\\s*\\d{1,2}\\s*[-–—:]\\s*", "");
        String fileKey = normalise(fileWithoutNumber);
        String titleWithoutChapter = chapter.title.replaceFirst("(?i)^Chapter\\s+\\d+\\s*[:\\-–—]?\\s*", "");
        String titleKey = normalise(titleWithoutChapter);

        if (chapter.id.equals("front_matter") && fileKey.contains("front matter")) return 120;
        if (fileKey.equals(titleKey)) return 115;
        if (fileKey.contains(titleKey) || titleKey.contains(fileKey)) {
            if (Math.min(fileKey.length(), titleKey.length()) > 8) return 95;
        }

        int chapterNumber = chapterNumber(chapter.title);
        int fileNumber = leadingNumber(fileName);
        if (chapterNumber > 0 && fileNumber == chapterNumber) return 110;

        String idKey = normalise(chapter.id.replace('_', ' '));
        if (fileKey.equals(idKey) || fileKey.contains(idKey)) return 90;

        int overlap = wordOverlap(fileKey, titleKey);
        return overlap >= 4 ? 70 + overlap : overlap * 10;
    }

    private int chapterNumber(String title) {
        Matcher matcher = Pattern.compile("(?i)^Chapter\\s+(\\d+)").matcher(title);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private int leadingNumber(String fileName) {
        Matcher matcher = Pattern.compile("^\\s*(\\d{1,2})\\s*[-–—:]").matcher(fileName);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private int wordOverlap(String first, String second) {
        Set<String> words = new HashSet<>();
        Collections.addAll(words, first.split("\\s+"));
        int count = 0;
        for (String word : second.split("\\s+")) {
            if (word.length() > 2 && words.contains(word)) count++;
        }
        return count;
    }

    private String stripExtension(String value) {
        return value == null ? "" : value.replaceFirst("(?i)\\.(m4a|aac|mp3|wav|ogg)$", "");
    }

    private String normalise(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.UK)
                .replace('&', ' ')
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String naturalSortKey(String name) {
        Matcher matcher = Pattern.compile("^\\s*(\\d{1,2})").matcher(name == null ? "" : name);
        int number = matcher.find() ? Integer.parseInt(matcher.group(1)) : 999;
        return String.format(Locale.UK, "%03d_%s", number, normalise(name));
    }

    private void rebuildChapterControls() {
        if (libraryAdapter == null) {
            libraryAdapter = new ChapterAdapter();
            libraryList.setAdapter(libraryAdapter);
            libraryList.setOnItemClickListener((parent, view, position, id) -> {
                selectChapter(position, false);
                tabHost.setCurrentTabByTag("read");
            });
        } else {
            libraryAdapter.notifyDataSetChanged();
        }

        List<String> titles = new ArrayList<>();
        for (Chapter chapter : chapters) titles.add(chapter.title);
        chapterSpinnerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                titles);
        suppressChapterSpinner = true;
        listenChapterSpinner.setAdapter(chapterSpinnerAdapter);
        if (!chapters.isEmpty()) listenChapterSpinner.setSelection(clampChapter(currentChapterIndex));
        suppressChapterSpinner = false;
        listenChapterSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!suppressChapterSpinner && position != currentAudioIndex) loadAudioForChapter(position, false);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        boolean hasBook = !chapters.isEmpty();
        continueButton.setEnabled(hasBook);
        readPreviousButton.setEnabled(hasBook && currentChapterIndex > 0);
        readNextButton.setEnabled(hasBook && currentChapterIndex < chapters.size() - 1);
        listenThisButton.setEnabled(hasBook && chapters.get(clampChapter(currentChapterIndex)).audioUri != null);
    }

    private void updateLibraryStatus() {
        if (chapters.isEmpty()) {
            libraryStatus.setText("Choose Narration_Master.md to load the book.");
            return;
        }
        int matched = 0;
        for (Chapter chapter : chapters) if (chapter.audioUri != null) matched++;
        libraryStatus.setText(chapters.size() + " sections loaded — " + matched + " audio files matched.");
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
        listenThisButton.setEnabled(chapter.audioUri != null);
        if (libraryAdapter != null) libraryAdapter.notifyDataSetChanged();

        int savedScroll = preferences.getInt(PREF_READ_POSITION_PREFIX + chapter.id, 0);
        readScroll.post(() -> readScroll.scrollTo(0, Math.max(0, savedScroll)));
        if (keepReadTab) tabHost.setCurrentTabByTag("read");
    }

    private void loadAudioForChapter(int requestedIndex, boolean autoPlay) {
        if (chapters.isEmpty()) return;
        int index = clampChapter(requestedIndex);
        Chapter chapter = chapters.get(index);
        if (chapter.audioUri == null) {
            Toast.makeText(this, "No audio file was matched to this section.", Toast.LENGTH_LONG).show();
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
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            mediaPlayer.setDataSource(this, chapter.audioUri);
            mediaPlayer.setOnPreparedListener(player -> {
                playerPrepared = true;
                int duration = player.getDuration();
                playerSeek.setMax(Math.max(duration, 1));
                int savedPosition = preferences.getInt(PREF_AUDIO_POSITION_PREFIX + chapter.id, 0);
                if (savedPosition > 0 && savedPosition < duration - 3000) player.seekTo(savedPosition);
                applyPlaybackSpeed();
                nowPlaying.setText(chapter.title + "\n" + chapter.audioName);
                playerTime.setText(formatTime(player.getCurrentPosition()) + " / " + formatTime(duration));
                setPlayerControlsEnabled(true);
                playPauseButton.setText("Play");
                if (autoPlay) {
                    player.start();
                    playPauseButton.setText("Pause");
                }
            });
            mediaPlayer.setOnCompletionListener(player -> {
                preferences.edit().putInt(PREF_AUDIO_POSITION_PREFIX + chapter.id, 0).apply();
                int next = findNextAudioIndex(index);
                if (next >= 0) loadAudioForChapter(next, true);
                else {
                    playPauseButton.setText("Play");
                    playerSeek.setProgress(0);
                    playerTime.setText("0:00 / " + formatTime(player.getDuration()));
                }
            });
            mediaPlayer.setOnErrorListener((player, what, extra) -> {
                nowPlaying.setText("This audio file could not be played. Choose the folder again or check the file.");
                setPlayerControlsEnabled(false);
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception exception) {
            releasePlayer();
            nowPlaying.setText("Could not open audio: " + exception.getMessage());
        }
    }

    private void togglePlayback() {
        if (mediaPlayer == null || !playerPrepared) {
            if (currentChapterIndex >= 0 && currentChapterIndex < chapters.size()) loadAudioForChapter(currentChapterIndex, true);
            return;
        }
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

    private void stopPlayback() {
        if (mediaPlayer == null || !playerPrepared) return;
        try {
            if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            mediaPlayer.seekTo(0);
            playPauseButton.setText("Play");
            playerSeek.setProgress(0);
            playerTime.setText("0:00 / " + formatTime(mediaPlayer.getDuration()));
            if (currentAudioIndex >= 0) {
                preferences.edit().putInt(PREF_AUDIO_POSITION_PREFIX + chapters.get(currentAudioIndex).id, 0).apply();
            }
        } catch (IllegalStateException ignored) {}
    }

    private void playPreviousAudio() {
        int previous = findPreviousAudioIndex(currentAudioIndex < 0 ? currentChapterIndex : currentAudioIndex);
        if (previous >= 0) loadAudioForChapter(previous, true);
    }

    private void playNextAudio() {
        int next = findNextAudioIndex(currentAudioIndex < 0 ? currentChapterIndex - 1 : currentAudioIndex);
        if (next >= 0) loadAudioForChapter(next, true);
    }

    private int findPreviousAudioIndex(int from) {
        for (int index = Math.min(from - 1, chapters.size() - 1); index >= 0; index--) {
            if (chapters.get(index).audioUri != null) return index;
        }
        return -1;
    }

    private int findNextAudioIndex(int from) {
        for (int index = Math.max(0, from + 1); index < chapters.size(); index++) {
            if (chapters.get(index).audioUri != null) return index;
        }
        return -1;
    }

    private void saveCurrentAudioPosition() {
        if (mediaPlayer == null || !playerPrepared || currentAudioIndex < 0 || currentAudioIndex >= chapters.size()) return;
        try { saveAudioPosition(mediaPlayer.getCurrentPosition(), mediaPlayer.getDuration()); }
        catch (IllegalStateException ignored) {}
    }

    private void saveAudioPosition(int position, int duration) {
        if (currentAudioIndex < 0 || currentAudioIndex >= chapters.size()) return;
        if (position >= duration - 3000) position = 0;
        preferences.edit().putInt(PREF_AUDIO_POSITION_PREFIX + chapters.get(currentAudioIndex).id, position).apply();
    }

    private void applyPlaybackSpeed() {
        if (mediaPlayer == null || !playerPrepared) return;
        int position = speedSpinner.getSelectedItemPosition();
        if (position < 0 || position >= SPEED_VALUES.length) position = 1;
        try {
            PlaybackParams params = mediaPlayer.getPlaybackParams();
            params.setSpeed(SPEED_VALUES[position]);
            mediaPlayer.setPlaybackParams(params);
        } catch (Exception ignored) {}
    }

    private void setSleepTimer(int position) {
        handler.removeCallbacks(sleepRunnable);
        sleepDeadline = 0L;
        if (position <= 0 || position >= SLEEP_MINUTES.length) {
            sleepStatus.setText("Sleep timer off");
            return;
        }
        long delay = SLEEP_MINUTES[position] * 60L * 1000L;
        sleepDeadline = System.currentTimeMillis() + delay;
        handler.postDelayed(sleepRunnable, delay);
        sleepStatus.setText("Sleep timer: " + SLEEP_MINUTES[position] + " minutes");
    }

    private void changeTextSize(float amount) {
        readerTextSize = Math.max(14f, Math.min(30f, readerTextSize + amount));
        readBody.setTextSize(readerTextSize);
        preferences.edit().putFloat(PREF_TEXT_SIZE, readerTextSize).apply();
    }

    private void applyTheme() {
        int background = darkMode ? Color.rgb(24, 24, 24) : Color.rgb(247, 244, 236);
        int panel = darkMode ? Color.rgb(34, 34, 34) : Color.WHITE;
        int text = darkMode ? Color.rgb(235, 235, 235) : Color.rgb(32, 32, 32);
        root.setBackgroundColor(background);
        headerPanel.setBackgroundColor(background);
        readScroll.setBackgroundColor(panel);
        readBody.setTextColor(text);
        readTitle.setTextColor(darkMode ? Color.WHITE : Color.rgb(7, 31, 61));
        nowPlaying.setTextColor(darkMode ? Color.WHITE : Color.rgb(7, 31, 61));
        darkModeButton.setText(darkMode ? "Light mode" : "Dark mode");
        if (libraryAdapter != null) libraryAdapter.notifyDataSetChanged();
    }

    private void setPlayerControlsEnabled(boolean enabled) {
        playPauseButton.setEnabled(enabled);
        playerStopButton.setEnabled(enabled);
        playerPreviousButton.setEnabled(enabled && findPreviousAudioIndex(currentAudioIndex) >= 0);
        playerNextButton.setEnabled(enabled && findNextAudioIndex(currentAudioIndex) >= 0);
        playerSeek.setEnabled(enabled);
    }

    private int clampChapter(int requested) {
        if (chapters.isEmpty()) return 0;
        return Math.max(0, Math.min(chapters.size() - 1, requested));
    }

    private String formatTime(int milliseconds) {
        int totalSeconds = Math.max(0, milliseconds) / 1000;
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) return String.format(Locale.UK, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.UK, "%d:%02d", minutes, seconds);
    }

    private String formatTimeLong(long milliseconds) {
        long totalSeconds = Math.max(0L, milliseconds) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.UK, "%d:%02d", minutes, seconds);
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

    @Override protected void onPause() {
        saveCurrentAudioPosition();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(playerProgressUpdater);
        handler.removeCallbacks(sleepRunnable);
        saveCurrentAudioPosition();
        releasePlayer();
        super.onDestroy();
    }

    private class ChapterAdapter extends BaseAdapter {
        @Override public int getCount() { return chapters.size(); }
        @Override public Chapter getItem(int position) { return chapters.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView title;
            TextView detail;
            if (convertView == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(18, 16, 18, 16);
                title = new TextView(MainActivity.this);
                title.setTextSize(16f);
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                detail = new TextView(MainActivity.this);
                detail.setTextSize(12f);
                row.addView(title);
                row.addView(detail);
                row.setTag(new TextView[]{title, detail});
            } else {
                row = (LinearLayout) convertView;
                TextView[] views = (TextView[]) row.getTag();
                title = views[0];
                detail = views[1];
            }

            Chapter chapter = getItem(position);
            title.setText(chapter.title);
            String progress = preferences.getInt(PREF_READ_POSITION_PREFIX + chapter.id, 0) > 0 ? "Reading started" : "Not started";
            String audio = chapter.audioUri == null ? "No audio matched" : "Audio ready: " + chapter.audioName;
            detail.setText(progress + "  •  " + audio);
            int rowBackground = position == currentChapterIndex
                    ? (darkMode ? Color.rgb(55, 50, 35) : Color.rgb(250, 242, 210))
                    : (darkMode ? Color.rgb(38, 38, 38) : Color.WHITE);
            row.setBackgroundColor(rowBackground);
            title.setTextColor(darkMode ? Color.WHITE : Color.rgb(7, 31, 61));
            detail.setTextColor(darkMode ? Color.LTGRAY : Color.DKGRAY);
            return row;
        }
    }

    private static class Chapter {
        final String id;
        final String title;
        final String html;
        Uri audioUri;
        String audioName = "";

        Chapter(String id, String title, String html) {
            this.id = id;
            this.title = title;
            this.html = html;
        }
    }

    private static class AudioFile {
        final String name;
        final Uri uri;

        AudioFile(String name, Uri uri) {
            this.name = name;
            this.uri = uri;
        }
    }

    private static class Heading {
        final int start;
        final int end;
        final String title;

        Heading(int start, int end, String title) {
            this.start = start;
            this.end = end;
            this.title = title;
        }
    }
}
