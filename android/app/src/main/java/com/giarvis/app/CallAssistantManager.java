package com.giarvis.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Gestore autonomo delle conversazioni telefoniche bidirezionali.
 * JARVIS parla in prima persona con l'interlocutore (es. Giulio), ascolta la sua risposta
 * tramite riconoscimento vocale in vivavoce e risponde attivamente durante la telefonata.
 */
public class CallAssistantManager {
    private static final String TAG = "CallAssistantManager";

    public interface CallAssistantListener {
        void onCallStatus(String status);
        void onTranscript(String speaker, String text);
        void onCallFinished(String summary);
    }

    private final Context context;
    private final TelephonyManager telephonyManager;
    private final AudioManager audioManager;
    private final TextToSpeech tts;
    private final String userName;
    private final CallAssistantListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private SpeechRecognizer speechRecognizer;
    private PhoneStateListener phoneStateListener;

    private String contactName = "";
    private String contactNumber = "";
    private String userInstruction = "";
    private boolean isOffHook = false;
    private boolean isGreetingDelivered = false;
    private boolean isAiSpeaking = false;
    private int conversationTurn = 0;
    private final StringBuilder fullCallLog = new StringBuilder();

    public CallAssistantManager(Context context, TextToSpeech tts, CallAssistantListener listener) {
        this.context = context;
        this.tts = tts;
        this.listener = listener;
        this.telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        SharedPreferences prefs = context.getSharedPreferences("jarvis_profile", Context.MODE_PRIVATE);
        this.userName = prefs.getString("user_name", "Jacopo");
    }

    public void startAutonomousCall(String name, String number, String instruction) {
        this.contactName = name;
        this.contactNumber = number;
        this.userInstruction = instruction == null ? "" : instruction.trim();
        this.isOffHook = false;
        this.isGreetingDelivered = false;
        this.isAiSpeaking = false;
        this.conversationTurn = 0;
        this.fullCallLog.setLength(0);

        if (listener != null) {
            listener.onCallStatus("AVVIO CHIAMATA // " + name.toUpperCase(Locale.ROOT));
            listener.onTranscript("JARVIS", "Avvio la telefonata verso " + name + ". Parlerò e risponderò io per tuo conto.");
        }

        setupTelephonyListener();
        AndroidActionRouter.execute(context, "call", number);
    }

    /** Indica se il gestore è agganciato a una chiamata in corso o appena avviata. */
    public boolean isCallSessionActive() {
        return phoneStateListener != null && (isOffHook || !isGreetingDelivered);
    }

