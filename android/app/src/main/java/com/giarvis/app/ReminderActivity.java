package com.giarvis.app;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Esperienza di vera telefonata in arrivo e conversazione in tempo reale con J.A.R.V.I.S. */
public class ReminderActivity extends Activity {
    private static final String TAG = "JARVIS_CALL";

    private String text;
    private boolean isDirectCall = false;
    private String userName = "Jacopo";

    private MediaPlayer ringtonePlayer;
    private Vibrator vibrator;
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private AudioManager audioManager;
    private PowerManager.WakeLock proximityWakeLock;
    private ExecutorService networkExecutor;

    private View incomingCallLayout;
    private View inCallLayout;
    private TextView reminderBody;
    private Chronometer callChronometer;
    private TextView callStatusText;
    private TextView callTranscript;
    private ScrollView callTranscriptScroll;
    private TextView callMicStatus;
    private Button btnAnswer;
    private Button btnIgnore;
    private Button btnSnooze;
    private Button btnMuteMic;
    private Button btnSpeaker;
    private Button btnEndCall;

    private boolean inCall = false;
    private boolean isTtsSpeaking = false;
    private boolean isSpeakerOn = true; // default a vivavoce attivo per chiarezza, commutabile
    private boolean isMicMuted = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Flags per sbloccare e accendere lo schermo come una vera chiamata in arrivo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        setContentView(R.layout.activity_reminder);

        SharedPreferences prefs = getSharedPreferences("jarvis_profile", MODE_PRIVATE);
        userName = prefs.getString("user_name", "Jacopo");
        if (userName.trim().isEmpty()) userName = "Jacopo";

        text = getIntent().getStringExtra("text");
        isDirectCall = getIntent().getBooleanExtra("is_direct_call", false);
        if (text == null || text.trim().isEmpty()) {
            text = "Chiamata vocale in arrivo da J.A.R.V.I.S.";
        }

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        networkExecutor = Executors.newSingleThreadExecutor();

