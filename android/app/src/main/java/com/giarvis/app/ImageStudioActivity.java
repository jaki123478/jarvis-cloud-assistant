package com.giarvis.app;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.ContentValues;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Studio immagini: nessun file viene salvato automaticamente. */
public class ImageStudioActivity extends Activity {
    private static final int PICK_IMAGE = 41, TAKE_IMAGE = 42;
    private ImageView preview;
    private EditText prompt;
    private TextView status;
    private Uri selected;
    private Uri pendingCameraUri;
    private String resultData;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String getBackendUrl() {
        return getSharedPreferences("jarvis_profile", MODE_PRIVATE).getString("custom_backend_url", BuildConfig.BACKEND_URL);
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_image_studio);

        preview = findViewById(R.id.preview);
        prompt = findViewById(R.id.prompt);
        status = findViewById(R.id.imgStatus);

        Button gallery = findViewById(R.id.btnGallery);
        Button camera = findViewById(R.id.btnCamera);
        Button analyze = findViewById(R.id.btnAnalyze);
        Button edit = findViewById(R.id.btnEdit);
        Button generate = findViewById(R.id.btnGenerate);
        Button save = findViewById(R.id.btnSave);
        Button share = findViewById(R.id.btnShare);

        gallery.setOnClickListener(v -> pick());
        camera.setOnClickListener(v -> camera());
        analyze.setOnClickListener(v -> run("analyze"));
        edit.setOnClickListener(v -> run("edit"));
        generate.setOnClickListener(v -> run("generate"));
        save.setOnClickListener(v -> save());
        share.setOnClickListener(v -> share());
    }

    private void pick() {
        startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("image/*")
                        .addCategory(Intent.CATEGORY_OPENABLE),
                PICK_IMAGE
        );
    }

    private void camera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 11);
            return;
        }
        try {
            File photoFile = new File(getCacheDir(), "jarvis_cam_" + System.currentTimeMillis() + ".jpg");
            pendingCameraUri = FileProvider.getUriForFile(this, "com.giarvis.app.fileprovider", photoFile);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(i, TAKE_IMAGE);
            } else {
                status.setText("FOTOCAMERA NON DISPONIBILE");
            }
        } catch (Exception e) {
            status.setText("ERRORE AVVIO CAMERA: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int r, int c, Intent d) {
        super.onActivityResult(r, c, d);
        if (c != RESULT_OK) return;

        if (r == PICK_IMAGE && d != null) {
            selected = d.getData();
            try {
                if (selected == null) throw new IOException("URI immagine mancante");
                preview.setImageBitmap(BitmapFactory.decodeStream(getContentResolver().openInputStream(selected)));
                status.setText("IMMAGINE PRONTA // SCEGLI UN'AZIONE");
            } catch (Exception e) {
                selected = null;
                status.setText("FILE NON LEGGIBILE");
            }
        } else if (r == TAKE_IMAGE) {
            if (pendingCameraUri != null) {
                selected = pendingCameraUri;
                try {
                    preview.setImageBitmap(BitmapFactory.decodeStream(getContentResolver().openInputStream(selected)));
                    status.setText("FOTO HD CATTURATA // SCEGLI UN'AZIONE");
                } catch (Exception e) {
                    selected = null;
                    status.setText("ERRORE CARICAMENTO FOTO HD");
                }
            } else {
                status.setText("FOTO NON DISPONIBILE");
            }
        }
    }

    private void run(String mode) {
        String p = prompt.getText().toString().trim();
        if (mode.equals("generate") && p.isEmpty()) {
            status.setText("INSERISCI UNA DESCRIZIONE");
            return;
        }
        if (!mode.equals("generate") && selected == null) {
            status.setText("ALLEGA PRIMA UNA FOTO");
            return;
        }
        status.setText("ELABORAZIONE // ATTENDERE...");
        try {
            executor.execute(() -> {
                try {
                    String json;
                    if (mode.equals("generate")) {
                        json = postJson(getBackendUrl() + "/images/generate", "{\"prompt\":\"" + escape(p) + "\"}");
                    } else if (mode.equals("analyze")) {
                        json = postMultipart(getBackendUrl() + "/images/analyze", readSelected(), "image", "prompt", p.isEmpty() ? "Descrivi l'immagine." : p);
                    } else {
                        json = postMultipart(getBackendUrl() + "/images/edit", readSelected(), "image", "prompt", p);
                    }

                    JSONObject res = new JSONObject(json);
                    String image = res.optString("image", "");
                    if (!image.isEmpty()) {
                        resultData = image;
                        byte[] bytes = Base64.getDecoder().decode(image.substring(image.indexOf(',') + 1));
                        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        runOnUiThread(() -> {
                            preview.setImageBitmap(bmp);
                            status.setText("COMPLETATO // RISULTATO TEMPORANEO");
                        });
                    } else {
                        String reply = res.optString("reply", "RISPOSTA NON DISPONIBILE");
                        runOnUiThread(() -> status.setText(reply));
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> status.setText("ERRORE IMMAGINI // " + e.getMessage()));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            status.setText("ATTIVITÀ IN CHIUSURA");
        }
    }

    private byte[] readSelected() throws Exception {
        if (selected.toString().startsWith("data:")) {
            return Base64.getDecoder().decode(selected.toString().substring(selected.toString().indexOf(',') + 1));
        }
        InputStream i = getContentResolver().openInputStream(selected);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = i.read(b)) > 0) o.write(b, 0, n);
        i.close();
        return o.toByteArray();
    }

    private String postJson(String u, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(90000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        return response(c);
    }

    private String postMultipart(String u, byte[] data, String field, String fieldName, String value) throws Exception {
        String boundary = "----Jarvis" + System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(90000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        OutputStream o = c.getOutputStream();
        String h = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\"image.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n";
        o.write(h.getBytes(StandardCharsets.UTF_8));
        o.write(data);
        o.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fieldName + "\"\r\n\r\n" + value + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        o.close();
        return response(c);
    }

    private String response(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream i = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (i == null) throw new IOException("Risposta vuota dal server");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = i.read(b)) != -1) o.write(b, 0, n);
        i.close();
        String s = new String(o.toByteArray(), StandardCharsets.UTF_8);
        if (code >= 400) throw new IOException(s);
        return s;
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private void save() {
        if (resultData == null) {
            status.setText("NESSUN RISULTATO DA SALVARE");
            return;
        }
        try {
            byte[] b = Base64.getDecoder().decode(resultData.substring(resultData.indexOf(',') + 1));
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "jarvis_" + System.currentTimeMillis() + ".png");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JARVIS");
            Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            OutputStream o = getContentResolver().openOutputStream(u);
            o.write(b);
            o.close();
            status.setText("SALVATO IN GALLERIA");
        } catch (Exception e) {
            status.setText("SALVATAGGIO NON RIUSCITO");
        }
    }

    private void share() {
        if (resultData == null) {
            status.setText("NESSUN RISULTATO DA CONDIVIDERE");
            return;
        }
        try {
            byte[] b = Base64.getDecoder().decode(resultData.substring(resultData.indexOf(',') + 1));
            File f = new File(getCacheDir(), "jarvis_share.png");
            FileOutputStream o = new FileOutputStream(f);
            o.write(b);
            o.close();
            Uri u = FileProvider.getUriForFile(this, "com.giarvis.app.fileprovider", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("image/png");
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Condividi immagine J.A.R.V.I.S."));
        } catch (Exception e) {
            status.setText("CONDIVISIONE NON DISPONIBILE");
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
