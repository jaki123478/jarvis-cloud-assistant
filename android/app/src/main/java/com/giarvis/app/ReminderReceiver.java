package com.giarvis.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.app.NotificationCompat;

public class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String text = intent == null ? "Hai un promemoria da controllare." : intent.getStringExtra("text");
        if (text == null || text.trim().isEmpty()) text = "Hai un promemoria da controllare.";
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        String channel = "jarvis_reminders";
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(channel, "J.A.R.V.I.S. Promemoria", NotificationManager.IMPORTANCE_HIGH);
            c.enableVibration(true); manager.createNotificationChannel(c);
        }
        Intent open = new Intent(context, ReminderActivity.class).putExtra("text", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, 4001, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder n = new NotificationCompat.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("J.A.R.V.I.S. // TI STA CHIAMANDO").setContentText(text).setStyle(new NotificationCompat.BigTextStyle().bigText("J.A.R.V.I.S. ti sta chiamando per ricordarti: " + text)).setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_CALL).setFullScreenIntent(pending, true).setAutoCancel(false).setOngoing(true).setContentIntent(pending);
        manager.notify(4001, n.build());
    }
}
