package com.giarvis.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class ChatHistoryActivity extends Activity {
    private TextView historyContent;
    private SharedPreferences prefs;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(5, 11, 18));
        getWindow().setNavigationBarColor(Color.rgb(5, 11, 18));
        setContentView(R.layout.activity_chat_history);

        prefs = getSharedPreferences("jarvis_profile", MODE_PRIVATE);
        historyContent = findViewById(R.id.historyContent);

        findViewById(R.id.btnClearHistory).setOnClickListener(v -> clearHistory());
        findViewById(R.id.btnBackHistory).setOnClickListener(v -> finish());

        loadHistory();
    }

    private void loadHistory() {
        String memory = prefs.getString("conversation_memory", "");
        if (memory.trim().isEmpty()) {
            historyContent.setText("Nessuna conversazione memorizzata.\n\nParla con J.A.R.V.I.S. per iniziare.");
            historyContent.setTextColor(Color.rgb(80, 125, 140));
            return;
        }

        // Formatta i messaggi con colori diversi per Tu: e JARVIS:
        SpannableStringBuilder builder = new SpannableStringBuilder();
        String[] lines = memory.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            int start = builder.length();
            builder.append(trimmed);
            builder.append("\n\n");

            if (trimmed.startsWith("Tu:") || trimmed.startsWith("FATTO:")) {
                builder.setSpan(new ForegroundColorSpan(Color.rgb(0, 229, 255)),
                        start, start + trimmed.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.startsWith("JARVIS:")) {
                builder.setSpan(new ForegroundColorSpan(Color.rgb(155, 219, 235)),
                        start, start + trimmed.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                builder.setSpan(new ForegroundColorSpan(Color.rgb(120, 180, 200)),
                        start, start + trimmed.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        historyContent.setText(builder);
    }

    private void clearHistory() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Cancella cronologia")
                .setMessage("Vuoi cancellare tutta la cronologia delle conversazioni?")
                .setNegativeButton("ANNULLA", null)
                .setPositiveButton("CANCELLA", (d, w) -> {
                    prefs.edit().remove("conversation_memory").apply();
                    loadHistory();
                    Toast.makeText(this, "Cronologia cancellata", Toast.LENGTH_SHORT).show();
                })
                .show();
    }
}
