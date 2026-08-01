package com.lodgemarketingmachine.narrator;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.WindowManager;
import android.widget.*;

import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int PICK_FILE = 41;
    private static final int PICK_AUDIO = 42;
    private static final String API_URL = "https://api.openai.com/v1/audio/speech";
    private static final String INSTRUCTIONS = "Narrate in natural British English. Use a warm, calm, authoritative and conversational delivery. Sound experienced and thoughtful, not theatrical, commercial or overly solemn. Use measured pacing, clear diction and gentle emphasis. Pause briefly after chapter titles, section headings, quotations and reflective questions. Pronounce Masonic terminology carefully. Read the supplied text faithfully without adding commentary, introductions or omitted wording.";

    private EditText apiKey;
    private Spinner voiceSpinner;
    private ProgressBar progress;
    private TextView status, fileStatus;
    private Button selectButton, sampleButton, chapterButton, fullButton, stopButton;

    private Button selectAudioButton, playPauseButton, playerStopButton;
    private TextView nowPlaying, playerTime;
    private SeekBar playerSeek;
    private MediaPlayer mediaPlayer;
    private final Handler playerHandler = new Handler(Looper.getMainLooper());
    private boolean playerPrepared = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean stopRequested = false;
    private String narrationText = "";

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

        apiKey=findViewById(R.id.apiKey); voiceSpinner=findViewById(R.id.voiceSpinner);
        progress=findViewById(R.id.progress); status=findViewById(R.id.status); fileStatus=findViewById(R.id.fileStatus);
        selectButton=findViewById(R.id.selectButton); sampleButton=findViewById(R.id.sampleButton);
        chapterButton=findViewById(R.id.chapterButton); fullButton=findViewById(R.id.fullButton); stopButton=findViewById(R.id.stopButton);

        selectAudioButton=findViewById(R.id.selectAudioButton); playPauseButton=findViewById(R.id.playPauseButton);
        playerStopButton=findViewById(R.id.playerStopButton); nowPlaying=findViewById(R.id.nowPlaying);
        playerTime=findViewById(R.id.playerTime); playerSeek=findViewById(R.id.playerSeek);

        voiceSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"cedar","marin","onyx"}));
        stopButton.setEnabled(false);
        playPauseButton.setEnabled(false);
        playerStopButton.setEnabled(false);

        selectButton.setOnClickListener(v -> chooseFile());
        sampleButton.setOnClickListener(v -> generateSample());
        chapterButton.setOnClickListener(v -> generateChapterOne());
        fullButton.setOnClickListener(v -> generateFullBook());
        stopButton.setOnClickListener(v -> { stopRequested=true; status.setText("Stopping after the current part…"); });

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
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        startActivityForResult(intent,PICK_FILE);
    }

    private void chooseAudio() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        startActivityForResult(intent,PICK_AUDIO);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK || data==null || data.getData()==null) return;
        Uri uri=data.getData();

        if(requestCode==PICK_FILE){
            try{
                try { getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) {}
                narrationText=readUri(uri);
                fileStatus.setText("Narration file loaded: "+displayName(uri));
                status.setText("Ready");
            }catch(Exception e){ status.setText("Could not open file: "+e.getMessage()); }
        } else if(requestCode==PICK_AUDIO) {
            try {
                try { getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) {}
                loadAudio(uri, displayName(uri));
            } catch (Exception e) {
                nowPlaying.setText("Could not open audio: " + e.getMessage());
            }
        }
    }

    private String readUri(Uri uri) throws Exception {
        try(InputStream in=getContentResolver().openInputStream(uri); ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null) throw new Exception("File could not be read.");
            byte[] b=new byte[8192]; int n; while((n=in.read(b))>0) out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String displayName(Uri uri){
        try(Cursor c=getContentResolver().query(uri,null,null,null,null)){
            if(c!=null && c.moveToFirst()){ int i=c.getColumnIndex("_display_name"); if(i>=0)return c.getString(i); }
        }catch(Exception ignored){}
        return "Selected file";
    }

    private void loadAudio(Uri uri, String name) {
        releasePlayer();
        playerPrepared=false;
        playPauseButton.setEnabled(false);
        playerStopButton.setEnabled(false);
        playerSeek.setProgress(0);
        playerTime.setText("0:00 / 0:00");
        nowPlaying.setText("Loading: " + name);

        mediaPlayer=new MediaPlayer();
        try {
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.setOnPreparedListener(mp -> {
                playerPrepared=true;
                playerSeek.setMax(Math.max(mp.getDuration(),1));
                playerTime.setText("0:00 / " + formatTime(mp.getDuration()));
                nowPlaying.setText("Now playing: " + name);
                playPauseButton.setText("Play");
                playPauseButton.setEnabled(true);
                playerStopButton.setEnabled(true);
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                playPauseButton.setText("Play");
                playerSeek.setProgress(0);
                playerTime.setText("0:00 / " + formatTime(mp.getDuration()));
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
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
        if(mediaPlayer==null || !playerPrepared) return;
        try {
            if(mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                playPauseButton.setText("Play");
            } else {
                mediaPlayer.start();
                playPauseButton.setText("Pause");
            }
        } catch (IllegalStateException e) {
            nowPlaying.setText("Playback error. Select the audio file again.");
        }
    }

    private void stopPlayback() {
        if(mediaPlayer==null || !playerPrepared) return;
        try {
            if(mediaPlayer.isPlaying()) mediaPlayer.pause();
            mediaPlayer.seekTo(0);
            playPauseButton.setText("Play");
            playerSeek.setProgress(0);
            playerTime.setText("0:00 / " + formatTime(mediaPlayer.getDuration()));
        } catch (IllegalStateException ignored) {}
    }

    private String formatTime(int milliseconds) {
        int totalSeconds=Math.max(milliseconds,0)/1000;
        int hours=totalSeconds/3600;
        int minutes=(totalSeconds%3600)/60;
        int seconds=totalSeconds%60;
        if(hours>0) return String.format(Locale.UK,"%d:%02d:%02d",hours,minutes,seconds);
        return String.format(Locale.UK,"%d:%02d",minutes,seconds);
    }

    private void releasePlayer() {
        if(mediaPlayer!=null) {
            try { mediaPlayer.reset(); } catch (Exception ignored) {}
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer=null;
        }
        playerPrepared=false;
    }

    private boolean validKey(){
        String k=apiKey.getText().toString().trim();
        if(!k.startsWith("sk-") || k.length()<20){ Toast.makeText(this,"Enter a valid OpenAI API key.",Toast.LENGTH_LONG).show(); return false; }
        return true;
    }

    private void generateSample(){
        if(!validKey())return;
        List<Section> list=new ArrayList<>();
        list.add(new Section("00_voice_sample","Voice Sample","Every generation inherits a lodge. Every generation has a choice. Every generation leaves a legacy. This is not a book about recruiting men at any cost. It is a book about becoming a lodge worth joining, communicating honestly, selecting carefully and caring properly for those who enter."));
        runGeneration("Voice sample",list);
    }

    private void generateChapterOne(){
        if(!validKey() || !hasText())return;
        List<Section> chapters=parseChapters(narrationText);
        for(Section s:chapters){ if(s.id.startsWith("chapter_01")){ runGeneration("Chapter 1",Collections.singletonList(s)); return; } }
        status.setText("Chapter 1 could not be found in the selected file.");
    }

    private void generateFullBook(){
        if(!validKey() || !hasText())return;
        runGeneration("Full book",parseChapters(narrationText));
    }

    private boolean hasText(){
        if(narrationText.trim().isEmpty()){ Toast.makeText(this,"Select Narration_Master.md first.",Toast.LENGTH_LONG).show(); return false; }
        return true;
    }

    private List<Section> parseChapters(String text){
        String clean=text.replace("\r\n","\n");
        Pattern p=Pattern.compile("(?m)^#\\s+(Chapter\\s+([0-9]+)[^\\n]*|Before We Begin|How to Use This Book|What You Will Build|The Complete Machine|Acknowledgements|Ideas and Influences|About the Author)\\s*$");
        Matcher m=p.matcher(clean); List<Integer> starts=new ArrayList<>(); List<String> titles=new ArrayList<>();
        while(m.find()){ starts.add(m.start()); titles.add(m.group(1).trim()); }
        List<Section> out=new ArrayList<>();
        if(starts.isEmpty()){ out.add(new Section("full_book","Full Book",clean)); return out; }
        if(starts.get(0)>0){ String pre=clean.substring(0,starts.get(0)).trim(); if(!pre.isEmpty())out.add(new Section("front_matter","Front Matter",pre)); }
        for(int i=0;i<starts.size();i++){
            int end=i+1<starts.size()?starts.get(i+1):clean.length();
            String title=titles.get(i); String id;
            Matcher n=Pattern.compile("Chapter\\s+([0-9]+)",Pattern.CASE_INSENSITIVE).matcher(title);
            if(n.find()) id=String.format(Locale.UK,"chapter_%02d",Integer.parseInt(n.group(1))); else id=title.toLowerCase(Locale.UK).replaceAll("[^a-z0-9]+","_").replaceAll("^_|_$","");
            out.add(new Section(id,title,clean.substring(starts.get(i),end).trim()));
        }
        return out;
    }

    private List<String> chunks(String text){
        List<String> out=new ArrayList<>(); StringBuilder cur=new StringBuilder();
        for(String para:text.split("\\n\\s*\\n")){
            String x=para.trim(); if(x.isEmpty())continue;
            if(cur.length()+x.length()+2>3700){ if(cur.length()>0){out.add(cur.toString());cur.setLength(0);} }
            if(x.length()>3700){ for(int i=0;i<x.length();i+=3600) out.add(x.substring(i,Math.min(x.length(),i+3600))); }
            else { if(cur.length()>0)cur.append("\n\n"); cur.append(x); }
        }
        if(cur.length()>0)out.add(cur.toString()); return out;
    }

    private void runGeneration(String label,List<Section> sections){
        stopRequested=false; setBusy(true); progress.setProgress(0);
        final String key=apiKey.getText().toString().trim(); final String voice=voiceSpinner.getSelectedItem().toString();
        executor.execute(() -> {
            try{
                List<Job> jobs=new ArrayList<>(); for(Section s:sections)for(String c:chunks(s.text))jobs.add(new Job(s,c));
                int done=0; String current=""; File temp=null; OutputStream joined=null; Uri lastSavedUri=null; String lastSavedName=null;
                for(Job job:jobs){
                    if(stopRequested)break;
                    if(!job.section.id.equals(current)){
                        if(joined!=null){
                            joined.close();
                            lastSavedName=current+".aac";
                            lastSavedUri=saveFile(temp,lastSavedName,current);
                        }
                        current=job.section.id; temp=new File(getCacheDir(),current+".aac"); joined=new BufferedOutputStream(new FileOutputStream(temp,false));
                    }
                    updateStatus(label+": part "+(done+1)+" of "+jobs.size()+"\n"+job.section.title);
                    joined.write(requestSpeech(key,voice,job.text)); joined.flush(); done++; updateProgress((int)Math.round(done*100.0/jobs.size()));
                }
                if(joined!=null){
                    joined.close();
                    lastSavedName=current+".aac";
                    lastSavedUri=saveFile(temp,lastSavedName,current);
                }
                boolean stopped=stopRequested;
                final Uri playableUri=lastSavedUri;
                final String playableName=lastSavedName;
                runOnUiThread(() -> {
                    setBusy(false);
                    status.setText(stopped?"Stopped safely. Completed files are in Downloads/LodgeMarketingMachineAudio.":"Complete. Files are in Downloads/LodgeMarketingMachineAudio.");
                    if(playableUri!=null) loadAudio(playableUri,playableName);
                });
            }catch(Exception e){runOnUiThread(() -> {setBusy(false);status.setText("Error: "+e.getMessage());});}
        });
    }

    private byte[] requestSpeech(String key,String voice,String text)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(API_URL).openConnection(); c.setRequestMethod("POST"); c.setConnectTimeout(30000); c.setReadTimeout(180000); c.setDoOutput(true);
        c.setRequestProperty("Authorization","Bearer "+key); c.setRequestProperty("Content-Type","application/json");
        JSONObject b=new JSONObject(); b.put("model","gpt-4o-mini-tts"); b.put("voice",voice); b.put("input",text); b.put("instructions",INSTRUCTIONS); b.put("response_format","aac"); b.put("speed",0.96);
        try(OutputStream o=c.getOutputStream()){o.write(b.toString().getBytes(StandardCharsets.UTF_8));}
        int code=c.getResponseCode(); InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream(); ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n; while((n=in.read(buf))>0)out.write(buf,0,n); in.close(); c.disconnect();
        byte[] data=out.toByteArray(); if(code<200||code>=300)throw new Exception("OpenAI returned "+code+": "+new String(data,StandardCharsets.UTF_8)); return data;
    }

    private Uri saveFile(File source,String name,String title)throws Exception{
        ContentValues v=new ContentValues(); v.put(MediaStore.MediaColumns.DISPLAY_NAME,name); v.put(MediaStore.MediaColumns.MIME_TYPE,"audio/aac"); v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/LodgeMarketingMachineAudio"); v.put(MediaStore.Audio.Media.TITLE,title);
        Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v); if(uri==null)throw new Exception("Could not create output file.");
        try(InputStream in=new FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(uri)){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}
        return uri;
    }

    private void setBusy(boolean busy){runOnUiThread(() -> {selectButton.setEnabled(!busy);sampleButton.setEnabled(!busy);chapterButton.setEnabled(!busy);fullButton.setEnabled(!busy);stopButton.setEnabled(busy);});}
    private void updateStatus(String s){runOnUiThread(() -> status.setText(s));}
    private void updateProgress(int p){runOnUiThread(() -> progress.setProgress(p));}

    @Override protected void onDestroy() {
        playerHandler.removeCallbacks(playerProgressUpdater);
        releasePlayer();
        executor.shutdownNow();
        super.onDestroy();
    }

    private static class Section{final String id,title,text;Section(String i,String t,String x){id=i;title=t;text=x;}}
    private static class Job{final Section section;final String text;Job(Section s,String t){section=s;text=t;}}
}
