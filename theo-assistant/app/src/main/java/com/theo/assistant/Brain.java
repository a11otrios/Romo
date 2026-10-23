package com.theo.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Matches what Theo said to an intent and decides what to answer with. */
final class Brain {

    static final class Intent {
        String id;
        List<String[]> phrases = new ArrayList<>();
        String fallback;
        boolean builtin;
    }

    /** Either a clip to play or a line to speak (or both empty for nothing). */
    static final class Answer {
        final String intentId;
        final File clip;
        final String speech;

        Answer(String intentId, File clip, String speech) {
            this.intentId = intentId;
            this.clip = clip;
            this.speech = speech;
        }
    }

    private static final Set<String> FILLER = new HashSet<>(Arrays.asList(
            "hey", "ok", "okay", "google", "please", "can", "you", "a", "an", "the", "me", "us",
            "to", "for", "some", "now", "again", "another", "one", "do", "will", "would", "could"));
    private static final String[] CLIP_TYPES = {".mp3", ".m4a", ".ogg", ".opus", ".wav", ".aac"};

    private final List<Intent> intents = new ArrayList<>();
    private final Map<String, String> lastClip = new HashMap<>();
    private final Random random = new Random();
    private final File clipRoot;
    private String unknown;

    Brain(Context context) {
        clipRoot = clipRoot(context);
        try (InputStream in = context.getAssets().open("intents.json")) {
            JSONObject root = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            unknown = root.getString("unknown");
            JSONArray list = root.getJSONArray("intents");
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                Intent intent = new Intent();
                intent.id = o.getString("id");
                intent.fallback = o.optString("fallback", unknown);
                intent.builtin = o.optBoolean("builtin", false);
                JSONArray match = o.getJSONArray("match");
                for (int j = 0; j < match.length(); j++) {
                    intent.phrases.add(normalize(match.getString(j)).split(" "));
                }
                intents.add(intent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("intents.json is broken", e);
        }
    }

    /** Clips live in /sdcard/Android/media/com.theo.assistant/clips/<intent id>/, so adb can push them. */
    static File clipRoot(Context context) {
        File[] media = context.getExternalMediaDirs();
        File base = media.length > 0 && media[0] != null ? media[0] : context.getFilesDir();
        File root = new File(base, "clips");
        root.mkdirs();
        return root;
    }

    List<Intent> intents() {
        return intents;
    }

    File clipDir(String intentId) {
        return new File(clipRoot, intentId);
    }

    List<File> clips(String intentId) {
        List<File> found = new ArrayList<>();
        File[] files = clipDir(intentId).listFiles();
        if (files == null) return found;
        for (File f : files) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            for (String type : CLIP_TYPES) {
                if (name.endsWith(type)) {
                    found.add(f);
                    break;
                }
            }
        }
        return found;
    }

    /** Picks the best of everything the recogniser heard. */
    Answer answer(List<String> heard) {
        Intent best = null;
        int bestScore = 0;
        for (String text : heard) {
            Set<String> words = new HashSet<>(Arrays.asList(normalize(text).split(" ")));
            for (Intent intent : intents) {
                int score = score(intent, words);
                if (score > bestScore) {
                    bestScore = score;
                    best = intent;
                }
            }
        }
        if (best == null) {
            List<File> clips = clips("unknown");
            return new Answer("unknown", pick("unknown", clips), unknown);
        }
        if (best.builtin) return new Answer(best.id, null, builtin(best.id));
        return new Answer(best.id, pick(best.id, clips(best.id)), best.fallback);
    }

    /** Score is the number of words in the longest phrase whose words were all heard. */
    private static int score(Intent intent, Set<String> words) {
        int best = 0;
        for (String[] phrase : intent.phrases) {
            int needed = 0;
            boolean all = true;
            for (String w : phrase) {
                if (w.isEmpty() || FILLER.contains(w)) continue;
                needed++;
                if (!words.contains(w)) {
                    all = false;
                    break;
                }
            }
            if (all && needed > best) best = needed;
        }
        return best;
    }

    /** Random clip, never the same one twice in a row when there is a choice. */
    private File pick(String intentId, List<File> clips) {
        if (clips.isEmpty()) return null;
        File choice = clips.get(random.nextInt(clips.size()));
        String last = lastClip.get(intentId);
        if (clips.size() > 1 && choice.getName().equals(last)) {
            choice = clips.get((clips.indexOf(choice) + 1) % clips.size());
        }
        lastClip.put(intentId, choice.getName());
        return choice;
    }

    private String builtin(String id) {
        switch (id) {
            case "coin":
                return random.nextBoolean() ? "It's heads!" : "It's tails!";
            case "dice":
                return "You rolled a " + (1 + random.nextInt(6)) + "!";
            case "number":
                return "I pick " + (1 + random.nextInt(10)) + "!";
            case "rps": {
                String[] moves = {"rock", "paper", "scissors"};
                return "I choose " + moves[random.nextInt(3)] + "!";
            }
            case "time": {
                Calendar now = Calendar.getInstance();
                int hour = now.get(Calendar.HOUR);
                return String.format(Locale.UK, "It's %d:%02d.", hour == 0 ? 12 : hour, now.get(Calendar.MINUTE));
            }
            default:
                return unknown;
        }
    }

    static String normalize(String text) {
        String s = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace("'", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        // The recogniser hears "favourite" either way; keep matching simple.
        return s.replace("favorite", "favourite").replace("color", "colour");
    }
}
