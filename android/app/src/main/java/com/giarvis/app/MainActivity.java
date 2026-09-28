package com.giarvis.app;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final String BACKEND_URL = "https://REPLACE_WITH_RENDER_URL.onrender.com/";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}, 7);
        WebView view = new WebView(this);
        WebSettings s = view.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setMediaPlaybackRequiresUserGesture(false);
        view.setWebViewClient(new WebViewClient()); view.setWebChromeClient(new WebChromeClient());
        view.loadUrl(BACKEND_URL); setContentView(view);
    }
}
