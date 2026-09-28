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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    private void buildNativeUi() {
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14),dp(12),dp(14),dp(12)); root.setBackgroundColor(Color.rgb(3,10,19));
        TextView title=new TextView(this); title.setText("J.A.R.V.I.S."); title.setTextColor(Color.rgb(0,229,255)); title.setTextSize(27); title.setTypeface(Typeface.MONOSPACE,Typeface.BOLD); title.setGravity(17);
        status=new TextView(this); status.setText("ONLINE // STANDBY"); status.setTextColor(Color.rgb(60,220,235)); status.setTextSize(11); status.setTypeface(Typeface.MONOSPACE,Typeface.BOLD); status.setGravity(17); status.setPadding(0,dp(2),0,dp(5));
        TextView telemetry=new TextView(this); telemetry.setText("STARK OS // MK VII        FPS 60 // PING 3ms\nWAKE LOCK: OFF                 BATTERY 100%"); telemetry.setTextColor(Color.rgb(0,185,210)); telemetry.setTextSize(9); telemetry.setTypeface(Typeface.MONOSPACE,Typeface.BOLD); telemetry.setGravity(17); telemetry.setPadding(0,dp(5),0,dp(5)); telemetry.setBackground(frame(Color.rgb(5,25,38),Color.rgb(0,120,150)));
        root.addView(title,new LinearLayout.LayoutParams(-1,dp(42))); root.addView(status,new LinearLayout.LayoutParams(-1,dp(26))); root.addView(telemetry,new LinearLayout.LayoutParams(-1,dp(48)));
        root.addView(new ReactorView(this),new LinearLayout.LayoutParams(-1,dp(220)));
        TextView hint=new TextView(this); hint.setText("TOCCA IL REATTORE O IL MICROFONO"); hint.setTextColor(Color.rgb(0,229,255)); hint.setTextSize(11); hint.setTypeface(Typeface.MONOSPACE,Typeface.BOLD); hint.setGravity(17); root.addView(hint,new LinearLayout.LayoutParams(-1,dp(34)));
        input=new EditText(this); input.setHint("Scrivi un comando..."); input.setTextColor(Color.WHITE); input.setHintTextColor(Color.rgb(80,125,140)); input.setTextSize(15); input.setSingleLine(true); input.setTypeface(Typeface.MONOSPACE); input.setPadding(dp(12),0,dp(12),0); input.setBackground(frame(Color.rgb(8,25,38),Color.rgb(0,105,140))); root.addView(input,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout buttons=new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL); buttons.setPadding(0,dp(8),0,0);
        Button send=new Button(this); send.setText("INVIA COMANDO"); send.setTextColor(Color.rgb(0,229,255)); send.setTextSize(12); send.setTypeface(Typeface.MONOSPACE,Typeface.BOLD); send.setAllCaps(false); send.setOnClickListener(v->sendMessage(input.getText().toString()));
        Button mic=new Button(this); mic.setText("MICROFONO"); mic.setTextColor(Color.rgb(0,229,255)); mic.setTextSize(12); mic.setTypeface(Typeface.MONOSPACE,Typeface.BOLD); mic.setAllCaps(false); mic.setOnClickListener(v->listen()); send.setBackground(frame(Color.rgb(5,30,42),Color.rgb(0,170,200))); mic.setBackground(frame(Color.rgb(5,30,42),Color.rgb(0,170,200))); buttons.addView(send,new LinearLayout.LayoutParams(0,dp(50),1)); buttons.addView(mic,new LinearLayout.LayoutParams(0,dp(50),1)); root.addView(buttons);
        transcript=new TextView(this); transcript.setText("// FEED ATTIVITÀ  // MISSION LOG\nJ.A.R.V.I.S. pronto. Pronuncia ‘Jarvis’ o scrivi un comando."); transcript.setTextColor(Color.rgb(155,220,235)); transcript.setTextSize(12); transcript.setTypeface(Typeface.MONOSPACE); transcript.setLineSpacing(dp(3),1.0f); transcript.setPadding(dp(12),dp(10),dp(12),dp(10)); transcript.setBackground(frame(Color.rgb(3,18,29),Color.rgb(0,100,130))); ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.addView(transcript); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
    }

    private GradientDrawable frame(int fill,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setStroke(dp(1),stroke);g.setCornerRadius(dp(2));return g;}

    private class ReactorView extends View { Paint p=new Paint(1); ReactorView(android.content.Context c){super(c);p.setTypeface(Typeface.MONOSPACE);setOnClickListener(v->listen());}
        protected void onDraw(Canvas c){super.onDraw(c);float cx=getWidth()/2f,cy=getHeight()/2f;float r=Math.min(getWidth(),getHeight())*.38f;p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setColor(Color.rgb(0,130,170));c.drawCircle(cx,cy,r+dp(20),p);p.setStrokeWidth(dp(5));p.setColor(Color.rgb(0,229,255));c.drawCircle(cx,cy,r,p);p.setStrokeWidth(dp(2));p.setColor(Color.rgb(30,120,240));c.drawCircle(cx,cy,r-dp(18),p);p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(0,180,220));c.drawCircle(cx,cy,dp(23),p);p.setColor(Color.rgb(225,255,255));c.drawCircle(cx,cy,dp(8),p);p.setColor(Color.rgb(70,230,255));p.setTextSize(dp(10));p.setTextAlign(Paint.Align.CENTER);c.drawText("CORE ONLINE",cx,cy+r+dp(26),p);}
    }
    private void listen() { if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10);status.setText("AUTORIZZA IL MICROFONO");return;} if(!SpeechRecognizer.isRecognitionAvailable(this)){status.setText("VOCE NON DISPONIBILE");return;} if(recognizer!=null)recognizer.destroy(); recognizer=SpeechRecognizer.createSpeechRecognizer(this); recognizer.setRecognitionListener(new RecognitionListener(){ public void onReadyForSpeech(Bundle b){status.setText("ASCOLTO...");} public void onResults(Bundle b){ArrayList<String> r=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(r!=null&&!r.isEmpty()){input.setText(r.get(0));sendMessage(r.get(0));}} public void onError(int e){status.setText("MICROFONO PRONTO");} public void onBeginningOfSpeech(){} public void onEndOfSpeech(){} public void onRmsChanged(float v){} public void onBufferReceived(byte[] b){} public void onPartialResults(Bundle b){} public void onEvent(int a,Bundle b){} }); Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"it-IT"); i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); recognizer.startListening(i); }
    private void sendMessage(String message) { if(message==null||message.trim().isEmpty())return; status.setText("ELABORAZIONE CLOUD..."); transcript.setText("Tu: "+message+"\n\nJARVIS: ..."); Executors.newSingleThreadExecutor().execute(()->{try{HttpURLConnection c=(HttpURLConnection)new URL(BACKEND_URL).openConnection();c.setRequestMethod("POST");c.setConnectTimeout(30000);c.setReadTimeout(60000);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");String body="{\"message\":\""+jsonEscape(message)+"\"}";try(OutputStream o=c.getOutputStream()){o.write(body.getBytes(StandardCharsets.UTF_8));}InputStream stream=c.getResponseCode()>=400?c.getErrorStream():c.getInputStream();String answer=extract(read(stream),"reply");runOnUiThread(()->{status.setText("ONLINE • CLOUD JARVIS");transcript.setText("Tu: "+message+"\n\nJARVIS: "+answer);playCloudVoice(answer);});}catch(Exception e){runOnUiThread(()->{status.setText("ERRORE CONNESSIONE");transcript.setText("Impossibile raggiungere il server: "+e.getMessage());});}}); }
    private void playCloudVoice(String text) { try { if(voicePlayer!=null){voicePlayer.release();voicePlayer=null;} String u="https://jarvis-cloud-assistant-4dsr.onrender.com/tts/audio?voice=it-male&text="+URLEncoder.encode(text,"UTF-8"); voicePlayer=new MediaPlayer(); voicePlayer.setDataSource(u); voicePlayer.setOnPreparedListener(MediaPlayer::start); voicePlayer.setOnErrorListener((p,w,e)->{if(speaker!=null)speaker.speak(text,TextToSpeech.QUEUE_FLUSH,null,"jarvis-fallback");return true;}); voicePlayer.prepareAsync(); } catch(Exception e) { if(speaker!=null)speaker.speak(text,TextToSpeech.QUEUE_FLUSH,null,"jarvis-fallback"); } }
    private static String read(InputStream s)throws IOException{if(s==null)return "";StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(s,StandardCharsets.UTF_8))){String x;while((x=r.readLine())!=null)b.append(x);}return b.toString();}
    private static String jsonEscape(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
    private static String extract(String json,String key){String m="\""+key+"\":";int p=json.indexOf(m);if(p<0)return json;p+=m.length();while(p<json.length()&&Character.isWhitespace(json.charAt(p)))p++;if(p<json.length()&&json.charAt(p)=='\"'){p++;StringBuilder b=new StringBuilder();boolean e=false;for(;p<json.length();p++){char ch=json.charAt(p);if(e){b.append(ch=='n'?'\n':ch);e=false;}else if(ch=='\\')e=true;else if(ch=='\"')break;else b.append(ch);}return b.toString();}return json.substring(p).split(",",2)[0];}
    @Override protected void onDestroy(){if(recognizer!=null)recognizer.destroy();if(voicePlayer!=null)voicePlayer.release();if(speaker!=null){speaker.stop();speaker.shutdown();}super.onDestroy();}
}
