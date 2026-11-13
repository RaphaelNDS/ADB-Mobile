package com.bkp.adbmobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;

/**
 * Criação das notificações nas duas formas do Android: com canal (8+) e a antiga (5 a 7.1), que
 * é obsoleta mas é a única que existe nessas versões (minSdk 21).
 */
final class Notifications {

    private Notifications() {
    }

    /** Builder no canal {@code channelId} (criado se preciso); {@code high} = som e pop-up. */
    static Notification.Builder builder(Context context, String channelId, String channelName, boolean high) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(channelId, channelName,
                    high ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_LOW));
            return new Notification.Builder(context, channelId);
        }
        return legacyBuilder(context, high);
    }

    @SuppressWarnings("deprecation") // Android 5 a 7.1: sem canais, a prioridade vai na notificação.
    private static Notification.Builder legacyBuilder(Context context, boolean high) {
        Notification.Builder b = new Notification.Builder(context);
        if (high) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        return b;
    }

    /** Botão de ação sem ícone. */
    static Notification.Action.Builder action(String title, PendingIntent intent) {
        if (Build.VERSION.SDK_INT >= 23) return new Notification.Action.Builder(null, title, intent);
        return legacyAction(title, intent);
    }

    @SuppressWarnings("deprecation") // Android 5 e 5.1: só existe o construtor com ícone int.
    private static Notification.Action.Builder legacyAction(String title, PendingIntent intent) {
        return new Notification.Action.Builder(0, title, intent);
    }
}
