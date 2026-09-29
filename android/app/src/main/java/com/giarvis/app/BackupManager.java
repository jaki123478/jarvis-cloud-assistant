package com.giarvis.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Backup e ripristino locale privato, confinato nella sandbox dell'app. */
public final class BackupManager {
    private static final String BACKUP_FILENAME = "jarvis-backup.json";

    private BackupManager() {}

    public static void save(Context context, String userName, String phone, String memory, String personality, String appointments) {
        if (context == null) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("user_name", userName == null ? "" : userName);
            obj.put("phone", phone == null ? "" : phone);
            obj.put("memory", memory == null ? "" : memory);
            obj.put("personality", personality == null ? "stark" : personality);
            obj.put("appointments", appointments == null ? "" : appointments);
            obj.put("timestamp", System.currentTimeMillis());

            try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), BACKUP_FILENAME))) {
                out.write(obj.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    public static boolean restore(Context context) {
        if (context == null) return false;
        File file = new File(context.getFilesDir(), BACKUP_FILENAME);
        if (!file.exists() || !file.canRead()) return false;

        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int read = in.read(buffer);
            if (read <= 0) return false;

            String jsonStr = new String(buffer, StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(jsonStr);

            SharedPreferences prefs = context.getSharedPreferences("jarvis_profile", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            if (obj.has("user_name")) editor.putString("user_name", obj.optString("user_name", ""));
            if (obj.has("phone")) editor.putString("reminder_phone", obj.optString("phone", ""));
            if (obj.has("memory")) editor.putString("conversation_memory", obj.optString("memory", ""));
            if (obj.has("personality")) editor.putString("personality", obj.optString("personality", "stark"));
            if (obj.has("appointments")) editor.putString("appointments_today", obj.optString("appointments", ""));
            editor.apply();

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean hasBackup(Context context) {
        if (context == null) return false;
        File file = new File(context.getFilesDir(), BACKUP_FILENAME);
        return file.exists() && file.length() > 0;
    }
}
