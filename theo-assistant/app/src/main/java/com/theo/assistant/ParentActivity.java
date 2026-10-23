package com.theo.assistant;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * Grown-ups screen: shows how many recordings each answer has, and lets you
 * record a new one with the microphone (tap a row to start, tap again to stop)
 * or hear the newest one (press and hold a row).
 */
public class ParentActivity extends Activity {

    private Brain brain;
    private LinearLayout list;
    private MediaRecorder recorder;
    private MediaPlayer player;
    private String recordingId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        brain = new Brain(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list);
        setContentView(scroll);
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRecording();
        stopPlaying();
    }

    private void refresh() {
        list.removeAllViews();
        TextView info = row("Recordings folder:\n" + Brain.clipRoot(this).getAbsolutePath()
                + "\n\nTap an answer to record it from the microphone, tap again to stop. "
                + "Press and hold to hear the newest recording.", 14, 0xFF5F6368);
        list.addView(info);
        addRow("unknown");
        for (Brain.Intent intent : brain.intents()) {
            if (!intent.builtin) addRow(intent.id);
        }
    }

    private void addRow(String id) {
        int count = brain.clips(id).size();
        String label = (id.equals(recordingId) ? "● Recording…  " : "") + id + "   (" + count + ")";
        TextView r = row(label, 20, count > 0 ? 0xFF188038 : 0xFF202124);
        r.setOnClickListener(v -> toggleRecording(id));
        r.setOnLongClickListener(v -> {
            playNewest(id);
            return true;
        });
        list.addView(r);
    }

    private void toggleRecording(String id) {
        if (recorder != null) {
            boolean same = id.equals(recordingId);
            stopRecording();
            refresh();
            if (same) return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != 0) {
            Toast.makeText(this, "Microphone permission is needed", Toast.LENGTH_SHORT).show();
            return;
        }
        File dir = brain.clipDir(id);
        dir.mkdirs();
        File out = new File(dir, "rec_" + System.currentTimeMillis() + ".m4a");
        try {
            recorder = new MediaRecorder(this);
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setOutputFile(out.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recordingId = id;
        } catch (Exception e) {
            recorder = null;
            Toast.makeText(this, "Couldn't start recording", Toast.LENGTH_SHORT).show();
        }
        refresh();
    }

    private void stopRecording() {
        if (recorder == null) return;
        try {
            recorder.stop();
        } catch (RuntimeException ignored) {
            // Stopped too quickly to have any audio.
        }
        recorder.release();
        recorder = null;
        recordingId = null;
    }

    private void playNewest(String id) {
        stopPlaying();
        List<File> clips = brain.clips(id);
        if (clips.isEmpty()) return;
        File newest = clips.get(0);
        for (File f : clips) if (f.lastModified() > newest.lastModified()) newest = f;
        try {
            player = new MediaPlayer();
            player.setDataSource(newest.getAbsolutePath());
            player.prepare();
            player.start();
        } catch (Exception e) {
            stopPlaying();
        }
    }

    private void stopPlaying() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private TextView row(String s, int sp, int colour) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        int pad = dp(10);
        t.setPadding(0, pad, 0, pad);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
