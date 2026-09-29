package com.giarvis.app;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.RecognitionListener;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.media.MediaPlayer;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.WindowManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.view.inputmethod.EditorInfo;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.telephony.PhoneStateListener;
import androidx.core.content.ContextCompat;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String DEFAULT_BACKEND_URL = BuildConfig.BACKEND_URL;
    private EditText input;
    private TextView transcript, status, privacyStatus, deviceStatus;
    private SpeechRecognizer recognizer;
    private TextToSpeech speaker;
    private MediaPlayer voicePlayer;
    private String lastSpokenText = "";
    private String userName = "";
    private String sessionId = "";
    private String memory = "";
    private SharedPreferences preferences;
    private ReactorView reactor;
    private final java.util.concurrent.ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean requestInFlight = false;
    private volatile boolean destroyed = false;
    private volatile int retryAttempts = 0;
    private volatile boolean voiceEnabled = true;
    private JarvisWebRtcManager webRtcManager;
    private final Handler speechHandler = new Handler(Looper.getMainLooper());
    private boolean speechListening = false;
    private boolean speechResultReceived = false;
    private int speechRestartAttempts = 0;
    private PowerManager.WakeLock screenWakeLock;
    private boolean wakeLockUserEnabled = false;
    private boolean wakeWordEnabled = false;
    private boolean sessionKeepAwake = false;
    private volatile boolean ttsPlaying = false;
    private long ttsCooldownUntilMs = 0L;
    private static final long ECHO_COOLDOWN_MS = 1100L;
    private final BroadcastReceiver wakeWordReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (JarvisForegroundService.ACTION_WAKE_DETECTED.equals(intent.getAction())) {
                handleWakeDetected(intent);
            }
        }
    };
    private static volatile boolean visible = false;

    public static boolean isAppVisible() { return visible; }

    private final BroadcastReceiver whatsappReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (status == null || transcript == null || isFinishing() || isDestroyed()) return;
            String sender = i.getStringExtra("sender");
            String text = "C'è una notifica da WhatsApp" + (sender == null || sender.isEmpty() ? "." : " da " + sender + ".");
            status.setText("WHATSAPP // NOTIFICA");
            transcript.setText("// NOTIFICA WHATSAPP\n\n" + text);
            speakMetal(text);
        }
    };

    private String getBackendUrl() {
        return preferences == null ? DEFAULT_BACKEND_URL : preferences.getString("custom_backend_url", DEFAULT_BACKEND_URL);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(5, 11, 18));
        getWindow().setNavigationBarColor(Color.rgb(5, 11, 18));
        
        preferences = getSharedPreferences("jarvis_profile", MODE_PRIVATE);
        userName = preferences.getString("user_name", "");
        sessionId = preferences.getString("assistant_session_id", "");
        if (sessionId.trim().isEmpty()) {
            sessionId = UUID.randomUUID().toString();
            preferences.edit().putString("assistant_session_id", sessionId).apply();
        }
        memory = preferences.getString("conversation_memory", "");
        voiceEnabled = preferences.getBoolean("voice_enabled", true);
        wakeLockUserEnabled = preferences.getBoolean("wake_lock_enabled", false);
        wakeWordEnabled = preferences.getBoolean(WakePhrase.PREF_WAKE_WORD_ENABLED, false);
        if (!preferences.contains("tts_engine")) {
            preferences.edit().putString("tts_engine", "edge-server").apply();
        }

        setupXmlUi();
        applyScreenWakePolicy();
        webRtcManager = new JarvisWebRtcManager(this);
        new Handler(Looper.getMainLooper()).postDelayed(this::probeBackendHealth, 900L);

        speaker = new TextToSpeech(this, r -> {
            TextToSpeech engine = speaker;
            if (r == TextToSpeech.SUCCESS && engine != null) {
                Locale italian = Locale.forLanguageTag("it-IT");
                engine.setLanguage(italian);
                if (engine.getVoices() != null) {
                    Voice best = null;
                    for (Voice candidate : engine.getVoices()) {
                        if (candidate.getLocale().getLanguage().equals("it") && !candidate.isNetworkConnectionRequired()) {
                            if (best == null || candidate.getQuality() > best.getQuality()) best = candidate;
                        }
                    }
                    if (best != null) engine.setVoice(best);
                }
                engine.setPitch(0.96f);
                float speedFactor = 0.75f + (preferences.getInt("voice_speed", 50) / 100f) * 0.5f;
                engine.setSpeechRate(speedFactor);
            }
        });

        if (userName.trim().isEmpty()) {
            new Handler().postDelayed(this::askUserName, 450);
        }

        List<String> perms = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO);
        }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.CALL_PHONE);
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.READ_CONTACTS);
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!perms.isEmpty()) {
            requestPermissions(perms.toArray(new String[0]), 10);
        }

        try {
            IntentFilter filter = new IntentFilter("com.giarvis.WHATSAPP_NOTIFICATION");
            ContextCompat.registerReceiver(this, whatsappReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Exception ignored) {}
        try {
            IntentFilter wakeFilter = new IntentFilter(JarvisForegroundService.ACTION_WAKE_DETECTED);
            ContextCompat.registerReceiver(this, wakeWordReceiver, wakeFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Exception ignored) {}
        // Best-effort pull of server persistent memory (404 = endpoint not ready yet).
        try {
            MemoryApiClient.pull(this, getBackendUrl(), sessionId, (ok, body) -> {
                if (ok && preferences != null) {
                    memory = preferences.getString("conversation_memory", memory);
                }
            });
        } catch (Exception ignored) {}

        new Handler().postDelayed(this::offerNotificationAccess, 900);
    }

    private void setupXmlUi() {
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        privacyStatus = findViewById(R.id.privacyStatus);
        deviceStatus = findViewById(R.id.deviceStatus);
        transcript = findViewById(R.id.transcript);
        input = findViewById(R.id.commandInput);

        Button startCore = findViewById(R.id.btnCoreOn);
        Button stopCore = findViewById(R.id.btnCoreOff);
        Button send = findViewById(R.id.btnSend);
        Button mic = findViewById(R.id.btnMic);
        Button plus = findViewById(R.id.btnPlus);
        Button history = findViewById(R.id.btnHistory);
        Button settings = findViewById(R.id.btnSettings);

        FrameLayout reactorContainer = findViewById(R.id.reactorContainer);
        reactor = new ReactorView(this);
        reactorContainer.addView(reactor, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        startCore.setOnClickListener(v -> startJarvisService());
        stopCore.setOnClickListener(v -> {
            if (preferences != null) preferences.edit().putBoolean("jarvis_core_wanted", false).apply();
            stopService(new Intent(this, JarvisForegroundService.class));
            status.setText(standbyStatusText());
        });

        send.setOnClickListener(v -> {
            String cmd = input.getText().toString().trim();
            if (!cmd.isEmpty()) {
                input.setText("");
                dispatchUserCommand(cmd);
            }
        });

        mic.setOnClickListener(v -> listen());

        input.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_DONE) {
                String cmd = input.getText().toString().trim();
                if (!cmd.isEmpty()) {
                    input.setText("");
                    dispatchUserCommand(cmd);
                }
                return true;
            }
            return false;
        });

        plus.setOnClickListener(v -> startActivity(new Intent(this, ImageStudioActivity.class)));
        Button help = findViewById(R.id.btnHelp);
        if (help != null) help.setOnClickListener(v -> showHelpDialog());
        history.setOnClickListener(v -> startActivity(new Intent(this, ChatHistoryActivity.class)));
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        updateDeviceStatus();
        updatePrivacyStatus();
    }

    private void startJarvisService() {
        wakeWordEnabled = preferences != null && preferences.getBoolean(WakePhrase.PREF_WAKE_WORD_ENABLED, false);
        if (preferences != null) preferences.edit().putBoolean("jarvis_core_wanted", true).apply();
        Intent service = new Intent(this, JarvisForegroundService.class).setAction(JarvisForegroundService.ACTION_START);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            if (status != null) {
                status.setText(wakeWordEnabled
                        ? "CORE // ASCOLTO WAKE (HEY JARVIS)"
                        : "CORE // ONLINE (TAP MIC O ABILITA WAKE)");
            }
        } catch (Exception ignored) {
            if (status != null) status.setText("CORE // AVVIO FALLITO");
        }
    }

    private String standbyStatusText() {
        return wakeWordEnabled
                ? "STANDBY // DI' HEY JARVIS O TOCCA"
                : "STANDBY // TOCCA PER PARLARE";
    }

    private void notifyWakeSessionBusy() {
        try {
            Intent i = new Intent(this, JarvisForegroundService.class).setAction(JarvisForegroundService.ACTION_WAKE_PAUSE);
            if (lastSpokenText != null && !lastSpokenText.isEmpty()) {
                i.putExtra(JarvisForegroundService.EXTRA_ECHO_TEXT, lastSpokenText);
            }
            startService(i);
        } catch (Exception ignored) {}
    }

    private void notifyWakeSessionIdle() {
        if (!wakeWordEnabled) return;
        if (preferences == null || !preferences.getBoolean("jarvis_core_wanted", false)) return;
        try {
            Intent i = new Intent(this, JarvisForegroundService.class).setAction(JarvisForegroundService.ACTION_WAKE_RESUME);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) {}
    }

    private void handleWakeDetected(Intent intent) {
        if (destroyed) return;
        String command = intent == null ? "" : intent.getStringExtra(JarvisForegroundService.EXTRA_COMMAND);
        if (command == null) command = "";
        command = command.trim();
        if (status != null) status.setText("WAKE // HEY JARVIS RILEVATO");
        if (!command.isEmpty()) {
            notifyWakeSessionBusy();
            if (input != null) input.setText(command);
            handleUserCommand(command);
        } else {
            listen();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null) return;
        String action = intent.getAction();
        if (JarvisForegroundService.ACTION_WAKE_DETECTED.equals(action)) {
            handleWakeDetected(intent);
        } else if (JarvisForegroundService.ACTION_LISTEN.equals(action)) {
            listen();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        new Handler().postDelayed(this::speakFirstGreetingIfNeeded, 650);
        new Handler().postDelayed(this::offerNotificationAccess, 1100);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            updateDeviceStatus();
            new Handler().postDelayed(this::speakFirstGreetingIfNeeded, 250);
        }
    }

    @Override
    protected void onStop() {
        visible = false;
        super.onStop();
    }

    private void offerNotificationAccess() {
        updatePrivacyStatus();
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && visible && !isFinishing() && !isDestroyed()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Notifiche J.A.R.V.I.S.")
                    .setMessage("Le notifiche dell'app sono bloccate. Attivale per ricevere promemoria, stato del Core e avvisi delle chiamate.")
                    .setNegativeButton("NON ORA", null)
                    .setPositiveButton("SBLOCCA NOTIFICHE", (d, w) -> openAppNotificationSettings())
                    .show();
            return;
        }
        if (!isNotificationAccessEnabled() && !isFinishing() && !isDestroyed() && visible) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Notifiche WhatsApp")
                    .setMessage("Permetti a J.A.R.V.I.S. di avvisarti solo mentre l'app è aperta?")
                    .setNegativeButton("NON ORA", null)
                    .setPositiveButton("APRI IMPOSTAZIONI", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_SETTINGS));
                        }
                    }).show();
        }
    }

    private void openAppNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private boolean isNotificationAccessEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }

    private void speakFirstGreetingIfNeeded() {
        if (preferences == null || userName.trim().isEmpty() || preferences.getBoolean("first_greeting_done", false)) return;
        preferences.edit().putBoolean("first_greeting_done", true).apply();
        speakMetal("Buongiorno " + userName + ".");
    }

    private void updatePrivacyStatus() {
        if (privacyStatus == null) return;
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean appNotifications = android.os.Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean notificationReader = isNotificationAccessEnabled();
        privacyStatus.setText("PRIVACY  //  MICROFONO: " + (mic ? "ON" : "OFF")
                + "   NOTIFICHE APP: " + (appNotifications ? "ON" : "OFF")
                + "   LETTORE: " + (notificationReader ? "ON" : "OFF"));
    }

    private void updateDeviceStatus() {
        if (deviceStatus == null) return;
        android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
        int battery = bm == null ? -1 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        boolean online = nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        long free = getFilesDir().getFreeSpace() / 1024 / 1024;
        String wake = (wakeLockUserEnabled || sessionKeepAwake) ? "ON" : "OFF";
        String ww = wakeWordEnabled ? "ON" : "OFF";
        deviceStatus.setText("DEVICE  // BATTERIA: " + (battery < 0 ? "N/D" : battery + "%") + "   RETE: " + (online ? "ONLINE" : "OFFLINE") + "   WAKE: " + wake + "   WW: " + ww + "   LIBERO: " + free + " MB");
    }

    private void askUserName() {
        final EditText name = new EditText(this);
        name.setHint("Il tuo nome");
        name.setSingleLine(true);
        name.setTextColor(Color.WHITE);
        name.setHintTextColor(Color.GRAY);
        name.setPadding(dp(16), 0, dp(16), 0);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Benvenuto in J.A.R.V.I.S.")
                .setMessage("Come devo chiamarti?")
                .setView(name)
                .setCancelable(false)
                .setPositiveButton("CONTINUA", null)
                .create();
        dialog.setOnShowListener(v -> {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(0, 180, 220));
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
                String value = name.getText().toString().trim();
                if (value.isEmpty()) {
                    name.setError("Inserisci il tuo nome");
                    return;
                }
                userName = value;
                preferences.edit().putString("user_name", userName).apply();
                status.setText("ONLINE // PRONTO, " + userName.toUpperCase(Locale.ROOT));
                transcript.setText("// FEED ATTIVITA  // MISSION LOG\nJ.A.R.V.I.S. online. Benvenuto, " + userName + ".\nDi' Hey Jarvis (se wake ON) oppure tocca il microfono.");
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 10) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            status.setText(granted ? standbyStatusText() : "PERMESSO MICROFONO NEGATO");
        }
        updatePrivacyStatus();
    }

    private long lastCommandTimestamp = 0;
    private String lastCommandText = "";

    private void dispatchUserCommand(String message) {
        if (message == null || message.trim().isEmpty()) return;
        String trimmed = message.trim();
        long now = System.currentTimeMillis();
        if (trimmed.equalsIgnoreCase(lastCommandText) && (now - lastCommandTimestamp) < 1000) {
            return;
        }
        lastCommandTimestamp = now;
        lastCommandText = trimmed;
        if (input != null) input.setText("");
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("cancella memoria") || lower.contains("dimentica tutto")) {
            memory = "";
            String keepSession = sessionId;
            String keepBackend = getBackendUrl();
            boolean keepWakeWord = preferences.getBoolean(WakePhrase.PREF_WAKE_WORD_ENABLED, false);
            boolean keepCore = preferences.getBoolean("jarvis_core_wanted", false);
            boolean keepVoice = preferences.getBoolean("voice_enabled", true);
            preferences.edit().clear().apply();
            // Restore identity + wake toggles; conversational memory wiped.
            SharedPreferences.Editor ed = preferences.edit();
            if (keepSession != null && !keepSession.isEmpty()) {
                ed.putString("assistant_session_id", keepSession);
                sessionId = keepSession;
            }
            ed.putBoolean(WakePhrase.PREF_WAKE_WORD_ENABLED, keepWakeWord);
            ed.putBoolean("jarvis_core_wanted", keepCore);
            ed.putBoolean("voice_enabled", keepVoice);
            ed.apply();
            wakeWordEnabled = keepWakeWord;
            MemoryApiClient.resetServer(keepBackend, keepSession);
            transcript.setText("J.A.R.V.I.S. // PRIVACY\n\nMemoria locale e server (best-effort) cancellate.");
            speakMetal("Memoria cancellata.");
            return;
        }
        if (lower.contains("cosa ho oggi") || lower.contains("appuntamenti di oggi")) {
            String saved = preferences.getString("appointments_today", "");
            speakMetal(saved.isEmpty() ? "Non hai appuntamenti locali salvati per oggi." : "Oggi hai: " + saved);
            return;
        }
        ReminderParser.Result r = ReminderParser.parse(message, System.currentTimeMillis());
        if (r != null && (lower.contains("ricord") || lower.contains("chiama"))) {
            android.app.AlarmManager a = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            Intent i = new Intent(this, ReminderReceiver.class).putExtra("text", r.text);
            android.app.PendingIntent p = android.app.PendingIntent.getBroadcast(this, (int) (r.at % 100000), i, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            try {
                if (a != null) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        if (a.canScheduleExactAlarms()) {
                            a.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, r.at, p);
                        } else {
                            a.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, r.at, p);
                        }
                    } else {
                        a.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, r.at, p);
                    }
                }
            } catch (SecurityException se) {
                if (a != null) a.set(android.app.AlarmManager.RTC_WAKEUP, r.at, p);
            }
            String when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, Locale.ITALY).format(new java.util.Date(r.at));
            if (java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, Locale.ITALY).format(new java.util.Date(r.at)).equals(java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, Locale.ITALY).format(new java.util.Date()))) {
                String old = preferences.getString("appointments_today", "");
                preferences.edit().putString("appointments_today", (old.isEmpty() ? r.text : old + "; " + r.text)).apply();
            }
            transcript.setText("J.A.R.V.I.S. // PROMEMORIA SALVATO\n\n" + when + "\n" + r.text);
            speakMetal("Promemoria salvato per " + when + ". Ti chiederò conferma prima di chiamarti.");
            return;
        }
        handleUserCommand(message);
    }

    private class ReactorView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.RectF arc = new android.graphics.RectF();
        float phase = 0f;
        boolean active = false;
        Handler ticker = new Handler();
        Runnable pulse;

        ReactorView(Context c) {
            super(c);
            // Lascia al renderer nativo del dispositivo la scelta del layer:
            // forzare software o hardware aumenta la memoria grafica su alcuni
            // telefoni e può provocare la chiusura dell'app.
            setLayerType(View.LAYER_TYPE_NONE, null);
            p.setTypeface(Typeface.MONOSPACE);
            setOnClickListener(v -> {
                stopVoice();
                listen();
            });
            pulse = () -> {
                phase += active ? 0.13f : 0.045f;
                invalidate();
                ticker.postDelayed(pulse, 50);
            };
            ticker.post(pulse);
        }

        void startPulse() {
            ticker.removeCallbacks(pulse);
            ticker.post(pulse);
        }

        void stopPulse() {
            ticker.removeCallbacks(pulse);
        }

        void setActive(boolean value) {
            active = value;
        }

        private void stroke(Canvas c, int color, float width) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp((int) width));
            p.setColor(color);
            p.clearShadowLayer();
        }

        private void ring(Canvas c, float cx, float cy, float r, float start, float sweep, float width, int color) {
            arc.set(cx - r, cy - r, cx + r, cy + r);
            stroke(c, color, width);
            c.drawArc(arc, start, sweep, false, p);
        }

        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float size = Math.min(getWidth(), getHeight());
            float base = size * .255f;
            float breath = (float) Math.sin(phase * 2.0f) * (active ? dp(4) : dp(2));
            float glow = base + breath;
            c.drawColor(Color.rgb(3, 10, 19));
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(active ? 24 : 14, 0, 220, 255));
            c.drawCircle(cx, cy, glow + dp(18), p);
            stroke(c, Color.argb(130, 0, 130, 180), 1);
            c.drawCircle(cx, cy, glow + dp(45), p);
            stroke(c, Color.argb(80, 0, 210, 255), 1);
            c.drawCircle(cx, cy, glow + dp(36), p);
            ring(c, cx, cy, glow + dp(25), phase * 28f, 72, 5, Color.rgb(0, 235, 255));
            ring(c, cx, cy, glow + dp(25), phase * 28f + 112, 48, 5, Color.rgb(0, 180, 235));
            ring(c, cx, cy, glow + dp(25), phase * 28f + 205, 92, 5, Color.rgb(0, 235, 255));
            ring(c, cx, cy, glow + dp(25), phase * 28f + 326, 36, 5, Color.rgb(0, 180, 235));
            ring(c, cx, cy, glow + dp(11), -phase * 42f, 54, 2, Color.rgb(75, 245, 255));
            ring(c, cx, cy, glow + dp(11), -phase * 42f + 90, 42, 2, Color.rgb(0, 130, 220));
            ring(c, cx, cy, glow + dp(11), -phase * 42f + 190, 68, 2, Color.rgb(75, 245, 255));
            stroke(c, Color.argb(150, 0, 220, 255), 1);
            for (int i = 0; i < 24; i++) {
                double a = phase * .45 + i * Math.PI * 2 / 24;
                float inner = glow + dp(40), outer = inner + ((i % 3 == 0) ? dp(9) : dp(4));
                float x1 = cx + (float) Math.cos(a) * inner, y1 = cy + (float) Math.sin(a) * inner, x2 = cx + (float) Math.cos(a) * outer, y2 = cy + (float) Math.sin(a) * outer;
                c.drawLine(x1, y1, x2, y2, p);
            }
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 8; i++) {
                double a = -phase * .8 + i * Math.PI / 4;
                float pr = glow + dp(34) + (i % 2) * dp(9);
                float x = cx + (float) Math.cos(a) * pr, y = cy + (float) Math.sin(a) * pr;
                p.setColor(Color.argb(180, 0, 220, 255));
                c.drawCircle(x, y, dp(i % 3 == 0 ? 3 : 2), p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(active ? 95 : 70, 0, 135, 210));
            c.drawCircle(cx, cy, glow * .62f, p);
            p.setColor(Color.argb(180, 0, 220, 255));
            c.drawCircle(cx, cy, glow * .44f, p);
            p.setColor(Color.rgb(220, 255, 255));
            c.drawCircle(cx, cy, glow * .19f, p);
            stroke(c, Color.rgb(0, 80, 125), 1);
            c.drawCircle(cx, cy, glow * .72f, p);
            ring(c, cx, cy, glow * .73f, phase * 85f, 38, 2, Color.rgb(170, 255, 255));
            float scan = (float) ((phase * 34) % 360);
            ring(c, cx, cy, glow + dp(49), scan, 18, 1, Color.argb(160, 100, 255, 255));
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(110, 245, 255));
            p.setTextSize(dp(9));
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(active ? "CORE // ASCOLTO" : "CORE // STANDBY", cx, cy + glow + dp(62), p);
        }
    }

    private void listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10);
            status.setText("PRIVACY // AUTORIZZA IL MICROFONO");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.setText("VOCE NON DISPONIBILE");
            return;
        }
        notifyWakeSessionBusy();
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            recognizer.destroy();
        }
        speechListening = true;
        speechResultReceived = false;
        speechRestartAttempts = 0;
        beginVoiceSessionKeepAwake();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle b) {
                status.setText("SESSIONE // MICROFONO ATTIVO, PARLA");
                if (reactor != null) reactor.setActive(true);
            }
            public void onResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                speechResultReceived = true;
                speechListening = false;
                speechRestartAttempts = 0;
                if (r != null && !r.isEmpty()) {
                    String spoken = r.get(0) == null ? "" : r.get(0).trim();
                    if (isEchoSuppressed() || looksLikeOwnTts(spoken)) {
                        if (reactor != null) reactor.setActive(false);
                        status.setText(standbyStatusText());
                        endVoiceSessionKeepAwake();
                        return;
                    }
                    String command = WakePhrase.stripWake(spoken);
                    if (command.isEmpty() && WakePhrase.containsWake(spoken)) {
                        status.setText("SESSIONE // CONTINUA A PARLARE");
                        speechResultReceived = false;
                        speechRestartAttempts = 0;
                        speechListening = true;
                        speechHandler.postDelayed(() -> restartSpeechListening(), 250L);
                        return;
                    }
                    input.setText(command.isEmpty() ? spoken : command);
                    handleUserCommand(command.isEmpty() ? spoken : command);
                } else {
                    if (reactor != null) reactor.setActive(false);
                    status.setText(standbyStatusText());
                    endVoiceSessionKeepAwake();
                }
            }
            public void onError(int e) {
                speechListening = false;
                if (reactor != null) reactor.setActive(false);
                // Android interrompe spesso SpeechRecognizer dopo timeout, rete lenta
                // o cambio audio. Riavvia automaticamente solo per la sessione attiva.
                if (!destroyed && speechRestartAttempts < 3 && !speechResultReceived) {
                    speechRestartAttempts++;
                    status.setText("MICROFONO // RIPRISTINO " + speechRestartAttempts + "/3");
                    speechHandler.postDelayed(() -> restartSpeechListening(), 450L * speechRestartAttempts);
                } else {
                    status.setText(standbyStatusText());
                    endVoiceSessionKeepAwake();
                }
            }
            public void onBeginningOfSpeech() {}
            public void onEndOfSpeech() {
                if (!speechResultReceived && !destroyed && speechRestartAttempts < 3) {
                    speechHandler.postDelayed(() -> restartSpeechListening(), 300L);
                }
            }
            public void onRmsChanged(float v) {}
            public void onBufferReceived(byte[] b) {}
            public void onPartialResults(Bundle b) {}
            public void onEvent(int a, Bundle b) {}
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        recognizer.startListening(i);
    }

    private void restartSpeechListening() {
        if (destroyed || !speechListening && speechResultReceived) return;
        if (isEchoSuppressed()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (recognizer != null) recognizer.cancel();
            speechListening = true;
            speechResultReceived = false;
            recognizer.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3));
        } catch (Exception e) {
            speechListening = false;
            status.setText(standbyStatusText());
            endVoiceSessionKeepAwake();
        }
    }

    private boolean containsWakePhrase(String text) {
        return WakePhrase.containsWake(text);
    }

    private String stripWakePhrase(String text) {
        return WakePhrase.stripWake(text);
    }

    private void probeBackendHealth() {
        if (destroyed || !isOnline()) return;
        networkExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(getBackendUrl() + "/health/ready").openConnection();
                connection.setConnectTimeout(3500);
                connection.setReadTimeout(3500);
                connection.setRequestMethod("GET");
                int code = connection.getResponseCode();
                if (code == 200) {
                    runOnUiThread(() -> {
                        if (!destroyed && !requestInFlight && status != null) status.setText("ONLINE // CORE PRONTO");
                    });
                } else {
                    runOnUiThread(() -> {
                        if (!destroyed && status != null) status.setText("CORE // FALLBACK LOCALE");
                    });
                }
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!destroyed && status != null) status.setText("CORE // OFFLINE, FALLBACK ATTIVO");
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void sendMessage(String message) {
        if (message == null || message.trim().isEmpty() || requestInFlight) return;
        if (!isOnline()) {
            status.setText("OFFLINE // VERIFICA RETE");
            transcript.setText("Nessuna connessione disponibile. Controlla Wi‑Fi o dati mobili e riprova.");
            return;
        }
        requestInFlight = true;
        status.setText("ELABORAZIONE CLOUD...");
        if (reactor != null) reactor.setActive(true);
        transcript.setText("Tu: " + message + "\n\nJARVIS: ...");

        networkExecutor.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(getBackendUrl() + "/chat").openConnection();
                c.setRequestMethod("POST");
                // Timeout brevi: l'interfaccia non deve restare appesa mentre il
                // backend cambia rete o si riattiva dopo un periodo inattivo.
                c.setConnectTimeout(7000);
                c.setReadTimeout(22000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");

                String selectedModel = preferences.getString("ai_model", "deepseek-v4.1-flash");
                String customApiKey = preferences.getString("custom_api_key", "");
                String contextualMessage = (userName.trim().isEmpty() ? "" : "L'utente con cui stai parlando si chiama " + userName + ". Rivolgiti a lui usando il nome quando naturale.\n")
                        + (memory.trim().isEmpty() ? "" : "Contesto recente della conversazione:\n" + memory + "\n")
                        + "Personalità richiesta: " + readPersonality() + "\nRichiesta attuale: " + message;
                String context = "utente=" + userName + ";personalità=" + readPersonality() + ";modello=" + selectedModel + ";batteria=" + readBattery() + ";rete=" + (isOnline() ? "online" : "offline");
                String body = "{\"message\":\"" + jsonEscape(contextualMessage) + "\""
                        + ",\"client\":\"android\""
                        + ",\"session_id\":\"" + jsonEscape(sessionId) + "\""
                        + ",\"model\":\"" + jsonEscape(selectedModel) + "\""
                        + ",\"personality\":\"" + jsonEscape(readPersonality()) + "\""
                        + (customApiKey.isEmpty() ? "" : ",\"api_key\":\"" + jsonEscape(customApiKey) + "\"")
                        + ",\"context\":{\"device\":\"android\",\"model\":\"" + jsonEscape(selectedModel) + "\",\"state\":\"" + jsonEscape(context) + "\"}}";

                try (OutputStream o = c.getOutputStream()) {
                    o.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int code = c.getResponseCode();
                InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String raw = read(stream);

                JSONObject json = new JSONObject(raw);
                String answer = json.optString("reply", "");
                if (code >= 400 || answer.trim().isEmpty()) throw new IOException("Server HTTP " + code);
                String action = json.optString("action", "");
                // Il cloud può restituire un errore del provider dentro una
                // risposta HTTP 200 (ad esempio Groq 429). Non mostrarlo
                // all'utente: JARVIS deve rispondere comunque localmente.
                String answerLower = answer.toLowerCase(Locale.ROOT);
                if (answerLower.contains("rate limit") || answerLower.contains("rate_limit")
                        || answerLower.contains("429") || answerLower.contains("anomal")) {
                    answer = localFallbackReply(message);
                    action = "chat";
                    json.remove("action_params");
                }
                String engineName = json.optString("engine", selectedModel);
                JSONObject actionParams = json.optJSONObject("action_params");
                String value = "";
                if (actionParams != null) {
                    if (actionParams.has("query")) value = actionParams.optString("query", "");
                    else if (actionParams.has("phone")) value = actionParams.optString("phone", "");
                    else if (actionParams.has("number")) value = actionParams.optString("number", "");
                    else if (actionParams.has("text")) value = actionParams.optString("text", "");
                    else if (actionParams.has("target")) value = actionParams.optString("target", "");
                    else if (actionParams.has("state")) value = actionParams.optString("state", "");
                    else if (actionParams.has("time")) value = actionParams.optString("time", "");
                    else if (actionParams.has("seconds")) value = actionParams.optString("seconds", "");
                    else if (actionParams.has("length")) value = actionParams.optString("length", "");
                    else if (actionParams.has("location")) value = actionParams.optString("location", "");
                }
                if (value.isEmpty()) value = json.optString("query", "");

                String updated = (memory + "\nTu: " + message + "\nJARVIS: " + answer).trim();
                if (updated.length() > 6000) updated = updated.substring(updated.length() - 6000);
                memory = updated;
                preferences.edit().putString("conversation_memory", memory).apply();
                MemoryApiClient.push(MainActivity.this, getBackendUrl(), sessionId, memory);

                final String resolvedValue = value;
                final String resolvedEngine = engineName;
                final String resolvedAnswer = answer;
                final String resolvedAction = action;
                runOnUiThread(() -> {
                    requestInFlight = false;
                    status.setText("ONLINE • " + resolvedEngine.toUpperCase(Locale.ROOT));
                    if (reactor != null) reactor.setActive(false);
                    transcript.setText("Tu: " + message + "\n\nJARVIS: " + resolvedAnswer);
                    // Per le chiamate la risposta vocale deve terminare prima di
                    // consegnare l'audio al dialer: altrimenti Android interrompe
                    // il TTS appena parte ACTION_CALL.
                    boolean callAction = "call".equalsIgnoreCase(resolvedAction) || "dial".equalsIgnoreCase(resolvedAction);
                    if (callAction) {
                        playCloudVoice(resolvedAnswer, () -> executeServerAction(resolvedAction, resolvedValue));
                    } else {
                        executeServerAction(resolvedAction, resolvedValue);
                        playCloudVoice(resolvedAnswer);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    requestInFlight = false;
                    if (reactor != null) reactor.setActive(false);
                    if (retryAttempts < 2 && !destroyed) {
                        retryAttempts++;
                        status.setText("RICONNESSIONE // TENTATIVO " + (retryAttempts + 1));
                        transcript.setText("JARVIS sta ristabilendo il collegamento…");
                        new Handler(Looper.getMainLooper()).postDelayed(() -> sendMessage(message), 650L * retryAttempts);
                    } else {
                        status.setText("PRONTO // CONNESSIONE TEMPORANEAMENTE ASSENTE");
                        transcript.setText("JARVIS: Non riesco a raggiungere il Core in questo momento. Riprovo automaticamente al prossimo comando.");
                        speakMetal("Connessione temporaneamente assente. Riprova tra poco, signore.");
                    }
                });
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) || nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
    }

    private String localFallbackReply(String message) {
        String q = message == null ? "" : message.toLowerCase(Locale.ROOT).trim();
        if (q.contains("come stai") || q.contains("tutto bene")) {
            return "Tutti i sistemi locali sono operativi, signore. Sono pronto e posso continuare a gestire i comandi dal telefono.";
        }
        if (q.contains("ciao") || q.contains("buongiorno") || q.contains("buonasera")) {
            return "Buongiorno, signore. Sono operativo e pronto ad aiutarla.";
        }
        if (q.contains("chi sei") || q.contains("come ti chiami")) {
            return "Sono JARVIS, il tuo assistente personale. Posso eseguire i comandi disponibili anche in modalità locale.";
        }
        return "Ho ricevuto la richiesta, signore. La modalità locale è attiva: posso gestire comandi del telefono, chiamate, battute e informazioni di sistema.";
    }

    private String readBattery() {
        android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
        int value = bm == null ? -1 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return value < 0 ? "N/D" : String.valueOf(value) + "%";
    }

    private String readPersonality() {
        return preferences == null ? "stark" : preferences.getString("personality", "stark");
    }

    private boolean handleLocalProfileCommand(String message) {
        String q = message.toLowerCase(Locale.ROOT);
        if (q.contains("modalità deepseek") || q.contains("modalita deepseek") || q.contains("passa a deepseek") || q.contains("attiva deepseek") || q.contains("usa deepseek") || q.contains("deepseek-v4.1-flash") || q.contains("deepseek v4") || q.contains("deepseek flash")) {
            preferences.edit().putString("personality", "deepseek").putString("ai_model", "deepseek-v4.1-flash").apply();
            String reply = "Modalità DeepSeek-V4.1-Flash attivata, signore. Architettura MoE da 552 miliardi di parametri con Engram e finestra di contesto da un milione di token pronta all'uso.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            status.setText("ONLINE • DEEPSEEK-V4.1-FLASH");
            speakMetal(reply);
            return true;
        }
        if (q.contains("modalità grok") || q.contains("modalita grok") || q.contains("passa a grok") || q.contains("attiva grok") || q.contains("usa grok")) {
            preferences.edit().putString("personality", "grok").putString("ai_model", "grok").apply();
            String reply = "Modalità Grok attivata, signore. Meno burocrazia, spirito libero, ironia e ricerca live sul web. Cosa vuoi sapere?";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            status.setText("ONLINE • GROK-2");
            speakMetal(reply);
            return true;
        }
        if (q.contains("modalità chatgpt") || q.contains("modalita chatgpt") || q.contains("passa a chatgpt") || q.contains("attiva chatgpt") || q.contains("usa chatgpt")) {
            preferences.edit().putString("personality", "chatgpt").putString("ai_model", "chatgpt").apply();
            String reply = "Modalità ChatGPT attivata, signore. Pronto per ragionamenti complessi, scrittura di codice e analisi dettagliate.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            status.setText("ONLINE • CHATGPT-4O");
            speakMetal(reply);
            return true;
        }
        if (q.contains("modalità gemini") || q.contains("modalita gemini") || q.contains("passa a gemini") || q.contains("attiva gemini") || q.contains("usa gemini")) {
            preferences.edit().putString("personality", "gemini").putString("ai_model", "gemini").apply();
            String reply = "Modalità Gemini attivata, signore. Intelligenza Google multimodale pronta con precisione scientifica e fattuale.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            status.setText("ONLINE • GEMINI-PRO");
            speakMetal(reply);
            return true;
        }
        if (q.contains("modalità qwen") || q.contains("modalita qwen") || q.contains("passa a qwen") || q.contains("attiva qwen") || q.contains("usa qwen") || q.contains("modalità coder")) {
            preferences.edit().putString("personality", "qwen").putString("ai_model", "qwen").apply();
            String reply = "Modalità Qwen attivata, signore. Motore logico-matematico, di coding e multilingue ad alte prestazioni pronto.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            status.setText("ONLINE • QWEN-3.8");
            speakMetal(reply);
            return true;
        }
        if (q.contains("modalità stark") || q.contains("modalita stark") || q.contains("passa a stark") || q.contains("torna a jarvis") || q.contains("torna a stark")) {
            preferences.edit().putString("personality", "stark").putString("ai_model", "stark").apply();
            String reply = "Modalità Stark attivata, signore. Sistemi Mark VII e protocollo primario ripristinati.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            status.setText("ONLINE • STARK-MARK-VII");
            speakMetal(reply);
            return true;
        }
        if (q.contains("modalità sarcastica") || q.contains("modalita sarcastica")) {
            preferences.edit().putString("personality", "sarcastica").apply();
            speakMetal("Personalità sarcastica attivata. Cercherò di non esagerare, signore.");
            return true;
        }
        if (q.contains("modalità elegante") || q.contains("modalita elegante")) {
            preferences.edit().putString("personality", "elegante").apply();
            speakMetal("Modalità elegante attivata, signore.");
            return true;
        }
        if (q.contains("chi ti ha creato") || q.contains("chi è il tuo creatore") || q.contains("chi ti ha fatto") || q.contains("chi ti ha programmato")) {
            String ans = "Sono stato creato da Jaki.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + ans);
            speakMetal(ans);
            return true;
        }
        if (q.startsWith("ricordati che ")) {
            String fact = message.substring(message.toLowerCase(Locale.ROOT).indexOf("ricordati che ") + 13).trim();
            memory = (memory + "\nFATTO: " + fact).trim();
            preferences.edit().putString("conversation_memory", memory).apply();
            MemoryApiClient.push(this, getBackendUrl(), sessionId, memory);
            speakMetal("Memorizzato, signore.");
            return true;
        }
        if (q.contains("cosa ti ricordi") || q.contains("cosa ricordi")) {
            speakMetal(memory.trim().isEmpty() ? "Non ho ancora memorie salvate, signore." : "Ricordo: " + memory.replace("\n", ". "));
            return true;
        }
        return false;
    }

    private void executeServerAction(String action, String value) {
        if (action == null || action.trim().isEmpty() || "chat".equals(action)) return;
        String mapped = action;
        String target = value == null ? "" : value;
        if ("map".equals(action)) mapped = "maps";
        if ("call".equals(action)) mapped = "call";
        if ("dial".equals(action)) mapped = "dial";
        if ("web_search".equals(action)) mapped = "search";
        if ("spotify".equals(action)) mapped = "spotify";
        if ("whatsapp".equals(action)) mapped = "whatsapp";
        if ("timer".equals(action)) mapped = "timer";
        if ("alarm".equals(action) || "sveglia".equals(action)) mapped = "alarm";
        if ("torch".equals(action) || "flashlight".equals(action)) mapped = "torch";
        if ("volume".equals(action)) mapped = "volume";
        if ("camera".equals(action) || "fotocamera".equals(action)) mapped = "camera";
        if ("open_app".equals(action)) mapped = "open_app";
        if ("google_meet".equals(action) || "meet".equals(action)) mapped = "google_meet";
        // Mantieni anche le chiamate riconosciute dal backend nel percorso
        // autonomo: il dialer semplice interrompe JARVIS e non può parlare.
        if ("call".equals(mapped) && !target.trim().isEmpty()) {
            String digits = target.replaceAll("[^0-9+]", "");
            ContactResolver.ContactInfo info = null;
            if (digits.length() >= 6) {
                info = new ContactResolver.ContactInfo(target, digits, 0);
            } else {
                info = ContactResolver.findContact(this, target);
            }
            if (info != null && info.number != null && !info.number.isEmpty()) {
                startAiAssistantOutboundCall(info, "");
                return;
            }
        }
        if ("battery".equals(action)) {
            updateDeviceStatus();
            String b = readBattery();
            String reply = "Il livello attuale della batteria è al " + b + ", signore.";
            transcript.setText((transcript.getText() + "\n\nJARVIS: " + reply).trim());
            speakMetal(reply);
            return;
        }
        if ("wakelock".equals(action)) {
            wakeLockUserEnabled = true;
            if (preferences != null) preferences.edit().putBoolean("wake_lock_enabled", true).apply();
            beginVoiceSessionKeepAwake();
            applyScreenWakePolicy();
            status.setText("WAKE LOCK // SCHERMO SVEGLIO");
            if (transcript != null) {
                transcript.setText((transcript.getText() + "\n\nJARVIS: Wake lock attivo. Lo schermo resterà acceso mentre usi J.A.R.V.I.S.").trim());
            }
            speakMetal("Wake lock attivo. Lo schermo resterà acceso, signore.");
            return;
        }
        boolean ok = AndroidActionRouter.execute(this, mapped, target);
        android.util.Log.d("MainActivity", "executeServerAction " + mapped + " target=" + target + " -> " + ok);
    }


    private void beginTtsPlayback(String text) {
        ttsPlaying = true;
        ttsCooldownUntilMs = 0L;
        if (text != null && !text.trim().isEmpty()) lastSpokenText = text.trim();
        notifyWakeSessionBusy();
        // Stop any leftover command recognizer so we don't hear ourselves.
        if (speechListening) {
            speechListening = false;
            try {
                if (recognizer != null) recognizer.cancel();
            } catch (Exception ignored) {}
        }
    }

    private void endTtsPlayback() {
        ttsPlaying = false;
        ttsCooldownUntilMs = System.currentTimeMillis() + ECHO_COOLDOWN_MS;
        // Resume wake only after short cooldown so trailing audio isn't picked up.
        speechHandler.postDelayed(() -> {
            if (!destroyed && !ttsPlaying && !speechListening) {
                notifyWakeSessionIdle();
            }
        }, ECHO_COOLDOWN_MS);
    }

    private boolean isEchoSuppressed() {
        return ttsPlaying || System.currentTimeMillis() < ttsCooldownUntilMs;
    }

    /** True if recognized text looks like our own TTS (anti-echo). */
    private boolean looksLikeOwnTts(String spoken) {
        if (spoken == null || spoken.trim().isEmpty() || lastSpokenText == null || lastSpokenText.isEmpty()) {
            return false;
        }
        String a = WakePhrase.normalize(spoken);
        String b = WakePhrase.normalize(lastSpokenText);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        if (b.contains(a) && a.length() >= 8) return true;
        if (a.contains(b) && b.length() >= 8) return true;
        // Prefix overlap for long answers partially captured
        int n = Math.min(24, Math.min(a.length(), b.length()));
        return n >= 12 && a.regionMatches(0, b, 0, n);
    }

    private void stopVoice() {
        try {
            if (speaker != null) speaker.stop();
        } catch (Exception ignored) {}
        try {
            if (voicePlayer != null) {
                voicePlayer.stop();
                voicePlayer.release();
                voicePlayer = null;
            }
        } catch (Exception ignored) {}
        ttsPlaying = false;
        ttsCooldownUntilMs = System.currentTimeMillis() + 400L;
        if (status != null) status.setText("VOCE // INTERROTTA");
        endVoiceSessionKeepAwake();
    }

    private boolean isVolumeLow() {
        android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) return false;
        int current = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        return current <= 1;
    }

    private void showTextOnly(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String cleanText = text.trim();
        lastSpokenText = cleanText;
        if (status != null) status.setText("TESTO // VOLUME BASSO");
        if (transcript != null) {
            String previous = transcript.getText() == null ? "" : transcript.getText().toString().trim();
            if (previous.contains(cleanText) || previous.endsWith(cleanText)) return;
            if (previous.contains("JARVIS: " + cleanText) || previous.contains("J.A.R.V.I.S.: " + cleanText)) return;
            transcript.setText((previous + "\n\nJARVIS: " + cleanText).trim());
        }
    }

    private boolean shouldUseEdgeTts() {
        if (!voiceEnabled || preferences == null) return false;
        if (!isOnline()) return false;
        String engine = preferences.getString("tts_engine", "edge-server");
        return !"native".equalsIgnoreCase(engine);
    }

    private void speakMetal(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (isVolumeLow()) {
            showTextOnly(text);
            return;
        }
        if (!voiceEnabled) return;
        lastSpokenText = text.trim();
        beginVoiceSessionKeepAwake();
        beginTtsPlayback(text);
        if (shouldUseEdgeTts()) {
            if (status != null) status.setText("VOCE // EDGE NEURAL TTS");
            playCloudVoice(text, () -> {
                endTtsPlayback();
                endVoiceSessionKeepAwake();
            });
            return;
        }
        speakNativeMetal(text);
    }

    private void speakNativeMetal(String text) {
        if (text == null || text.trim().isEmpty()) {
            endVoiceSessionKeepAwake();
            return;
        }
        if (!voiceEnabled || speaker == null) {
            endVoiceSessionKeepAwake();
            return;
        }
        lastSpokenText = text.trim();
        beginTtsPlayback(text);
        if (status != null) status.setText("VOCE // NATIVA");
        String[] parts = text.trim().split("(?<=[.!?])\\s+");
        for (int i = 0; i < parts.length; i++) {
            speaker.speak(parts[i].trim(), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "jarvis-natural-" + i);
        }
        long approxMs = Math.max(1400L, Math.min(20000L, text.trim().length() * 55L));
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            endTtsPlayback();
            endVoiceSessionKeepAwake();
        }, approxMs);
    }

    private void beginVoiceSessionKeepAwake() {
        sessionKeepAwake = true;
        applyScreenWakePolicy();
    }

    private void endVoiceSessionKeepAwake() {
        if (speechListening) return;
        sessionKeepAwake = false;
        applyScreenWakePolicy();
        if (!isEchoSuppressed()) {
            notifyWakeSessionIdle();
        }
    }

    private void applyScreenWakePolicy() {
        boolean keep = wakeLockUserEnabled || sessionKeepAwake;
        try {
            if (keep) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        } catch (Exception ignored) {}
        if (keep) acquirePowerWakeLock();
        else releasePowerWakeLock();
        try { updateDeviceStatus(); } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    private void acquirePowerWakeLock() {
        try {
            if (screenWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm == null) return;
                screenWakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                        "giarvis:ScreenWake");
                screenWakeLock.setReferenceCounted(false);
            }
            if (screenWakeLock != null && !screenWakeLock.isHeld()) {
                screenWakeLock.acquire(30 * 60 * 1000L);
            }
        } catch (Exception ignored) {}
    }

    private void releasePowerWakeLock() {
        try {
            if (screenWakeLock != null && screenWakeLock.isHeld()) screenWakeLock.release();
        } catch (Exception ignored) {}
    }

    private void showHelpDialog() {
        String helpHtml = "<b>VOCE</b><br>"
                + "• Tap-to-talk: tocca reattore o microfono (sempre disponibile).<br>"
                + "• Wake word always-on (opzionale): Impostazioni → <i>Wake Word Hey Jarvis</i>, poi CORE ON. Di' <i>Hey Jarvis</i> / <i>Jarvis</i> senza toccare. Usa SpeechRecognizer in un Foreground Service (non openWakeWord ancora).<br>"
                + "• HUD: <i>ASCOLTO WAKE</i> = in attesa della parola; <i>SESSIONE</i> = microfono comando attivo.<br>"
                + "• Con rete ONLINE la voce predefinita è Edge Neural TTS; offline usa TTS nativo.<br>"
                + "• <i>Wake Lock</i> schermo (impostazioni) è separato dal wake word mic.<br><br>"
                + "<b>📱 CONTROLLI HARDWARE</b><br>"
                + "• <i>'Accendi la torcia'</i> / <i>'Spegni torcia'</i><br>"
                + "• <i>'Alza il volume'</i> / <i>'Abbassa il volume'</i> / <i>'Volume al massimo'</i> / <i>'Metti muto'</i><br>"
                + "• <i>'Quanta batteria ho?'</i><br>"
                + "• <i>'Apri la fotocamera'</i> / <i>'Fai una foto'</i><br>"
                + "• <i>'Impostazioni Wi-Fi'</i> / <i>'Bluetooth'</i> / <i>'Accessibilità'</i><br><br>"
                + "<b>🚀 APRI QUALSIASI APP</b><br>"
                + "• <i>'Apri YouTube'</i>, <i>'Apri Calcolatrice'</i>, <i>'Apri Chrome'</i><br>"
                + "• <i>'Apri Telegram'</i>, <i>'Apri Gmail'</i>, <i>'Apri Galleria'</i> (e qualsiasi altra app)<br><br>"
                + "<b>⏰ SVEGLIE & TIMER</b><br>"
                + "• <i>'Timer di 5 minuti'</i> / <i>'Timer 30 secondi'</i><br>"
                + "• <i>'Sveglia alle 7 e mezza'</i> / <i>'Sveglia alle 8:15'</i><br>"
                + "• <i>'Che ore sono?'</i> / <i>'Che giorno è?'</i><br><br>"
                + "<b>🎵 MUSICA & MEDIA</b><br>"
                + "• <i>'Ascolta i Coldplay su Spotify'</i> / <i>'Metti un po' di musica'</i><br>"
                + "• Tasto <b>+</b> per generare e modificare immagini con l'IA<br><br>"
                + "<b>📞 CHIAMATE VOCALI & GOOGLE MEET</b><br>"
                + "• <i>'Chiamami'</i> / <i>'Fammi una chiamata'</i> (JARVIS ti chiama con squillo reale e voce bidirezionale)<br>"
                + "• <i>'Chiamami tra 5 minuti per [motivo]'</i><br>"
                + "• <i>'Chiama mamma'</i> / <i>'Chiama Marco'</i> (cerca e chiama i contatti in rubrica)<br>"
                + "• <i>'Chiama il [numero]'</i><br>"
                + "• <i>'Chiama su Google Meet'</i> / <i>'Videochiamata'</i><br>"
                + "• <i>'Apri WhatsApp'</i> / <i>'Messaggio WhatsApp a [numero]'</i><br>"
                + "• Notifiche WhatsApp lette automaticamente<br><br>"
                + "<b>🌐 WEB & INTELLIGENZA LIVE</b><br>"
                + "• <i>'Che tempo fa a Milano / Roma?'</i><br>"
                + "• <i>'Chi è [persona]?'</i> / <i>'Cos'è [argomento]?'</i> (Wikipedia live)<br>"
                + "• <i>'Cerca sul web le ultime notizie'</i> (DuckDuckGo live)<br>"
                + "• <i>'Calcola quanto fa 250 diviso 4'</i> / <i>'il 20% di 150'</i><br><br>"
                + "<b>⚙️ PERSONALITÀ & MEMORIA</b><br>"
                + "• <i>'Modalità Stark'</i> / <i>'Modalità Sarcastica'</i> / <i>'Modalità Elegante'</i><br>"
                + "• <i>'Ricordati che [fatto]'</i> / <i>'Cosa ti ricordi?'</i>";

        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(android.text.Html.fromHtml(helpHtml, android.text.Html.FROM_HTML_MODE_LEGACY));
        tv.setTextColor(Color.rgb(200, 240, 255));
        tv.setTextSize(13);
        tv.setLineSpacing(dp(3), 1.0f);
        tv.setPadding(dp(20), dp(14), dp(20), dp(14));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);

        new android.app.AlertDialog.Builder(this)
                .setTitle("J.A.R.V.I.S. // GUIDA PROTOCOLLI")
                .setView(sv)
                .setPositiveButton("RICEVUTO", null)
                .show();
    }

    private void handleUserCommand(String message) {
        String q = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (q.trim().isEmpty()) return;
        if (q.contains("smetti di ripetere") || q.contains("disattiva ripetizione") || q.contains("modalità normale") || q.contains("modalita normale")) {
            repeatMode = false;
            speakMetal("Modalità ripetizione disattivata, signore.");
            return;
        }
        if (q.contains("ripeti quello che dico") || q.contains("ripetizione attiva") || q.contains("fai il pappagallo")) {
            repeatMode = true;
            speakMetal("Modalità ripetizione attivata. Dica pure.");
            return;
        }
        if (repeatMode) {
            lastSpokenText = message;
            speakMetal(message);
            return;
        }
        if (handleLocalProfileCommand(message)) return;
        // Le richieste di umorismo restano locali e immediate: non dipendono
        // dal backend, quindi funzionano anche con rete lenta o assente.
        if (q.contains("battuta") || q.contains("battute")
                || q.contains("fammi ridere") || q.contains("fammi sorridere")
                || q.contains("barzelletta") || q.contains("barzellette")
                || q.contains("racconta una barzelletta")
                || q.contains("dimmi una cosa divertente")
                || q.contains("scherzo") || q.contains("umorismo")) {
            tellSillyJoke();
            return;
        }
        if (q.contains("ripeti")) {
            speakMetal(lastSpokenText.isEmpty() ? "Non ho ancora una risposta da ripetere." : lastSpokenText);
            return;
        }
        if (q.contains("silenz") || q.contains("voce off")) {
            voiceEnabled = false;
            stopVoice();
            status.setText("VOCE // DISATTIVATA");
            return;
        }
        if (q.contains("voce on") || q.contains("attiva voce")) {
            voiceEnabled = true;
            status.setText("VOCE // ATTIVA");
            return;
        }
        if (q.contains("cronologia")) {
            startActivity(new Intent(this, ChatHistoryActivity.class));
            return;
        }
        if (q.contains("wifi")) {
            boolean ok = AndroidActionRouter.execute(this, "wifi_settings", "");
            String reply = ok ? "Apro le impostazioni Wi‑Fi, signore." : "Impossibile aprire le impostazioni Wi‑Fi.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }
        if (q.contains("bluetooth")) {
            boolean ok = AndroidActionRouter.execute(this, "bluetooth_settings", "");
            String reply = ok ? "Apro le impostazioni Bluetooth, signore." : "Impossibile aprire le impostazioni Bluetooth.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }
        if (q.contains("accessibilità") || q.contains("accessibilita")) {
            boolean ok = AndroidActionRouter.execute(this, "accessibility_settings", "");
            String reply = ok ? "Apro le impostazioni di accessibilità, signore." : "Impossibile aprire le impostazioni di accessibilità.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }
        if (q.contains("impostazioni notifiche")) {
            boolean ok = AndroidActionRouter.execute(this, "notification_settings", "");
            String reply = ok ? "Apro le impostazioni delle notifiche, signore." : "Impossibile aprire le impostazioni delle notifiche.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }
        if (q.contains("impostazion") || q.contains("settings")) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        if (q.contains("notific") && q.contains("whatsapp")) {
            String answer = WhatsAppNotificationService.getSummary(this);
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + answer);
            speakMetal(answer);
            return;
        }
        if (q.contains("torcia")) {
            boolean turnOn = !q.contains("spegni") && !q.contains("disattiva") && !q.contains("off");
            boolean ok = AndroidActionRouter.execute(this, "torch", turnOn ? "on" : "off");
            String reply = ok ? (turnOn ? "Torcia attivata, signore." : "Torcia disattivata, signore.") : "Impossibile controllare la torcia.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }
        if (q.contains("timer")) {
            boolean ok = AndroidActionRouter.execute(this, "timer", message);
            String reply = ok ? "Timer impostato, signore." : "Impossibile impostare il timer sul dispositivo.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }
        if (q.contains("sveglia")) {
            boolean ok = AndroidActionRouter.execute(this, "alarm", message);
            String reply = ok ? "Sveglia programmata, signore." : "Impossibile programmare la sveglia sul dispositivo.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }
        if (q.contains("spotify") || q.contains("musica") || q.contains("canzone")) {
            String query = message.replaceAll("(?i)\\b(apri|metti|su|ascolta|fammi ascoltare)\\b|spotify", "").trim();
            boolean ok = AndroidActionRouter.execute(this, "spotify", query);
            String reply = ok ? "Apro Spotify per riprodurre " + (query.isEmpty() ? "la tua musica" : query) + "." : "Impossibile aprire Spotify.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }
        if (q.startsWith("apri whatsapp") || q.equals("whatsapp")) {
            boolean ok = AndroidActionRouter.execute(this, "whatsapp", "");
            String reply = ok ? "Apro WhatsApp, signore." : "Impossibile aprire WhatsApp sul dispositivo.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }

        if (q.equals("chiamami") || q.equals("chiamami adesso") || q.equals("fammi una chiamata") || q.equals("chiama me") || q.contains("fai squillare il telefono") || q.startsWith("chiamami ")) {
            if (q.contains("tra ") || q.contains("minut") || q.contains("ore") || q.contains("second")) {
                scheduleReminderCall(message);
                return;
            }
            String reply = "Avvio immediatamente la chiamata verso il tuo dispositivo, signore.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            new Handler().postDelayed(() -> {
                Intent callIntent = new Intent(this, ReminderActivity.class);
                callIntent.putExtra("text", "Chiamata vocale diretta richiesta da " + userName + ".");
                callIntent.putExtra("is_direct_call", true);
                callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(callIntent);
            }, 800);
            return;
        }

        if (q.contains("meet") || q.contains("videochiamata")) {
            String target = message.replaceAll("(?i)\\b(chiama|videochiamata|chiamata|su|con|a|il|la|di|meet|google meet)\\b", "").trim();
            boolean ok = AndroidActionRouter.execute(this, "google_meet", target);
            String reply = ok ? "Avvio la chiamata su Google Meet" + (target.isEmpty() ? "" : " per " + target) + ", signore." : "Impossibile avviare Google Meet.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }

        if (q.startsWith("chiama ") || q.startsWith("telefona ") || q.startsWith("telefona a ") || q.startsWith("fai una chiamata a ") || q.matches(".*\\b(chiama|componi|telefona)\\b.*[0-9+].*")) {
            String target = message.replaceFirst("(?i)(chiama|telefona a|telefona|fai una chiamata a|componi)\\s*", "").trim();
            String messageForContact = "";
            if (target.contains(" e digli che ")) {
                String[] parts = target.split("(?i)\\s+e digli che\\s+", 2);
                target = parts[0].trim();
                messageForContact = parts[1].trim();
            } else if (target.contains(" e digli ")) {
                String[] parts = target.split("(?i)\\s+e digli\\s+", 2);
                target = parts[0].trim();
                messageForContact = parts[1].trim();
            } else if (target.contains(" e chiedigli ")) {
                String[] parts = target.split("(?i)\\s+e chiedigli\\s+", 2);
                target = parts[0].trim();
                messageForContact = "ti chiede: " + parts[1].trim();
            } else if (target.contains(" per dirgli ")) {
                String[] parts = target.split("(?i)\\s+per dirgli\\s+", 2);
                target = parts[0].trim();
                messageForContact = parts[1].trim();
            }

            if (target.equalsIgnoreCase("me") || target.equalsIgnoreCase("il mio numero")) {
                String savedPhone = preferences.getString("reminder_phone", "+393451039992");
                ContactResolver.ContactInfo ownNumber = new ContactResolver.ContactInfo("il tuo numero", savedPhone, 0);
                startAiAssistantOutboundCall(ownNumber, messageForContact);
                return;
            }

            // Se target è un numero di telefono esplicito (es. "345 1234567")
            String digits = target.replaceAll("[^0-9+]", "");
            if (digits.length() >= 6) {
                ContactResolver.ContactInfo directInfo = new ContactResolver.ContactInfo(digits, digits, 0);
                startAiAssistantOutboundCall(directInfo, messageForContact);
                return;
            }

            ContactResolver.ContactInfo info = ContactResolver.findContact(this, target);
            if (info != null && info.number != null && !info.number.isEmpty()) {
                startAiAssistantOutboundCall(info, messageForContact);
                return;
            } else {
                String reply = "Non ho trovato nessun contatto corrispondente a '" + target + "' nella tua rubrica telefonica, signore. Vuoi comporre un numero specifico o provare con Google Meet?";
                transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
                speakMetal(reply);
                return;
            }
        }
        if (q.contains("batteria") || q.contains("carica") || q.contains("autonomia")) {
            String b = readBattery();
            String reply = "Il livello attuale della batteria è al " + b + ", signore.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }
        if (q.contains("alza il volume") || q.contains("volume su") || q.contains("più volume") || q.contains("piu volume")) {
            boolean ok = AndroidActionRouter.execute(this, "volume", "alza");
            String reply = ok ? "Volume multimediale alzato, signore." : "Impossibile regolare il volume.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }
        if (q.contains("abbassa il volume") || q.contains("volume giù") || q.contains("volume giu") || q.contains("meno volume")) {
            boolean ok = AndroidActionRouter.execute(this, "volume", "abbassa");
            String reply = ok ? "Volume multimediale abbassato, signore." : "Impossibile regolare il volume.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }
        if (q.contains("volume al massimo") || q.contains("massimo volume") || q.contains("volume cento") || q.contains("volume 100")) {
            boolean ok = AndroidActionRouter.execute(this, "volume", "max");
            String reply = ok ? "Volume impostato al massimo, signore." : "Impossibile regolare il volume.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }
        if (q.contains("metti muto") || q.contains("silenzia volume") || q.contains("volume a zero") || q.equals("muto")) {
            boolean ok = AndroidActionRouter.execute(this, "volume", "muto");
            String reply = ok ? "Volume multimediale azzerato, signore." : "Impossibile azzerare il volume.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }
        if (q.contains("fotocamera") || q.contains("fai una foto") || q.contains("scatta una foto") || q.contains("scatta foto") || q.contains("apri camera")) {
            boolean ok = AndroidActionRouter.execute(this, "camera", "");
            String reply = ok ? "Apro la fotocamera, signore." : "Impossibile avviare la fotocamera.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }
        if (q.contains("cosa puoi fare") || q.contains("cosa sai fare") || q.contains("quali sono i comandi") || q.contains("elenco comandi") || q.contains("guida comandi") || q.equals("aiuto") || q.equals("help") || q.equals("comandi")) {
            showHelpDialog();
            String reply = "Ecco il catalogo completo dei comandi operativi, signore.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }
        if (q.startsWith("apri ") && !q.contains("whatsapp") && !q.contains("spotify") && !q.contains("impostazion") && !q.contains("wifi") && !q.contains("bluetooth") && !q.contains("fotocamera") && !q.contains("camera")) {
            String appTarget = message.replaceFirst("(?i)apri\\s*", "").trim();
            boolean ok = AndroidActionRouter.execute(this, "open_app", appTarget);
            String reply = ok ? "Apro " + appTarget + ", signore." : "Non ho trovato l'applicazione '" + appTarget + "' sul dispositivo.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }
        if (q.startsWith("cerca ") || q.startsWith("cerca su google ")) {
            String term = message.replaceFirst("(?i)cerca( su google)?\\s*", "").trim();
            boolean ok = AndroidActionRouter.execute(this, "search", term);
            String reply = ok ? "Cerco " + term + " sul web, signore." : "Impossibile avviare la ricerca.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
            speakMetal(reply);
            return;
        }
        if (!isOnline()) {
            String offline = "Sono offline. Posso comunque ripetere l'ultima risposta o aprire le impostazioni vocali.";
            transcript.setText("Tu: " + message + "\n\nJARVIS: " + offline);
            speakMetal(offline);
            return;
        }
        retryAttempts = 0;
        sendMessage(message);
        new Handler().postDelayed(() -> {
            if (requestInFlight && retryAttempts < 2 && !destroyed) {
                requestInFlight = false;
                retryAttempts++;
                status.setText("RETE LENTA // RITENTO...");
                sendMessage(message);
            }
        }, 46000);
    }

    private void scheduleReminderCall(String message) {
        int seconds = 300;
        String mLower = message.toLowerCase(Locale.ROOT);
        Matcher matcher = Pattern.compile("(\\d+)").matcher(message);
        if (matcher.find()) {
            try {
                int val = Integer.parseInt(matcher.group(1));
                if (mLower.contains("ora") || mLower.contains("ore")) seconds = val * 3600;
                else if (mLower.contains("second")) seconds = val;
                else seconds = val * 60;
            } catch (Exception ignored) {}
        } else if (mLower.contains("un minuto")) {
            seconds = 60;
        } else if (mLower.contains("due minuti")) {
            seconds = 120;
        } else if (mLower.contains("mezz'ora") || mLower.contains("mezzora")) {
            seconds = 1800;
        }

        String reason = message.replaceFirst("(?i).*?\\b(per|di|che)\\b", "").trim();
        if (reason.isEmpty() || reason.equals(message)) reason = "Promemoria richiesto da te.";

        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        Intent intent = new Intent(this, ReminderReceiver.class).putExtra("text", reason);
        PendingIntent pi = PendingIntent.getBroadcast(this, (int) System.currentTimeMillis(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (am != null) {
            long triggerAt = System.currentTimeMillis() + (seconds * 1000L);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        }

        int mins = Math.max(1, seconds / 60);
        String reply = "Perfetto signore, ti chiamerò tra " + (seconds < 60 ? seconds + " secondi" : mins + " minuti") + " per: " + reason + ".";
        transcript.setText("Tu: " + message + "\n\nJARVIS: " + reply);
        speakMetal(reply);
    }

    private CallAssistantManager callAssistantManager;
    private boolean repeatMode = false;
    private final String[] sillyJokes = {
            "Perché il computer va dal medico? Perché ha un virus.",
            "Cosa dice una lampadina quando ha un'idea? Mi si è accesa una cosa.",
            "Perché il libro di matematica è triste? Ha troppi problemi.",
            "Che cosa dice una presa all'altra? Restiamo in contatto.",
            "Il Wi-Fi e il Bluetooth hanno litigato: non erano più connessi.",
            "Perché il pomodoro arrossisce? Ha visto l'insalata nuda.",
            "Qual è il colmo per un calendario? Avere i giorni contati.",
            "Ho fatto una battuta sul caricabatterie, ma non ha fatto presa.",
            "Perché il telefono è andato in vacanza? Doveva staccare la spina.",
            "Il GPS ha perso la strada, ma sostiene fosse una deviazione strategica."
    };

    private void tellSillyJoke() {
        String joke = sillyJokes[new Random().nextInt(sillyJokes.length)];
        lastSpokenText = joke;
        transcript.setText("JARVIS: " + joke);
        speakMetal(joke);
    }

    private void startAiAssistantOutboundCall(ContactResolver.ContactInfo info, String messageForContact) {
        String reply = "Chiamo subito " + info.name + " al suo numero (" + info.number + "). Quando risponderà, parlerò io direttamente e risponderò a ciò che dice per tuo conto, signore.";
        transcript.setText((transcript.getText() == null ? "" : transcript.getText().toString() + "\n\n") + "JARVIS: " + reply);
        speakMetal(reply);

        if (callAssistantManager != null) {
            callAssistantManager.cleanup();
        }

        callAssistantManager = new CallAssistantManager(this, speaker, new CallAssistantManager.CallAssistantListener() {
            @Override
            public void onCallStatus(String s) {
                runOnUiThread(() -> {
                    if (status != null) status.setText(s);
                });
            }

            @Override
            public void onTranscript(String spk, String txt) {
                runOnUiThread(() -> {
                    if (transcript != null) {
                        String cur = transcript.getText() == null ? "" : transcript.getText().toString();
                        transcript.setText(cur + "\n[" + spk + "]: " + txt);
                    }
                });
            }

            @Override
            public void onCallFinished(String summary) {
                runOnUiThread(() -> {
                    if (status != null) status.setText("ONLINE // CHIAMATA COMPLETATA");
                    String report = "Signore, ho completato la telefonata con " + info.name + ". Ho memorizzato la trascrizione nel registro.";
                    speakMetal(report);
                    // Dopo ACTION_CALL Android porta avanti l'app Telefono.
                    // Riporta JARVIS in primo piano quando la linea è terminata.
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        try {
                            Intent reopen = new Intent(MainActivity.this, MainActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            startActivity(reopen);
                            if (status != null) status.setText("ONLINE // PRONTO");
                            if (reactor != null) reactor.setActive(false);
                        } catch (Exception ignored) {}
                    }, 700L);
                });
            }
        });

        new Handler().postDelayed(() -> {
            callAssistantManager.startAutonomousCall(info.name, info.number, messageForContact);
        }, 1200);
    }

    private void playCloudVoice(String text) {
        playCloudVoice(text, null);
    }

    private void playCloudVoice(String text, Runnable afterVoice) {
        if (destroyed || isFinishing()) return;
        if (isVolumeLow()) {
            showTextOnly(text);
            if (afterVoice != null) new Handler(Looper.getMainLooper()).postDelayed(afterVoice, 900);
            else endVoiceSessionKeepAwake();
            return;
        }
        if (!voiceEnabled) {
            if (afterVoice != null) afterVoice.run();
            else endVoiceSessionKeepAwake();
            return;
        }
        lastSpokenText = text == null ? "" : text.trim();
        beginVoiceSessionKeepAwake();
        try {
            if (voicePlayer != null) {
                try {
                    voicePlayer.stop();
                } catch (Exception ignored) {}
                voicePlayer.release();
                voicePlayer = null;
            }
            String edgeVoice = preferences != null ? preferences.getString("edge_tts_voice", "") : "";
            String edgeRate = preferences != null ? preferences.getString("edge_tts_rate", "") : "";
            String edgePitch = preferences != null ? preferences.getString("edge_tts_pitch", "+0Hz") : "+0Hz";
            if (edgeRate == null || edgeRate.trim().isEmpty()) {
                int speed = preferences != null ? preferences.getInt("voice_speed", 50) : 50;
                int pct = (int) Math.round(-15 + (speed / 100.0) * 30);
                edgeRate = (pct >= 0 ? "+" : "") + pct + "%";
            }
            if (edgePitch == null || edgePitch.trim().isEmpty()) edgePitch = "+0Hz";
            if (edgeVoice == null) edgeVoice = "";
            String u = getBackendUrl() + "/tts/audio?voice=" + URLEncoder.encode(edgeVoice, "UTF-8")
                    + "&rate=" + URLEncoder.encode(edgeRate, "UTF-8")
                    + "&pitch=" + URLEncoder.encode(edgePitch, "UTF-8")
                    + "&text=" + URLEncoder.encode(text, "UTF-8");
            MediaPlayer player = new MediaPlayer();
            voicePlayer = player;
            player.setDataSource(u);
            player.setOnPreparedListener(p -> {
                if (destroyed || p != voicePlayer) {
                    try {
                        p.release();
                    } catch (Exception ignored) {}
                    return;
                }
                try {
                    beginTtsPlayback(text);
                    p.start();
                } catch (Exception ignored) {
                    speakNativeMetal(text);
                    if (afterVoice != null) new Handler(Looper.getMainLooper()).postDelayed(afterVoice, 1200);
                }
            });
            player.setOnCompletionListener(p -> {
                endTtsPlayback();
                if (afterVoice != null) afterVoice.run();
                endVoiceSessionKeepAwake();
            });
            player.setOnErrorListener((p, w, e) -> {
                if (!destroyed) {
                    // speakNativeMetal schedules endVoiceSessionKeepAwake; also run afterVoice if any
                    if (afterVoice != null) {
                        speakNativeMetal(text);
                        new Handler(Looper.getMainLooper()).postDelayed(afterVoice, 1200);
                    } else {
                        speakNativeMetal(text);
                    }
                } else if (afterVoice != null) {
                    new Handler(Looper.getMainLooper()).postDelayed(afterVoice, 1200);
                }
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            if (afterVoice != null) {
                speakNativeMetal(text);
                new Handler(Looper.getMainLooper()).postDelayed(afterVoice, 1200);
            } else {
                speakNativeMetal(text);
            }
        }
    }

    private static String read(InputStream s) throws IOException {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(s, StandardCharsets.UTF_8))) {
            String x;
            while ((x = r.readLine()) != null) b.append(x);
        }
        return b.toString();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        sessionKeepAwake = false;
        try { getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Exception ignored) {}
        releasePowerWakeLock();
        speechHandler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(whatsappReceiver);
        } catch (Exception ignored) {}
        try {
            unregisterReceiver(wakeWordReceiver);
        } catch (Exception ignored) {}
        try {
            notifyWakeSessionIdle();
        } catch (Exception ignored) {}
        try {
            if (webRtcManager != null) webRtcManager.stop();
        } catch (Exception ignored) {}
        try {
            if (recognizer != null) recognizer.destroy();
        } catch (Exception ignored) {}
        try {
            if (voicePlayer != null) {
                voicePlayer.setOnPreparedListener(null);
                voicePlayer.setOnErrorListener(null);
                try {
                    voicePlayer.stop();
                } catch (Exception ignored) {}
                voicePlayer.release();
                voicePlayer = null;
            }
        } catch (Exception ignored) {}
        try {
            if (speaker != null && (callAssistantManager == null || !callAssistantManager.isCallSessionActive())) {
                speaker.stop();
                speaker.shutdown();
            }
        } catch (Exception ignored) {}
        try {
            if (callAssistantManager != null && !callAssistantManager.isCallSessionActive()) {
                callAssistantManager.cleanup();
                callAssistantManager = null;
            }
        } catch (Exception ignored) {}
        networkExecutor.shutdownNow();
        if (reactor != null) reactor.stopPulse();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        if (reactor != null) reactor.stopPulse();
        sessionKeepAwake = false;
        // Keep FLAG only while visible; always drop PowerManager lock in background.
        try { getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Exception ignored) {}
        releasePowerWakeLock();
        super.onPause();
        if (preferences != null) BackupManager.save(this, userName, preferences.getString("reminder_phone", "+393451039992"), memory, readPersonality(), preferences.getString("appointments_today", ""));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (reactor != null) reactor.startPulse();
        if (preferences != null) {
            userName = preferences.getString("user_name", userName);
            voiceEnabled = preferences.getBoolean("voice_enabled", voiceEnabled);
            wakeLockUserEnabled = preferences.getBoolean("wake_lock_enabled", wakeLockUserEnabled);
            boolean wasWake = wakeWordEnabled;
            wakeWordEnabled = preferences.getBoolean(WakePhrase.PREF_WAKE_WORD_ENABLED, false);
            if (wasWake != wakeWordEnabled && preferences.getBoolean("jarvis_core_wanted", false)) {
                // Refresh FGS so it picks up the new wake preference (only if CORE ON).
                Intent refresh = new Intent(this, JarvisForegroundService.class).setAction(JarvisForegroundService.ACTION_START);
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(refresh);
                    else startService(refresh);
                } catch (Exception ignored) {}
            }
            updateDeviceStatus();
        }
        applyScreenWakePolicy();
    }
}
