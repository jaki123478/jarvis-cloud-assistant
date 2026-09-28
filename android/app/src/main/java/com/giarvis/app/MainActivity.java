package com.giarvis.app;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.RecognitionListener;
import android.speech.tts.TextToSpeech;
import android.media.MediaPlayer;
import android.graphics.Color;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String BACKEND_URL = "https://jarvis-cloud-assistant-4dsr.onrender.com/chat";
    private EditText input; private TextView transcript, status; private SpeechRecognizer recognizer; private TextToSpeech speaker; private MediaPlayer voicePlayer;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildNativeUi(); speaker = new TextToSpeech(this, r -> { if (r == TextToSpeech.SUCCESS) speaker.setLanguage(Locale.ITALIAN); });
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10);
    }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) { super.onRequestPermissionsResult(requestCode, permissions, results); if (requestCode == 10) status.setText(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED ? "MICROFONO PRONTO" : "PERMESSO MICROFONO NEGATO"); }
    private void buildNativeUi() { LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20),dp(24),dp(20),dp(16)); root.setBackgroundColor(Color.rgb(5,12,24)); TextView title=new TextView(this); title.setText("J.A.R.V.I.S."); title.setTextColor(Color.rgb(74,220,255)); title.setTextSize(28); title.setGravity(17); status=new TextView(this); status.setText("CONNESSIONE CLOUD PRONTA"); status.setTextColor(Color.rgb(120,180,200)); status.setGravity(17); status.setPadding(0,dp(6),0,dp(12)); transcript=new TextView(this); transcript.setText("Salve. Sono pronto."); transcript.setTextColor(Color.WHITE); transcript.setTextSize(17); transcript.setLineSpacing(dp(4),1.0f); transcript.setPadding(dp(14),dp(14),dp(14),dp(14)); ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.addView(transcript); root.addView(title,new LinearLayout.LayoutParams(-1,dp(48))); root.addView(status,new LinearLayout.LayoutParams(-1,dp(42))); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); input=new EditText(this); input.setHint("Scrivi un comando..."); input.setTextColor(Color.WHITE); input.setHintTextColor(Color.GRAY); input.setTextSize(16); input.setSingleLine(true); input.setPadding(dp(12),0,dp(12),0); root.addView(input,new LinearLayout.LayoutParams(-1,dp(52))); LinearLayout buttons=new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL); Button send=new Button(this); send.setText("INVIA"); send.setAllCaps(false); send.setOnClickListener(v->sendMessage(input.getText().toString())); Button mic=new Button(this); mic.setText("PARLA"); mic.setAllCaps(false); mic.setOnClickListener(v->listen()); buttons.addView(send,new LinearLayout.LayoutParams(0,dp(52),1)); buttons.addView(mic,new LinearLayout.LayoutParams(0,dp(52),1)); root.addView(buttons); setContentView(root); }
    private void listen() { if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10);status.setText("AUTORIZZA IL MICROFONO");return;} if(!SpeechRecognizer.isRecognitionAvailable(this)){status.setText("VOCE NON DISPONIBILE");return;} if(recognizer!=null)recognizer.destroy(); recognizer=SpeechRecognizer.createSpeechRecognizer(this); recognizer.setRecognitionListener(new RecognitionListener(){ public void onReadyForSpeech(Bundle b){status.setText("ASCOLTO...");} public void onResults(Bundle b){ArrayList<String> r=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(r!=null&&!r.isEmpty()){input.setText(r.get(0));sendMessage(r.get(0));}} public void onError(int e){status.setText("MICROFONO PRONTO");} public void onBeginningOfSpeech(){} public void onEndOfSpeech(){} public void onRmsChanged(float v){} public void onBufferReceived(byte[] b){} public void onPartialResults(Bundle b){} public void onEvent(int a,Bundle b){} }); Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"it-IT"); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); recognizer.startListening(i); }
    private void sendMessage(String message) { if(message==null||message.trim().isEmpty())return; status.setText("ELABORAZIONE CLOUD..."); transcript.setText("Tu: "+message+"\n\nJARVIS: ..."); Executors.newSingleThreadExecutor().execute(()->{try{HttpURLConnection c=(HttpURLConnection)new URL(BACKEND_URL).openConnection();c.setRequestMethod("POST");c.setConnectTimeout(30000);c.setReadTimeout(60000);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");String body="{\"message\":\""+jsonEscape(message)+"\"}";try(OutputStream o=c.getOutputStream()){o.write(body.getBytes(StandardCharsets.UTF_8));}InputStream stream=c.getResponseCode()>=400?c.getErrorStream():c.getInputStream();String answer=extract(read(stream),"reply");runOnUiThread(()->{status.setText("ONLINE • CLOUD JARVIS");transcript.setText("Tu: "+message+"\n\nJARVIS: "+answer);playCloudVoice(answer);});}catch(Exception e){runOnUiThread(()->{status.setText("ERRORE CONNESSIONE");transcript.setText("Impossibile raggiungere il server: "+e.getMessage());});}}); }
    private void playCloudVoice(String text) { try { if(voicePlayer!=null){voicePlayer.release();voicePlayer=null;} String u="https://jarvis-cloud-assistant-4dsr.onrender.com/tts/audio?voice=it-male&text="+URLEncoder.encode(text,"UTF-8"); voicePlayer=new MediaPlayer(); voicePlayer.setDataSource(u); voicePlayer.setOnPreparedListener(MediaPlayer::start); voicePlayer.setOnErrorListener((p,w,e)->{if(speaker!=null)speaker.speak(text,TextToSpeech.QUEUE_FLUSH,null,"jarvis-fallback");return true;}); voicePlayer.prepareAsync(); } catch(Exception e) { if(speaker!=null)speaker.speak(text,TextToSpeech.QUEUE_FLUSH,null,"jarvis-fallback"); } }
    private static String read(InputStream s)throws IOException{if(s==null)return "";StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(s,StandardCharsets.UTF_8))){String x;while((x=r.readLine())!=null)b.append(x);}return b.toString();}
    private static String jsonEscape(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
    private static String extract(String json,String key){String m="\""+key+"\":";int p=json.indexOf(m);if(p<0)return json;p+=m.length();while(p<json.length()&&Character.isWhitespace(json.charAt(p)))p++;if(p<json.length()&&json.charAt(p)=='\"'){p++;StringBuilder b=new StringBuilder();boolean e=false;for(;p<json.length();p++){char ch=json.charAt(p);if(e){b.append(ch=='n'?'\n':ch);e=false;}else if(ch=='\\')e=true;else if(ch=='\"')break;else b.append(ch);}return b.toString();}return json.substring(p).split(",",2)[0];}
    @Override protected void onDestroy(){if(recognizer!=null)recognizer.destroy();if(voicePlayer!=null)voicePlayer.release();if(speaker!=null){speaker.stop();speaker.shutdown();}super.onDestroy();}
}
