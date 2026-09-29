package com.giarvis.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Best-effort hooks for server-side persistent memory.
 * Endpoints (sibling may add fuller parity):
 *   GET  /memory?session_id=...
 *   POST /memory  {session_id, memory|facts}
 *   POST /reset   (existing — clears server conversation histories)
 * Gracefully no-ops on 404/network errors; local SharedPreferences remain source of truth on device.
 */
public final class MemoryApiClient {
    private static final String TAG = "JarvisMemory";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    public interface Callback {
        void onResult(boolean ok, String bodyOrMessage);
    }

    private MemoryApiClient() {}

    public static void pull(Context context, String backendUrl, String sessionId, Callback cb) {
        EXEC.execute(() -> {
            HttpURLConnection c = null;
            try {
                String url = trimSlash(backendUrl) + "/memory?session_id=" + enc(sessionId);
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                String body = read(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
                if (code == 200) {
                    String remote = extractJsonString(body, "memory");
                    if (remote == null) remote = extractJsonString(body, "facts");
                    if (remote != null && !remote.trim().isEmpty()) {
                        SharedPreferences prefs = context.getSharedPreferences("jarvis_profile", Context.MODE_PRIVATE);
                        prefs.edit().putString("conversation_memory", remote.trim()).apply();
                    }
                    done(cb, true, body);
                } else if (code == 404) {
                    done(cb, false, "memory_endpoint_absent");
                } else {
                    done(cb, false, "http_" + code);
                }
            } catch (Exception e) {
                Log.d(TAG, "pull failed: " + e.getMessage());
                done(cb, false, e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    public static void push(Context context, String backendUrl, String sessionId, String memory) {
        EXEC.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(trimSlash(backendUrl) + "/memory").openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                String payload = "{\"session_id\":\"" + esc(sessionId) + "\",\"client\":\"android\",\"memory\":\""
                        + esc(memory == null ? "" : memory) + "\"}";
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(bytes);
                }
                int code = c.getResponseCode();
                if (code != 200 && code != 201 && code != 204 && code != 404) {
                    Log.d(TAG, "push http " + code);
                }
            } catch (Exception e) {
                Log.d(TAG, "push failed: " + e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    /** Clears server session memory via existing POST /reset (and optional /memory DELETE). */
    public static void resetServer(String backendUrl, String sessionId) {
        EXEC.execute(() -> {
            postEmpty(trimSlash(backendUrl) + "/reset");
            // Future-proof: sibling may add session-scoped clear
            postJson(trimSlash(backendUrl) + "/memory/clear",
                    "{\"session_id\":\"" + esc(sessionId == null ? "" : sessionId) + "\",\"client\":\"android\"}");
        });
    }

    private static void postEmpty(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3500);
            c.setReadTimeout(3500);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            c.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void postJson(String url, String json) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3500);
            c.setReadTimeout(3500);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            c.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void done(Callback cb, boolean ok, String msg) {
        if (cb == null) return;
        new Handler(Looper.getMainLooper()).post(() -> cb.onResult(ok, msg));
    }

    private static String trimSlash(String u) {
        if (u == null) return "";
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line);
        }
        return b.toString();
    }

    /** Tiny extractor — avoids org.json dependency edge cases for optional fields. */
    private static String extractJsonString(String json, String key) {
        if (json == null || key == null) return null;
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        StringBuilder out = new StringBuilder();
        for (int p = q1 + 1; p < json.length(); p++) {
            char ch = json.charAt(p);
            if (ch == '\\' && p + 1 < json.length()) {
                char n = json.charAt(++p);
                if (n == 'n') out.append('\n');
                else if (n == 'r') out.append('\r');
                else if (n == 't') out.append('\t');
                else out.append(n);
            } else if (ch == '"') {
                return out.toString();
            } else {
                out.append(ch);
            }
        }
        return null;
    }
}