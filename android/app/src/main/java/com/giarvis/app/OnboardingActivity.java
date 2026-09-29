package com.giarvis.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Onboarding a 3 step mostrato solo al primo avvio.
 * Step 1: Benvenuto e presentazione
 * Step 2: Nome utente e numero telefono
 * Step 3: Permessi (microfono, fotocamera, notifiche)
 */
public class OnboardingActivity extends Activity {
    private SharedPreferences prefs;
    private int step = 1;
    private EditText nameInput, phoneInput;
    private TextView description, permissionsText, stepIndicator, subtitle;
    private Button nextBtn;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("jarvis_profile", MODE_PRIVATE);

        // Se onboarding già completato, vai direttamente a MainActivity
        if (prefs.getBoolean("onboarding_done", false)) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        getWindow().setStatusBarColor(Color.rgb(5, 11, 18));
        getWindow().setNavigationBarColor(Color.rgb(5, 11, 18));
        setContentView(R.layout.activity_onboarding);

        nameInput = findViewById(R.id.onboardName);
        phoneInput = findViewById(R.id.onboardPhone);
        description = findViewById(R.id.onboardDescription);
        permissionsText = findViewById(R.id.onboardPermissions);
        stepIndicator = findViewById(R.id.onboardStep);
        subtitle = findViewById(R.id.onboardSubtitle);
        nextBtn = findViewById(R.id.onboardNext);

        nextBtn.setOnClickListener(v -> advance());
    }

    private void advance() {
        switch (step) {
            case 1:
                // Passa a step 2: input nome e telefono
                step = 2;
                description.setVisibility(View.GONE);
                nameInput.setVisibility(View.VISIBLE);
                phoneInput.setVisibility(View.VISIBLE);
                subtitle.setText("Configurazione profilo");
                nextBtn.setText("CONTINUA →");
                stepIndicator.setText("STEP 2 / 3");
                break;

            case 2:
                // Valida e salva
                String name = nameInput.getText().toString().trim();
                if (name.isEmpty()) {
                    nameInput.setError("Inserisci il tuo nome");
                    return;
                }
                String phone = phoneInput.getText().toString().trim();

                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("user_name", name);
                if (!phone.isEmpty()) editor.putString("reminder_phone", phone);
                editor.apply();

                // Passa a step 3: permessi
                step = 3;
                nameInput.setVisibility(View.GONE);
                phoneInput.setVisibility(View.GONE);
                permissionsText.setVisibility(View.VISIBLE);
                subtitle.setText("Autorizzazioni sistema");
                nextBtn.setText("CONCEDI PERMESSI E ATTIVA →");
                stepIndicator.setText("STEP 3 / 3");
                break;

            case 3:
                // Richiedi permessi
                requestAllPermissions();
                completeOnboarding();
                break;
        }
    }

    private void requestAllPermissions() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= 33) {
            perms = new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_PHONE_STATE
            };
        } else {
            perms = new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_PHONE_STATE
            };
        }

        boolean needRequest = false;
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }

        if (needRequest) {
            requestPermissions(perms, 100);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // L'onboarding è già completato indipendentemente dal risultato
    }

    private void completeOnboarding() {
        prefs.edit().putBoolean("onboarding_done", true).apply();
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
