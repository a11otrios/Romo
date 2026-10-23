package com.theo.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements RecognitionListener {

    private static final int REQ_MIC = 1;
    private static final Locale VOICE_LOCALE = Locale.UK;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Brain brain;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady;
    private MediaPlayer player;
    private ToneGenerator beeper;
    private boolean busy;

    private DotsView dots;
    private TextView heardText;
    private TextView replyText;
    private TextView hint;
    private VideoView video;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        brain = new Brain(this);
        beeper = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
        buildUi();

        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            tts.setLanguage(VOICE_LOCALE);
            pickFriendlyVoice();
            tts.setPitch(1.1f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { main.post(MainActivity.this::finishedTalking); }
                @Override public void onError(String id) { main.post(MainActivity.this::finishedTalking); }
            });
            ttsReady = true;
        });
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text("Hi Theo!", 30, 0xFF202124, true);
        // Grown-ups: press and hold the title to open the clips screen.
        title.setOnLongClickListener(v -> {
            stopEverything();
            startActivity(new Intent(this, ParentActivity.class));
            return true;
        });
        root.addView(title, new LinearLayout.LayoutParams(-2, -2));

        heardText = text("", 24, 0xFF5F6368, false);
        LinearLayout.LayoutParams heardLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        heardLp.topMargin = dp(24);
        heardText.setGravity(Gravity.CENTER);
        root.addView(heardText, heardLp);

        dots = new DotsView(this);
        root.addView(dots, new LinearLayout.LayoutParams(-1, dp(140)));

        replyText = text("", 26, 0xFF1A73E8, true);
        replyText.setGravity(Gravity.CENTER);
        root.addView(replyText, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView mic = text("🎤", 54, Color.WHITE, false);
        mic.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFF4285F4);
        mic.setBackground(circle);
        mic.setOnClickListener(v -> tapped());
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(dp(150), dp(150));
        root.addView(mic, micLp);

        hint = text("Tap and talk to me", 18, 0xFF5F6368, false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-2, -2);
        hintLp.topMargin = dp(12);
        root.addView(hint, hintLp);

        root.setOnClickListener(v -> tapped());

        // Recorded answers with the Assistant's screen play full screen on top.
        video = new VideoView(this);
        video.setVisibility(View.GONE);
        video.setOnCompletionListener(mp -> finishedTalking());
        video.setOnErrorListener((mp, what, extra) -> {
            finishedTalking();
            return true;
        });
        FrameLayout videoBox = new FrameLayout(this);
        videoBox.setBackgroundColor(Color.BLACK);
        videoBox.setVisibility(View.GONE);
        videoBox.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        videoBox.setOnClickListener(v -> tapped());

        FrameLayout screen = new FrameLayout(this);
        screen.addView(root, new FrameLayout.LayoutParams(-1, -1));
        screen.addView(videoBox, new FrameLayout.LayoutParams(-1, -1));
        setContentView(screen);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        // Like the old Assistant, start listening as soon as it opens.
        main.postDelayed(this::listen, 600);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacksAndMessages(null);
        stopEverything();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        beeper.release();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_MIC && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            listen();
        } else {
            hint.setText("I need the microphone to hear you");
        }
    }

    private void tapped() {
        if (busy) {
            // Tapping while she talks stops her and listens again, like the real one.
            stopEverything();
        }
        listen();
    }

    private void listen() {
        if (busy) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            replyText.setText("Speech recognition isn't available on this phone.");
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
        }
        busy = true;
        heardText.setText("");
        replyText.setText("");
        hint.setText("Listening…");
        dots.setMode(DotsView.Mode.LISTENING);
        beeper.startTone(ToneGenerator.TONE_PROP_BEEP, 120);

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, VOICE_LOCALE.toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        main.postDelayed(() -> recognizer.startListening(intent), 150);
    }

    private void hideVideo() {
        if (video == null) return;
        video.stopPlayback();
        video.setVisibility(View.GONE);
        ((View) video.getParent()).setVisibility(View.GONE);
    }

    private void stopEverything() {
        hideVideo();
        if (recognizer != null) recognizer.cancel();
        if (tts != null) tts.stop();
        if (player != null) {
            player.release();
            player = null;
        }
        busy = false;
        dots.setMode(DotsView.Mode.IDLE);
        hint.setText("Tap and talk to me");
    }

    // RecognitionListener

    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEvent(int eventType, Bundle params) { }

    @Override
    public void onRmsChanged(float rmsdB) {
        dots.setLevel((rmsdB + 2f) / 12f);
    }

    @Override
    public void onEndOfSpeech() {
        dots.setMode(DotsView.Mode.THINKING);
    }

    @Override
    public void onPartialResults(Bundle partial) {
        ArrayList<String> words = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (words != null && !words.isEmpty()) heardText.setText(words.get(0));
    }

    @Override
    public void onError(int error) {
        busy = false;
        dots.setMode(DotsView.Mode.IDLE);
        boolean heardNothing = error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
        hint.setText(heardNothing ? "I didn't hear anything. Tap and try again!" : "Tap and talk to me");
    }

    @Override
    public void onResults(Bundle results) {
        List<String> heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (heard == null || heard.isEmpty()) {
            onError(SpeechRecognizer.ERROR_NO_MATCH);
            return;
        }
        heardText.setText(heard.get(0));
        respond(brain.answer(heard));
    }

    private void respond(Brain.Answer answer) {
        dots.setMode(DotsView.Mode.TALKING);
        hint.setText("Tap to stop");
        if (answer.clip != null && Brain.isVideo(answer.clip)) {
            replyText.setText("");
            ((View) video.getParent()).setVisibility(View.VISIBLE);
            video.setVisibility(View.VISIBLE);
            video.setVideoPath(answer.clip.getAbsolutePath());
            video.start();
            return;
        }
        if (answer.clip != null) {
            replyText.setText("");
            try {
                player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                player.setDataSource(answer.clip.getAbsolutePath());
                player.setOnCompletionListener(mp -> finishedTalking());
                player.setOnErrorListener((mp, what, extra) -> {
                    finishedTalking();
                    return true;
                });
                player.prepare();
                player.start();
                return;
            } catch (Exception e) {
                if (player != null) player.release();
                player = null;
            }
        }
        replyText.setText(answer.speech);
        if (ttsReady) {
            tts.speak(answer.speech, TextToSpeech.QUEUE_FLUSH, null, answer.intentId);
        } else {
            main.postDelayed(this::finishedTalking, 2000);
        }
    }

    private void finishedTalking() {
        hideVideo();
        if (player != null) {
            player.release();
            player = null;
        }
        busy = false;
        dots.setMode(DotsView.Mode.IDLE);
        hint.setText("Tap and talk to me");
    }

    /** Prefers an offline British female Google voice, which is closest to the old Assistant. */
    private void pickFriendlyVoice() {
        Voice best = null;
        int bestScore = -1;
        try {
            for (Voice v : tts.getVoices()) {
                if (!v.getLocale().getLanguage().equals("en")) continue;
                String name = v.getName().toLowerCase(Locale.ROOT);
                int score = 0;
                if ("GB".equals(v.getLocale().getCountry())) score += 4;
                if (!v.isNetworkConnectionRequired()) score += 2;
                if (name.contains("gba") || name.contains("gbc") || name.contains("gbg")) score += 3;
                if (score > bestScore) {
                    bestScore = score;
                    best = v;
                }
            }
        } catch (Exception ignored) {
            // Some engines throw here; the default voice is fine then.
        }
        if (best != null) tts.setVoice(best);
    }

    private TextView text(String s, int sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
