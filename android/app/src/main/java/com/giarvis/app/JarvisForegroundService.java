package com.giarvis.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.BatteryManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import androidx.core.app.NotificationCompat;

/** Stato persistente JARVIS. L'ascolto continuo viene abilitato solo dall'utente. */
public class JarvisForegroundService extends Service {
    public static final String ACTION_START = "com.giarvis.app.START";
    public static final String ACTION_STOP = "com.giarvis.app.STOP";
    public static final String ACTION_LISTEN = "com.giarvis.app.LISTEN";
    private static final String CHANNEL = "jarvis_core";
    private static final int NOTIFICATION_ID = 7401;

    @Override public void onCreate() { super.onCreate(); createChannel(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopForeground(true); stopSelf(); return START_NOT_STICKY; }
        if (intent != null && ACTION_LISTEN.equals(intent.getAction())) {
            Intent open = new Intent(this, MainActivity.class).setAction(ACTION_LISTEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(open);
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (SecurityException denied) {
            // Android 14+ può rifiutare un servizio microphone prima del consenso.
            stopSelf();
            return START_NOT_STICKY;
        } catch (RuntimeException invalidState) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent listenPending = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class).setAction(ACTION_LISTEN).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopPending = PendingIntent.getService(this, 3, new Intent(this, JarvisForegroundService.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(com.giarvis.app.R.mipmap.ic_launcher)
                .setContentTitle("J.A.R.V.I.S. // CORE ONLINE")
                .setContentText("Batteria " + batteryPercent() + "% · " + batteryCurrent() + " · " + networkState())
                .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).setContentIntent(openPending)
                .addAction(0, "ASCOLTA", listenPending).addAction(0, "PAUSA", stopPending)
                .build();
    }

    private String batteryPercent() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int value = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return value < 0 ? "N/D" : String.valueOf(value);
    }

    private String batteryCurrent() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (bm == null || Build.VERSION.SDK_INT < 21) return "Consumo N/D";
        int microamps = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (microamps == Integer.MIN_VALUE || microamps == 0) return "Consumo N/D";
        return "Consumo " + Math.abs(microamps) / 1000 + " mA";
    }

    private String networkState() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ? "ONLINE" : "OFFLINE";
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "J.A.R.V.I.S. Core", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Stato del servizio J.A.R.V.I.S.");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
