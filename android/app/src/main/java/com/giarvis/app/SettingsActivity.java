package com.giarvis.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.*;

public class SettingsActivity extends Activity {
    private EditText userName, phone, backendUrl, apiKeyInput;
    private Spinner modelSpinner, personality;
    private Switch voice, wakeLock;
    private Spinner ttsEngine, edgeVoice;
    private SeekBar speed;
    private SharedPreferences prefs;

    private static final String[] TTS_ENGINE_KEYS = {"edge-server", "native"};
    private static final String[] TTS_ENGINE_LABELS = {
        "Edge Neural TTS (online, come PWA)",
        "TTS nativo Android (offline)"
    };
    private static final String[] EDGE_VOICE_KEYS = {"", "it-male-multi"};
    private static final String[] EDGE_VOICE_LABELS = {
        "Diego (IT Maschile - Default JARVIS)",
        "Giuseppe Multilingue (IT Maschile)"
    };

    private static final String[] MODEL_KEYS = {
        "deepseek-v4.1-flash",
        "grok",
        "chatgpt",
        "gemini",
        "qwen",
        "stark"
    };

    private static final String[] MODEL_LABELS = {
        "DeepSeek-V4.1-Flash (MoE 552B)",
        "Grok-2 (xAI)",
        "ChatGPT (GPT-4o OpenAI)",
        "Gemini (Google DeepMind)",
        "Qwen-3.8-27B (Alibaba)",
        "Stark Mark VII (J.A.R.V.I.S.)"
    };

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(5, 11, 18));
        getWindow().setNavigationBarColor(Color.rgb(5, 11, 18));
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("jarvis_profile", MODE_PRIVATE);

        userName = findViewById(R.id.settingUserName);
        phone = findViewById(R.id.settingPhone);
        backendUrl = findViewById(R.id.settingBackendUrl);
        modelSpinner = findViewById(R.id.settingModel);
        apiKeyInput = findViewById(R.id.settingApiKey);
        personality = findViewById(R.id.settingPersonality);
        voice = findViewById(R.id.settingVoice);
        wakeLock = findViewById(R.id.settingWakeLock);
        ttsEngine = findViewById(R.id.settingTtsEngine);
        edgeVoice = findViewById(R.id.settingEdgeVoice);
        speed = findViewById(R.id.settingSpeed);

        // Popola spinner modelli AI
        ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, MODEL_LABELS);
        modelSpinner.setAdapter(modelAdapter);

        // Popola spinner personalità
        String[] modes = {"stark", "sarcastica", "elegante", "seria", "grok", "chatgpt", "gemini", "qwen", "deepseek"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, modes);
        personality.setAdapter(adapter);

        // Carica valori attuali
        userName.setText(prefs.getString("user_name", ""));
        phone.setText(prefs.getString("reminder_phone", ""));
        backendUrl.setText(prefs.getString("custom_backend_url", BuildConfig.BACKEND_URL));
        apiKeyInput.setText(prefs.getString("custom_api_key", ""));
        voice.setChecked(prefs.getBoolean("voice_enabled", true));
        wakeLock.setChecked(prefs.getBoolean("wake_lock_enabled", false));
        speed.setProgress(prefs.getInt("voice_speed", 50));

        ArrayAdapter<String> ttsAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, TTS_ENGINE_LABELS);
        ttsEngine.setAdapter(ttsAdapter);
        String currentTts = prefs.getString("tts_engine", "edge-server");
        for (int i = 0; i < TTS_ENGINE_KEYS.length; i++) {
            if (TTS_ENGINE_KEYS[i].equalsIgnoreCase(currentTts)) {
                ttsEngine.setSelection(i);
                break;
            }
        }

        ArrayAdapter<String> edgeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, EDGE_VOICE_LABELS);
        edgeVoice.setAdapter(edgeAdapter);
        String currentEdge = prefs.getString("edge_tts_voice", "");
        for (int i = 0; i < EDGE_VOICE_KEYS.length; i++) {
            if (EDGE_VOICE_KEYS[i].equals(currentEdge)) {
                edgeVoice.setSelection(i);
                break;
            }
        }

        // Seleziona modello attivo
        String currentModel = prefs.getString("ai_model", "deepseek-v4.1-flash");
        for (int i = 0; i < MODEL_KEYS.length; i++) {
            if (MODEL_KEYS[i].equalsIgnoreCase(currentModel)) {
                modelSpinner.setSelection(i);
                break;
            }
        }

        // Seleziona personalità attiva
        String currentPersonality = prefs.getString("personality", "stark");
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].equalsIgnoreCase(currentPersonality)) {
                personality.setSelection(i);
                break;
            }
        }

        findViewById(R.id.btnSaveSettings).setOnClickListener(v -> save());
        findViewById(R.id.btnRestoreBackup).setOnClickListener(v -> restoreBackup());
        findViewById(R.id.btnBackSettings).setOnClickListener(v -> finish());
    }

    private void restoreBackup() {
        if (!BackupManager.hasBackup(this)) {
            Toast.makeText(this, "Nessun file di backup trovato", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean ok = BackupManager.restore(this);
        if (ok) {
            userName.setText(prefs.getString("user_name", ""));
            phone.setText(prefs.getString("reminder_phone", ""));
            apiKeyInput.setText(prefs.getString("custom_api_key", ""));
            String m = prefs.getString("ai_model", "deepseek-v4.1-flash");
            for (int i = 0; i < MODEL_KEYS.length; i++) {
                if (MODEL_KEYS[i].equalsIgnoreCase(m)) {
                    modelSpinner.setSelection(i);
                    break;
                }
            }
            String p = prefs.getString("personality", "stark");
            for (int i = 0; i < personality.getCount(); i++) {
                if (personality.getItemAtPosition(i).toString().equalsIgnoreCase(p)) {
                    personality.setSelection(i);
                    break;
                }
            }
            Toast.makeText(this, "Backup ripristinato con successo", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Errore durante il ripristino del backup", Toast.LENGTH_SHORT).show();
        }
    }

    private void save() {
        SharedPreferences.Editor editor = prefs.edit();

        String name = userName.getText().toString().trim();
        if (!name.isEmpty()) editor.putString("user_name", name);

        String phoneVal = phone.getText().toString().trim();
        if (!phoneVal.isEmpty()) editor.putString("reminder_phone", phoneVal);

        String urlVal = backendUrl.getText().toString().trim();
        if (!urlVal.isEmpty() && !urlVal.equals(BuildConfig.BACKEND_URL)) {
            editor.putString("custom_backend_url", urlVal);
        } else {
            editor.remove("custom_backend_url");
        }

        int modelPos = modelSpinner.getSelectedItemPosition();
        if (modelPos >= 0 && modelPos < MODEL_KEYS.length) {
            editor.putString("ai_model", MODEL_KEYS[modelPos]);
        }

        String apiKey = apiKeyInput.getText().toString().trim();
        editor.putString("custom_api_key", apiKey);

        editor.putString("personality", personality.getSelectedItem().toString());
        editor.putBoolean("voice_enabled", voice.isChecked());
        editor.putBoolean("wake_lock_enabled", wakeLock.isChecked());
        editor.putInt("voice_speed", speed.getProgress());
        int ttsPos = ttsEngine.getSelectedItemPosition();
        if (ttsPos >= 0 && ttsPos < TTS_ENGINE_KEYS.length) {
            editor.putString("tts_engine", TTS_ENGINE_KEYS[ttsPos]);
        }
        int edgePos = edgeVoice.getSelectedItemPosition();
        if (edgePos >= 0 && edgePos < EDGE_VOICE_KEYS.length) {
            editor.putString("edge_tts_voice", EDGE_VOICE_KEYS[edgePos]);
        }
        editor.apply();

        Toast.makeText(this, "Configurazione salvata con successo", Toast.LENGTH_SHORT).show();
        finish();
    }
}
