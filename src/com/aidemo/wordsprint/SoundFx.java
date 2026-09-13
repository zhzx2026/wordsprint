package com.aidemo.wordsprint;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/** 轻音效（ToneGenerator）+ 触感 + TTS 朗读 */
public class SoundFx {
    private ToneGenerator tg;
    private Vibrator vib;
    private TextToSpeech tts;
    private boolean ttsReady;
    private final Prefs prefs;

    public SoundFx(Context c) {
        prefs = Prefs.of(c);
        try { tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 55); } catch (Exception ignored) {}
        try { vib = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE); } catch (Exception ignored) {}
        try {
            tts = new TextToSpeech(c.getApplicationContext(), new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS) {
                        try {
                            int r = tts.setLanguage(Locale.US);
                            ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                            tts.setSpeechRate(0.9f);
                            tts.setPitch(1.0f);
                        } catch (Exception e) { ttsReady = false; }
                    }
                }
            });
        } catch (Exception ignored) {}
    }

    public void ok() {
        if (!prefs.on(Prefs.K_SOUND, true)) return;
        try { if (tg != null) tg.startTone(ToneGenerator.TONE_PROP_ACK, 110); } catch (Exception ignored) {}
        tick(18);
    }
    public void miss() {
        if (!prefs.on(Prefs.K_SOUND, true)) return;
        try { if (tg != null) tg.startTone(ToneGenerator.TONE_PROP_NACK, 130); } catch (Exception ignored) {}
        tick(32);
    }
    private void tick(long ms) {
        try {
            if (vib != null && vib.hasVibrator())
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {}
    }
    public void speak(String word) {
        if (!prefs.on(Prefs.K_SPEAK, true)) return;
        try {
            if (ttsReady && tts != null && word != null && word.length() <= 40)
                tts.speak(word, TextToSpeech.QUEUE_FLUSH, null, "wp");
        } catch (Exception ignored) {}
    }
    public void shutdown() {
        try { if (tg != null) tg.release(); } catch (Exception ignored) {}
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception ignored) {}
        ttsReady = false;
    }
}
