package com.giarvis.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.BatteryManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;

/**
 * Persistent JARVIS core. When wake_word_enabled=true, runs continuous
 * SpeechRecognizer listening for "Hey Jarvis" / "Jarvis" (Option B).
 * Mic + PARTIAL_WAKE_LOCK are released when wake listening stops.
 */
public class JarvisForegroundService extends Service {
    public static final String ACTION_START = "com.giarvis.app.START";
    public static final String ACTION_STOP = "com.giarvis.app.STOP";
    public static final String ACTION_LISTEN = "com.giarvis.app.LISTEN";
    public static final String ACTION_WAKE_PAUSE = "com.giarvis.app.WAKE_PAUSE";
    public static final String ACTION_WAKE_RESUME = "com.giarvis.app.WAKE_RESUME";
    public static final String ACTION_WAKE_DETECTED = "com.giarvis.app.WAKE_DETECTED";
    public static final String EXTRA_SPOKEN = "spoken";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_ECHO_TEXT = "echo_text";

    private static final String CHANNEL = "jarvis_core";
    private static final int NOTIFICATION_ID = 7401;
    private static final long RESTART_DELAY_MS = 350L;
    private static final long ERROR_BACKOFF_MS = 900L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer wakeRecognizer;
    private PowerManager.WakeLock partialWakeLock;
    private boolean wakeWanted = false;
    private boolean sessionBusy = false;
    private boolean wakeListening = false;
    private boolean destroyed = false;
    private String hudState = "CORE ONLINE";
    private String echoSuppressText = "";
    private long echoSuppressUntilMs = 0L;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        destroyed = false;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopWakeListening(true);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_LISTEN.equals(action)) {
            Intent open = new Intent(this, MainActivity.class)
                    .setAction(ACTION_LISTEN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(open);
            return START_STICKY;
        }
        if (ACTION_WAKE_PAUSE.equals(action)) {
            sessionBusy = true;
            if (intent != null) {
                String echo = intent.getStringExtra(EXTRA_ECHO_TEXT);
                if (echo != null && !echo.trim().isEmpty()) {
                    echoSuppressText = echo.trim();
                    echoSuppressUntilMs = System.currentTimeMillis() + 2500L;
                }
            }
            stopWakeListening(false);
            hudState = "SESSIONE / TTS (MIC IN PAUSA)";
            updateNotification();
            return START_STICKY;
        }
        if (ACTION_WAKE_RESUME.equals(action)) {
            sessionBusy = false;
            refreshWakePreference();
            if (wakeWanted) {
                hudState = "ASCOLTO WAKE: HEY JARVIS";
                startWakeListening();
            } else {
                hudState = "CORE ONLINE";
            }
            updateNotification();
            return START_STICKY;
        }

        refreshWakePreference();
        try {
            startForegroundCompat(buildNotification());
        } catch (SecurityException denied) {
            stopWakeListening(true);
            stopSelf();
            return START_NOT_STICKY;
        } catch (RuntimeException invalidState) {
            stopWakeListening(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (wakeWanted && !sessionBusy) {
            hudState = "ASCOLTO WAKE: HEY JARVIS";
            startWakeListening();
        } else if (!wakeWanted) {
            hudState = "CORE ONLINE (WAKE OFF)";
            stopWakeListening(true);
        }
        updateNotification();
        return START_STICKY;
    }

    private void refreshWakePreference() {
        SharedPreferences prefs = getSharedPreferences("jarvis_profile", MODE_PRIVATE);
        wakeWanted = prefs.getBoolean(WakePhrase.PREF_WAKE_WORD_ENABLED, false)
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
                && SpeechRecognizer.isRecognitionAvailable(this);
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (wakeWanted) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(NOTIFICATION_ID, notification, type);
        } else if (Build.VERSION.SDK_INT >= 29) {
            int type = wakeWanted
                    ? (ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startWakeListening() {
        if (destroyed || sessionBusy || !wakeWanted) return;
        mainHandler.post(this::ensureWakeRecognizerStarted);
    }

    private void ensureWakeRecognizerStarted() {
        if (destroyed || sessionBusy || !wakeWanted) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            hudState = "WAKE // VOCE NON DISPONIBILE";
            updateNotification();
            return;
        }
        acquirePartialWakeLock();
        try {
            if (wakeRecognizer == null) {
                wakeRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
                wakeRecognizer.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle params) {
                        wakeListening = true;
                        hudState = "ASCOLTO WAKE: HEY JARVIS";
                        updateNotification();
                    }
                    @Override public void onBeginningOfSpeech() {}
                    @Override public void onRmsChanged(float rmsdB) {}
                    @Override public void onBufferReceived(byte[] buffer) {}
                    @Override public void onPartialResults(Bundle partialResults) {
                        maybeDetectWake(partialResults, false);
                    }
                    @Override public void onEvent(int eventType, Bundle params) {}
                    @Override public void onEndOfSpeech() {
                        wakeListening = false;
                    }
                    @Override public void onError(int error) {
                        wakeListening = false;
                        if (destroyed || sessionBusy || !wakeWanted) return;
                        // Restart loop: SpeechRecognizer ends after silence/timeout.
                        long delay = (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                                || error == SpeechRecognizer.ERROR_CLIENT)
                                ? ERROR_BACKOFF_MS : RESTART_DELAY_MS;
                        mainHandler.postDelayed(() -> restartWakeListening(), delay);
                    }
                    @Override public void onResults(Bundle results) {
                        wakeListening = false;
                        maybeDetectWake(results, true);
                        if (!destroyed && wakeWanted && !sessionBusy) {
                            mainHandler.postDelayed(() -> restartWakeListening(), RESTART_DELAY_MS);
                        }
                    }
                });
            }
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            wakeRecognizer.startListening(intent);
            wakeListening = true;
        } catch (Exception e) {
            wakeListening = false;
            mainHandler.postDelayed(this::restartWakeListening, ERROR_BACKOFF_MS);
        }
    }

    private void restartWakeListening() {
        if (destroyed || sessionBusy || !wakeWanted) return;
        try {
            if (wakeRecognizer != null) wakeRecognizer.cancel();
        } catch (Exception ignored) {}
        ensureWakeRecognizerStarted();
    }

    private void maybeDetectWake(Bundle bundle, boolean finalResult) {
        if (bundle == null || sessionBusy || destroyed) return;
        ArrayList<String> matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) return;
        for (String spoken : matches) {
            if (isEchoOfTts(spoken)) continue;
            if (!WakePhrase.containsWake(spoken)) continue;
            String command = WakePhrase.stripWake(spoken);
            onWakeDetected(spoken, command);
            return;
        }
        // Ignore non-wake finals; restart handled by caller.
        if (!finalResult) { /* keep listening for partials */ }
    }

    private void onWakeDetected(String spoken, String command) {
        sessionBusy = true;
        stopWakeListening(false);
        hudState = "WAKE RILEVATO";
        updateNotification();
        try {
            Intent open = new Intent(this, MainActivity.class)
                    .setAction(ACTION_WAKE_DETECTED)
                    .putExtra(EXTRA_SPOKEN, spoken == null ? "" : spoken)
                    .putExtra(EXTRA_COMMAND, command == null ? "" : command)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(open);
        } catch (Exception ignored) {}
        Intent broadcast = new Intent(ACTION_WAKE_DETECTED)
                .setPackage(getPackageName())
                .putExtra(EXTRA_SPOKEN, spoken == null ? "" : spoken)
                .putExtra(EXTRA_COMMAND, command == null ? "" : command);
        sendBroadcast(broadcast);
    }

    private boolean isEchoOfTts(String spoken) {
        if (spoken == null || spoken.trim().isEmpty()) return false;
        if (System.currentTimeMillis() > echoSuppressUntilMs) return false;
        if (echoSuppressText == null || echoSuppressText.isEmpty()) return false;
        String a = WakePhrase.normalize(spoken);
        String b = WakePhrase.normalize(echoSuppressText);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b) || (b.contains(a) && a.length() >= 8) || (a.contains(b) && b.length() >= 8)) return true;
        int n = Math.min(24, Math.min(a.length(), b.length()));
        return n >= 12 && a.regionMatches(0, b, 0, n);
    }

    private void stopWakeListening(boolean releaseLock) {
        wakeListening = false;
        mainHandler.removeCallbacksAndMessages(null);
        try {
            if (wakeRecognizer != null) {
                try { wakeRecognizer.cancel(); } catch (Exception ignored) {}
                try { wakeRecognizer.destroy(); } catch (Exception ignored) {}
                wakeRecognizer = null;
            }
        } catch (Exception ignored) {}
        if (releaseLock) releasePartialWakeLock();
    }

    private void acquirePartialWakeLock() {
        try {
            if (partialWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm == null) return;
                partialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "giarvis:WakeWord");
                partialWakeLock.setReferenceCounted(false);
            }
            if (partialWakeLock != null && !partialWakeLock.isHeld()) {
                // Cap at 4h; user can stop core. Avoid indefinite hold.
                partialWakeLock.acquire(4L * 60L * 60L * 1000L);
            }
        } catch (Exception ignored) {}
    }

    private void releasePartialWakeLock() {
        try {
            if (partialWakeLock != null && partialWakeLock.isHeld()) partialWakeLock.release();
        } catch (Exception ignored) {}
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (Exception ignored) {}
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent listenPending = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class).setAction(ACTION_LISTEN).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopPending = PendingIntent.getService(this, 3,
                new Intent(this, JarvisForegroundService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String detail = hudState + " · Batteria " + batteryPercent() + "% · " + networkState();
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(com.giarvis.app.R.mipmap.ic_launcher)
                .setContentTitle("J.A.R.V.I.S. // CORE ONLINE")
                .setContentText(detail)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(detail
                        + (wakeWanted
                        ? "\nWake word attivo (SpeechRecognizer). Consumo mic continuo."
                        : "\nWake word disattivato. Tap ASCOLTA o microfono.")))
                .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).setContentIntent(openPending)
                .addAction(0, "ASCOLTA", listenPending).addAction(0, "PAUSA", stopPending)
                .build();
    }

    private String batteryPercent() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int value = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return value < 0 ? "N/D" : String.valueOf(value);
    }

    private String networkState() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ? "ONLINE" : "OFFLINE";
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "J.A.R.V.I.S. Core", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Stato del servizio J.A.R.V.I.S. e ascolto wake word");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override public void onDestroy() {
        destroyed = true;
        stopWakeListening(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}