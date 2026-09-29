package com.giarvis.app;

import android.service.voice.VoiceInteractionService;
import android.content.Intent;

/** Permette all'utente di scegliere J.A.R.V.I.S. come assistente predefinito. */
public class JarvisVoiceInteractionService extends VoiceInteractionService {
    @Override public void onReady() { super.onReady(); }
    @Override public void onLaunchVoiceAssistFromKeyguard() { launch(); }
    private void launch(){ try { startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)); } catch(Exception ignored){} }
}