    private void setupTelephonyListener() {
        if (telephonyManager == null) return;

        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                super.onCallStateChanged(state, phoneNumber);
                if (state == TelephonyManager.CALL_STATE_OFFHOOK && !isOffHook) {
                    isOffHook = true;
                    if (listener != null) listener.onCallStatus("LINEA APERTA // PARLA CON " + contactName.toUpperCase(Locale.ROOT));
                    // Attendi 3.5 secondi che l'interlocutore risponda al telefono (es. "Pronto?")
                    handler.postDelayed(() -> deliverOpeningGreeting(), 3500);
                } else if (state == TelephonyManager.CALL_STATE_IDLE && isOffHook) {
                    isOffHook = false;
                    onCallEnded();
                }
            }
        };

        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        } catch (SecurityException denied) {
            // Android può negare READ_PHONE_STATE anche dopo CALL_PHONE.
            // La chiamata deve comunque partire senza chiudere JARVIS.
            Log.w(TAG, "READ_PHONE_STATE negato: monitoraggio stato chiamata disattivato", denied);
            phoneStateListener = null;
            if (listener != null) listener.onCallStatus("CHIAMATA AVVIATA // MONITORAGGIO LIMITATO");
        }
    }

    private void deliverOpeningGreeting() {
        if (isGreetingDelivered) return;
        isGreetingDelivered = true;

        enableSpeakerphone();

        String greeting = "Pronto " + contactName + "? Buongiorno, sono J.A.R.V.I.S., l'assistente vocale di " + userName + ". ";
        if (!userInstruction.isEmpty()) {
            greeting += userName + " mi ha chiesto di telefonarti per riferirti: " + userInstruction + ". Tu cosa ne pensi?";
        } else {
            greeting += userName + " mi ha chiesto di mettermi in contatto con te. Mi senti bene?";
        }

        speakInCall(greeting, this::startListeningToContact);
    }

    private void enableSpeakerphone() {
        try {
            if (audioManager != null) {
                // In vivavoce Android deve riprodurre il TTS sullo speaker
                // multimediale: lo stream VOICE_CALL può restare confinato al
                // percorso interno della chiamata e non essere captato dal mic.
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.setSpeakerphoneOn(true);
                int maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0);
                int maxCall = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxCall, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Errore abilitazione vivavoce", e);
        }
    }

    private void speakInCall(String message, Runnable onDone) {
        if (tts == null || message == null || message.trim().isEmpty()) {
            if (onDone != null) onDone.run();
            return;
        }

        isAiSpeaking = true;
        fullCallLog.append("JARVIS: ").append(message).append("\n");
        if (listener != null) {
            listener.onTranscript("JARVIS", message);
            listener.onCallStatus("JARVIS STA PARLANDO...");
        }

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {
                isAiSpeaking = true;
            }

            @Override public void onDone(String utteranceId) {
                isAiSpeaking = false;
                handler.post(() -> {
                    if (onDone != null) onDone.run();
                });
            }

            @Override public void onError(String utteranceId) {
                isAiSpeaking = false;
                handler.post(() -> {
                    if (onDone != null) onDone.run();
                });
            }
        });

        // In vivavoce lo stream musicale viene ripreso dal microfono del telefono
        // e quindi arriva all'interlocutore remoto.
        android.os.Bundle speechParams = new android.os.Bundle();
        speechParams.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
        speechParams.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, speechParams, "call-agent-" + System.currentTimeMillis());
    }

    private void startListeningToContact() {
        if (!isOffHook || isAiSpeaking) return;

        if (listener != null) {
            listener.onCallStatus("ASCOLTO " + contactName.toUpperCase(Locale.ROOT) + "...");
        }

        handler.post(() -> {
            try {
                if (speechRecognizer != null) {
                    speechRecognizer.destroy();
                }
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
                speechRecognizer.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(android.os.Bundle params) {}
                    @Override public void onBeginningOfSpeech() {}
                    @Override public void onRmsChanged(float rmsdB) {}
                    @Override public void onBufferReceived(byte[] buffer) {}
                    @Override public void onEndOfSpeech() {
                        if (listener != null) listener.onCallStatus("ELABORAZIONE RISPOSTA...");
                    }
                    @Override public void onError(int error) {
                        // Se c'è silenzio e non abbiamo superato i turni, riascolta
                        if (isOffHook && !isAiSpeaking && conversationTurn < 4) {
                            handler.postDelayed(() -> startListeningToContact(), 1500);
                        }
                    }
                    @Override public void onResults(android.os.Bundle results) {
                        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null && !matches.isEmpty()) {
                            String heard = matches.get(0).trim();
                            handleContactResponse(heard);
                        } else {
                            if (isOffHook && !isAiSpeaking) startListeningToContact();
                        }
                    }
                    @Override public void onPartialResults(android.os.Bundle partialResults) {}
                    @Override public void onEvent(int eventType, android.os.Bundle params) {}
                });

                android.content.Intent intent = new android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT");
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                speechRecognizer.startListening(intent);
            } catch (Exception e) {
                Log.e(TAG, "Errore avvio ascolto interlocutore", e);
            }
        });
    }

    private void handleContactResponse(String contactSpeech) {
        conversationTurn++;
        fullCallLog.append(contactName).append(": ").append(contactSpeech).append("\n");
        if (listener != null) {
            listener.onTranscript(contactName, contactSpeech);
        }

        String q = contactSpeech.toLowerCase(Locale.ROOT);

        // Se l'interlocutore saluta o chiude la conversazione
        if (q.contains("ciao") || q.contains("va bene") || q.contains("ok") || q.contains("perfetto") || q.contains("ci vediamo") || q.contains("a presto") || q.contains("grazie")) {
            String closing = "Perfetto " + contactName + ", riferisco subito tutto a " + userName + ". Ti auguro una buona giornata, a presto!";
            speakInCall(closing, () -> {
                if (listener != null) {
                    listener.onCallStatus("CONVERSAZIONE CONCLUSA");
                }
            });
            return;
        }

        // Se l'interlocutore chiede dettagli o fa una domanda, chiediamo al Cloud
        queryCloudAiForCallAnswer(contactSpeech);
    }

    private void queryCloudAiForCallAnswer(String contactQuery) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL("https://jarvis-cloud-assistant-4dsr.onrender.com/chat").openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");

                String prompt = "Sei J.A.R.V.I.S. e sei al telefono con " + contactName + " per conto di " + userName + ".\n"
                        + "Istruzione iniziale data da " + userName + ": " + userInstruction + "\n"
                        + "Cronologia telefonata:\n" + fullCallLog.toString() + "\n"
                        + contactName + " ha appena detto: '" + contactQuery + "'.\n"
                        + "Rispondi a " + contactName + " con 1 o 2 frasi cortesi, naturali e concise in italiano, dichiarando che riferirai il suo messaggio a " + userName + ".";

                String body = "{\"message\":\"" + prompt.replace("\"", "\\\"").replace("\n", " ") + "\",\"client\":\"call_agent\",\"session_id\":\"" + userName + "\"}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                Scanner s = new Scanner(is).useDelimiter("\\A");
                String resp = s.hasNext() ? s.next() : "";

                JSONObject json = new JSONObject(resp);
                String aiReply = json.optString("reply", "Ricevuto " + contactName + ", riferisco immediatamente a " + userName + ".");

                handler.post(() -> {
                    speakInCall(aiReply, () -> {
                        if (conversationTurn < 3) {
                            startListeningToContact();
                        }
                    });
                });
            } catch (Exception e) {
                Log.e(TAG, "Errore elaborazione AI per chiamata", e);
                handler.post(() -> {
                    String fallback = "Perfetto " + contactName + ", ho annotato tutto e lo riferisco a " + userName + ". Buona giornata!";
                    speakInCall(fallback, null);
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void onCallEnded() {
        if (telephonyManager != null && phoneStateListener != null) {
            try {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            } catch (Exception ignored) {}
        }

        try {
            if (speechRecognizer != null) {
                speechRecognizer.destroy();
                speechRecognizer = null;
            }
        } catch (Exception ignored) {}

        // Ripristina l'audio del telefono dopo la conversazione: evita che
        // musica, notifiche e chiamate successive restino forzate in vivavoce.
        try {
            if (audioManager != null) {
                audioManager.setSpeakerphoneOn(false);
                audioManager.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (Exception ignored) {}

        String summary = fullCallLog.length() > 0 ? fullCallLog.toString().trim() : "Chiamata conclusa senza trascrizione.";
        if (listener != null) {
            listener.onCallFinished(summary);
            listener.onCallStatus("CHIAMATA TERMINATA");
        }
    }

    public void cleanup() {
        onCallEnded();
        executor.shutdownNow();
    }
}
