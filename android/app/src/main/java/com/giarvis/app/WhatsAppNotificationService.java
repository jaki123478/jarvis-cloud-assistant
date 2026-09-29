package com.giarvis.app;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/** Legge gli eventi WhatsApp e fornisce riepiloghi vocali personalizzati. */
public class WhatsAppNotificationService extends NotificationListenerService {
    private static final String TAG = "JarvisNotifications";
    private static final String WHATSAPP = "com.whatsapp";
    private static final List<String> recentSenders = new ArrayList<>();

    public static synchronized String getSummary(Context context) {
        String userName = "";
        if (context != null) {
            userName = context.getSharedPreferences("jarvis_profile", MODE_PRIVATE).getString("user_name", "").trim();
        }
        String address = userName.isEmpty() ? "signore" : userName;

        if (recentSenders.isEmpty()) {
            return "Non ci sono nuove notifiche WhatsApp, " + address + ".";
        }
        return "Ci sono " + recentSenders.size() + " notifiche WhatsApp, " + address + ". Mittenti: " + String.join(", ", recentSenders) + ".";
    }

    public static synchronized String getSummary() {
        return getSummary(null);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "Notification listener connected");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.w(TAG, "Notification listener disconnected; requesting rebind");
        try {
            requestRebind(new ComponentName(this, WhatsAppNotificationService.class));
        } catch (Exception ignored) {}
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!WHATSAPP.equals(sbn.getPackageName())) return;
        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) return;
        Bundle extras = notification.extras;
        String sender = extras.getString(Notification.EXTRA_TITLE, "").trim();
        synchronized (recentSenders) {
            if (sender.isEmpty()) sender = "WhatsApp";
            recentSenders.remove(sender);
            recentSenders.add(0, sender);
            while (recentSenders.size() > 20) recentSenders.remove(recentSenders.size() - 1);
        }
        Intent event = new Intent("com.giarvis.WHATSAPP_NOTIFICATION");
        event.setPackage(getPackageName());
        event.putExtra("sender", sender);
        sendBroadcast(event);
    }
}