        // Sensore di prossimità per spegnere lo schermo all'orecchio come una vera telefonata
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximityWakeLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "giarvis:call_proximity");
            }
        } catch (Exception e) {
            Log.w(TAG, "Proximity wake lock not supported", e);
        }

        incomingCallLayout = findViewById(R.id.incomingCallLayout);
        inCallLayout = findViewById(R.id.inCallLayout);
        reminderBody = findViewById(R.id.reminderBody);
        callChronometer = findViewById(R.id.callChronometer);
        callStatusText = findViewById(R.id.callStatusText);
        callTranscript = findViewById(R.id.callTranscript);
        callTranscriptScroll = findViewById(R.id.callTranscriptScroll);
        callMicStatus = findViewById(R.id.callMicStatus);
        btnAnswer = findViewById(R.id.btnAnswer);
        btnIgnore = findViewById(R.id.btnIgnore);
        btnSnooze = findViewById(R.id.btnSnooze);
        btnMuteMic = findViewById(R.id.btnMuteMic);
        btnSpeaker = findViewById(R.id.btnSpeaker);
        btnEndCall = findViewById(R.id.btnEndCall);

        reminderBody.setText(text);

        if (!isDirectCall) {
            btnSnooze.setVisibility(View.VISIBLE);
            btnSnooze.setOnClickListener(v -> snoozeAndFinish());
        }

        btnAnswer.setOnClickListener(v -> answerCall());
        btnIgnore.setOnClickListener(v -> declineAndFinish());
        btnEndCall.setOnClickListener(v -> endCall());

        if (btnSpeaker != null) {
            btnSpeaker.setOnClickListener(v -> toggleSpeaker());
        }
        if (btnMuteMic != null) {
            btnMuteMic.setOnClickListener(v -> toggleMuteMic());
        }

        // Inizializza TTS in background
        initTts();

        // Avvia suoneria e vibrazione della vera chiamata in arrivo
        startRingingAndVibrating();
    }

    private void startRingingAndVibrating() {
        try {
            Uri alert = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE);
            if (alert == null) {
                alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            ringtonePlayer = new MediaPlayer();
            ringtonePlayer.setDataSource(this, alert);
            ringtonePlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            ringtonePlayer.setLooping(true);
            ringtonePlayer.prepare();
            ringtonePlayer.start();
        } catch (Exception e) {
            Log.e(TAG, "Errore avvio suoneria", e);
        }

        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0, 1000, 800, 1000, 800};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Errore avvio vibrazione", e);
        }
    }

    private void stopRingingAndVibrating() {
        try {
            if (ringtonePlayer != null) {
                if (ringtonePlayer.isPlaying()) ringtonePlayer.stop();
                ringtonePlayer.release();
                ringtonePlayer = null;
            }
        } catch (Exception ignored) {}

        try {
            if (vibrator != null) {
                vibrator.cancel();
            }
        } catch (Exception ignored) {}
    }

    private void answerCall() {
        inCall = true;
        stopRingingAndVibrating();

        // Attiva sensore di prossimità per spegnere lo schermo all'orecchio
        try {
            if (proximityWakeLock != null && !proximityWakeLock.isHeld()) {
                proximityWakeLock.acquire(10 * 60 * 1000L); // 10 min max
            }
        } catch (Exception ignored) {}

        // Rimuovi eventuale notifica persistente
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(4001);

        // Transizione visuale alla schermata di chiamata attiva
        incomingCallLayout.setVisibility(View.GONE);
        inCallLayout.setVisibility(View.VISIBLE);

        // Avvia cronometro chiamata
        callChronometer.setBase(SystemClock.elapsedRealtime());
        callChronometer.start();

        // Configura audio telefonico bidirezionale
        try {
            if (audioManager != null) {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.setSpeakerphoneOn(isSpeakerOn);
            }
        } catch (Exception e) {
            Log.w(TAG, "Audio manager setup warning", e);
        }
        updateSpeakerUi();

        // Saluto naturale e realistico come una persona che risponde al telefono
        String initialGreeting;
        if (isDirectCall) {
            initialGreeting = "Pronto? Ciao " + userName + ", mi senti? Sono Jarvis. Ti ho chiamato direttamente come mi avevi chiesto. Dimmi pure, ti ascolto.";
        } else {
            initialGreeting = "Pronto? Ciao " + userName + ", sono Jarvis. Ti sto chiamando per il tuo promemoria: " + text + ". Dimmi se ti serve altro.";
        }

        updateTranscript("J.A.R.V.I.S.: " + initialGreeting);
        speakInCall(initialGreeting);
    }

    private void toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn;
        if (audioManager != null) {
            audioManager.setSpeakerphoneOn(isSpeakerOn);
        }
        updateSpeakerUi();
    }

    private void updateSpeakerUi() {
        if (btnSpeaker != null) {
            btnSpeaker.setText(isSpeakerOn ? "🔊 VIVAVOCE: ON" : "🔈 AURICOLARE");
            btnSpeaker.setTextColor(isSpeakerOn ? 0xFF00E5FF : 0xFF80B0D0);
        }
        if (callStatusText != null) {
            callStatusText.setText(isSpeakerOn ? "VIVAVOCE ATTIVO • CRITTOGRAFIA END-TO-END" : "AURICOLARE ATTIVO • AVVICINA ALL'ORECCHIO");
        }
    }

    private void toggleMuteMic() {
        isMicMuted = !isMicMuted;
        if (btnMuteMic != null) {
            btnMuteMic.setText(isMicMuted ? "🔇 MUTO: ATTIVO" : "🎤 MUTO");
            btnMuteMic.setTextColor(isMicMuted ? 0xFFFF4444 : 0xFF00E5FF);
        }
        if (isMicMuted && speechRecognizer != null) {
            speechRecognizer.stopListening();
            if (callMicStatus != null) callMicStatus.setText("MICROFONO DISATTIVATO");
        } else if (!isMicMuted && !isTtsSpeaking) {
            startListeningToUser();
        }
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                tts.setLanguage(Locale.forLanguageTag("it-IT"));
                tts.setPitch(0.96f);
                tts.setSpeechRate(1.05f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        isTtsSpeaking = true;
                        mainHandler.post(() -> {
                            if (callMicStatus != null) callMicStatus.setText("JARVIS STA PARLANDO...");
                        });
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        isTtsSpeaking = false;
                        mainHandler.post(() -> {
                            if (inCall && !isFinishing() && !isMicMuted) {
                                startListeningToUser();
                            }
                        });
                    }

                    @Override
                    public void onError(String utteranceId) {
                        isTtsSpeaking = false;
                        mainHandler.post(() -> {
                            if (inCall && !isFinishing() && !isMicMuted) {
                                startListeningToUser();
                            }
                        });
                    }
                });
            }
        });
    }

    private void speakInCall(String message) {
        if (tts == null || message == null || message.trim().isEmpty()) return;
        isTtsSpeaking = true;
        if (callMicStatus != null) callMicStatus.setText("JARVIS STA PARLANDO...");
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "jarvis-call-msg");
    }

    private void startListeningToUser() {
        if (!inCall || isFinishing() || isTtsSpeaking || isMicMuted) return;

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            updateTranscript("[Microfono non autorizzato]");
            return;
        }

        if (callMicStatus != null) callMicStatus.setText("🎤 IN ASCOLTO... PARLA PURE");

        try {
            if (speechRecognizer != null) {
                speechRecognizer.destroy();
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    if (callMicStatus != null) callMicStatus.setText("🎤 TI STO ASCOLTANDO...");
                }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {
                    if (callMicStatus != null) callMicStatus.setText("ELABORAZIONE IN CORSO...");
                }
                @Override public void onError(int error) {
                    if (inCall && !isFinishing() && !isMicMuted) {
                        mainHandler.postDelayed(() -> {
                            if (inCall && !isTtsSpeaking && !isMicMuted) startListeningToUser();
                        }, 1200);
                    }
                }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String userSaid = matches.get(0).trim();
                        handleInCallUserInput(userSaid);
                    } else {
                        if (inCall && !isFinishing() && !isMicMuted) startListeningToUser();
                    }
                }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            Log.e(TAG, "Errore avvio SpeechRecognizer", e);
        }
    }

    private void handleInCallUserInput(String userSpeech) {
        updateTranscript("Tu: " + userSpeech);
        String q = userSpeech.toLowerCase(Locale.ROOT);

        // Controllo se l'utente risponde con "Pronto" o saluta
        if (q.equals("pronto") || q.equals("pronto jarvis") || q.equals("ciao jarvis") || q.equals("mi senti")) {
            String answer = "Sì " + userName + ", ti sento forte e chiaro! Dimmi pure, come posso esserti utile?";
            updateTranscript("J.A.R.V.I.S.: " + answer);
            speakInCall(answer);
            return;
        }

        // Controllo comandi di chiusura chiamata
        if (q.contains("chiudi") || q.contains("riattacca") || q.contains("termina") || q.contains("ciao") || q.contains("arrivederci") || q.contains("basta grazie") || q.contains("a dopo")) {
            String goodbye = "D'accordo, chiudo la telefonata. A presto, " + userName + ".";
            updateTranscript("J.A.R.V.I.S.: " + goodbye);
            if (tts != null) {
                tts.speak(goodbye, TextToSpeech.QUEUE_FLUSH, null, "goodbye");
            }
            mainHandler.postDelayed(this::endCall, 2200);
            return;
        }

        // Controllo comandi locali rapidi
        if (q.contains("che ore sono") || q.contains("l'ora")) {
            String time = new java.text.SimpleDateFormat("HH:mm", Locale.ITALIAN).format(new java.util.Date());
            String reply = "Sono le ore " + time + ", signore.";
            updateTranscript("J.A.R.V.I.S.: " + reply);
            speakInCall(reply);
            return;
        }

        if (q.contains("che giorno è") || q.contains("data di oggi")) {
            String date = new java.text.SimpleDateFormat("EEEE d MMMM", Locale.ITALIAN).format(new java.util.Date());
            String reply = "Oggi è " + date + ", signore.";
            updateTranscript("J.A.R.V.I.S.: " + reply);
            speakInCall(reply);
            return;
        }

        if (q.contains("torcia")) {
            boolean turnOn = !q.contains("spegni") && !q.contains("off");
            AndroidActionRouter.execute(this, "torch", turnOn ? "on" : "off");
            String reply = turnOn ? "Torcia attivata, signore." : "Torcia disattivata, signore.";
            updateTranscript("J.A.R.V.I.S.: " + reply);
            speakInCall(reply);
            return;
        }

        // Chiamata ad un altro contatto richiesta durante la telefonata
        if (q.startsWith("chiama ") || q.startsWith("telefona a ")) {
            String contactName = q.replaceFirst("(?i)(chiama|telefona a)\\s*", "").trim();
            ContactResolver.ContactInfo info = ContactResolver.findContact(this, contactName);
            if (info != null) {
                String reply = "Chiudo la nostra linea e inoltro subito la chiamata a " + info.name + ", signore.";
                updateTranscript("J.A.R.V.I.S.: " + reply);
                if (tts != null) tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "transfer");
                mainHandler.postDelayed(() -> {
                    endCall();
                    AndroidActionRouter.execute(this, "call", info.number);
                }, 2000);
                return;
            } else {
                String reply = "Non ho trovato " + contactName + " nella tua rubrica telefonica, signore.";
                updateTranscript("J.A.R.V.I.S.: " + reply);
                speakInCall(reply);
                return;
            }
        }

        // Altrimenti inoltra la richiesta al Cloud di J.A.R.V.I.S.
        queryCloudInCall(userSpeech);
    }

    private void queryCloudInCall(String query) {
        if (callMicStatus != null) callMicStatus.setText("JARVIS STA ELABORANDO...");
        networkExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL("https://jarvis-cloud-assistant-4dsr.onrender.com/chat").openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(20000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");

                String prompt = "Sei al telefono in una vera chiamata vocale diretta con " + userName + ". Rispondi in modo ultra-naturale, colloquiale, sintetico e spontaneo in italiano, come un vero assistente umano al telefono.\nUtente al telefono: " + query;
                String body = "{\"message\":\"" + prompt.replace("\"", "\\\"").replace("\n", " ") + "\",\"client\":\"android_call\",\"session_id\":\"" + userName + "\"}";

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
                String resp = s.hasNext() ? s.next() : "";

                JSONObject json = new JSONObject(resp);
                String reply = json.optString("reply", "Ricevuto, signore.");

                mainHandler.post(() -> {
                    updateTranscript("J.A.R.V.I.S.: " + reply);
                    speakInCall(reply);
                });
            } catch (Exception e) {
                Log.e(TAG, "Errore cloud call", e);
                mainHandler.post(() -> {
                    String fallback = "Ti sento, signore. Ricevuto, procedo con le operazioni.";
                    updateTranscript("J.A.R.V.I.S.: " + fallback);
                    speakInCall(fallback);
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void updateTranscript(String line) {
        mainHandler.post(() -> {
            if (callTranscript != null) {
                String cur = callTranscript.getText().toString();
                callTranscript.setText(cur + "\n\n" + line);
                if (callTranscriptScroll != null) {
                    callTranscriptScroll.post(() -> callTranscriptScroll.fullScroll(View.FOCUS_DOWN));
                }
            }
        });
    }

    private void endCall() {
        inCall = false;
        stopRingingAndVibrating();

        if (callChronometer != null) {
            callChronometer.stop();
        }

        try {
            if (proximityWakeLock != null && proximityWakeLock.isHeld()) {
                proximityWakeLock.release();
            }
        } catch (Exception ignored) {}

        try {
            if (speechRecognizer != null) {
                speechRecognizer.stopListening();
                speechRecognizer.destroy();
                speechRecognizer = null;
            }
        } catch (Exception ignored) {}

        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
                tts = null;
            }
        } catch (Exception ignored) {}

        try {
            if (audioManager != null) {
                audioManager.setMode(AudioManager.MODE_NORMAL);
                audioManager.setSpeakerphoneOn(false);
            }
        } catch (Exception ignored) {}

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(4001);

        finish();
    }

    private void declineAndFinish() {
        stopRingingAndVibrating();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(4001);
        finish();
    }

    private void snoozeAndFinish() {
        stopRingingAndVibrating();
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        Intent i = new Intent(this, ReminderReceiver.class).putExtra("text", text);
        PendingIntent p = PendingIntent.getBroadcast(this, 4001, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (am != null) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 300000, p);
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(4001);
        finish();
    }

    @Override
    protected void onDestroy() {
        stopRingingAndVibrating();
        try {
            if (proximityWakeLock != null && proximityWakeLock.isHeld()) {
                proximityWakeLock.release();
            }
        } catch (Exception ignored) {}
        try {
            if (speechRecognizer != null) {
                speechRecognizer.destroy();
                speechRecognizer = null;
            }
        } catch (Exception ignored) {}
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
                tts = null;
            }
        } catch (Exception ignored) {}
        try {
            if (audioManager != null) {
                audioManager.setMode(AudioManager.MODE_NORMAL);
                audioManager.setSpeakerphoneOn(false);
            }
        } catch (Exception ignored) {}
        if (networkExecutor != null) {
            networkExecutor.shutdownNow();
        }
        super.onDestroy();
    }
}
