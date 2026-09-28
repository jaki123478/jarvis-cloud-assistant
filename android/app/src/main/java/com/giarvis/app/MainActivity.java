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
import android.graphics.Color;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String BACKEND_URL = "https://jarvis-cloud-assistant-4dsr.onrender.com/chat";
    private EditText input; private TextView transcript, status; private SpeechRecognizer recognizer; private TextToSpeech speaker;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildNativeUi(); speaker = new TextToSpeech(this, r -> { if (r == TextToSpeech.SUCCESS) speaker.setLanguage(Locale.ITALIAN); });
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10);
    }
    private void buildNativeUi() { LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(28,36,28,24); root.setBackgroundColor(Color.rgb(5,12,24)); TextView title=new TextView(this); title.setText("J.A.R.V.I.S."); title.setTextColor(Color.rgb(74,220,255)); title.setTextSize(30); title.setGravity(17); status=new TextView(this); status.setText("CONNESSIONE CLOUD PRONTA"); status.setTextColor(Color.rgb(120,180,200)); status.setGravity(17); transcript=new TextView(this); transcript.setText("Salve. Sono pronto, signore."); transcript.setTextColor(Color.WHITE); transcript.setTextSize(18); transcript.setPadding(16,16,16,16); ScrollView scroll=new ScrollView(this); scroll.addView(transcript); root.addView(title); root.addView(status); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); input=new EditText(this); input.setHint("Scrivi un comando..."); input.setTextColor(Color.WHITE); input.setHintTextColor(Color.GRAY); input.setSingleLine(true); root.addView(input); LinearLayout buttons=new LinearLayout(this); Button send=new Button(this); send.setText("INVIA"); send.setOnClickListener(v->sendMessage(input.getText().toString())); Button mic=new Button(this); mic.setText("🎙 PARLA"); mic.setOnClickListener(v->listen()); buttons.addView(send,new LinearLayout.LayoutParams(0,-2,1)); buttons.addView(mic,new LinearLayout.LayoutParams(0,-2,1)); root.addView(buttons); setContentView(root); }
    private void listen() { if(!SpeechRecognizer.isRecognitionAvailable(this)){status.setText("VOCE NON DISPONIBILE");return;} if(recognizer!=null)recognizer.destroy(); recognizer=SpeechRecognizer.createSpeechRecognizer(this); recognizer.setRecognitionListener(new RecognitionListener(){ public void onReadyForSpeech(Bundle b){status.setText("ASCOLTO...");} public void onResults(Bundle b){ArrayList<String> r=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(r!=null&&!r.isEmpty()){input.setText(r.get(0));sendMessage(r.get(0));}} public void onError(int e){status.setText("MICROFONO PRONTO");} public void onBeginningOfSpeech(){} public void onEndOfSpeech(){} public void onRmsChanged(float v){} public void onBufferReceived(byte[] b){} public void onPartialResults(Bundle b){} public void onEvent(int a,Bundle b){} }); Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"it-IT"); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); recognizer.startListening(i); }
    private void sendMessage(String message) { if(message==null||message.trim().isEmpty())return; status.setText("ELABORAZIONE CLOUD..."); transcript.setText("Tu: "+message+"\n\nJARVIS: ..."); Executors.newSingleThreadExecutor().execute(()->{try{HttpURLConnection c=(HttpURLConnection)new URL(BACKEND_URL).openConnection();c.setRequestMethod("POST");c.setConnectTimeout(30000);c.setReadTimeout(60000);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");String body="{\"message\":\""+jsonEscape(message)+"\"}";try(OutputStream o=c.getOutputStream()){o.write(body.getBytes(StandardCharsets.UTF_8));}InputStream stream=c.getResponseCode()>=400?c.getErrorStream():c.getInputStream();String answer=extract(read(stream),"reply");runOnUiThread(()->{status.setText("ONLINE • CLOUD JARVIS");transcript.setText("Tu: "+message+"\n\nJARVIS: "+answer);if(speaker!=null)speaker.speak(answer,TextToSpeech.QUEUE_FLUSH,null,"jarvis");});}catch(Exception e){runOnUiThread(()->{status.setText("ERRORE CONNESSIONE");transcript.setText("Impossibile raggiungere il server: "+e.getMessage());});}}); }
    private static String read(InputStream s)throws IOException{if(s==null)return "";StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(s,StandardCharsets.UTF_8))){String x;while((x=r.readLine())!=null)b.append(x);}return b.toString();}
    private static String jsonEscape(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
    private static String extract(String json,String key){String m="\""+key+"\":";int p=json.indexOf(m);if(p<0)return json;p+=m.length();while(p<json.length()&&Character.isWhitespace(json.charAt(p)))p++;if(p<json.length()&&json.charAt(p)=='\"'){p++;StringBuilder b=new StringBuilder();boolean e=false;for(;p<json.length();p++){char ch=json.charAt(p);if(e){b.append(ch=='n'?'\n':ch);e=false;}else if(ch=='\\')e=true;else if(ch=='\"')break;else b.append(ch);}return b.toString();}return json.substring(p).split(",",2)[0];}
    @Override protected void onDestroy(){if(recognizer!=null)recognizer.destroy();if(speaker!=null){speaker.stop();speaker.shutdown();}super.onDestroy();}
}
