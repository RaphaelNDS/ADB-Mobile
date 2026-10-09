package com.bkp.adbmobile;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Recebe o código digitado na notificação e faz o pareamento. */
public class PairingReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        final CharSequence code = results == null ? null : results.getCharSequence(Adb.KEY_CODE);
        if (code == null || code.toString().trim().isEmpty()) return;

        final PendingResult pending = goAsync();
        Adb.showPairingNotification(context, "Pareando...", false);
        new Thread(() -> {
            try {
                Adb.pair(context, -1, code.toString());
                Adb.showPairingNotification(context,
                        "Pareado com sucesso! Volte ao ADB Mobile e toque em \"Executar comando\".", false);
            } catch (Exception e) {
                Adb.showPairingNotification(context, "Falhou: " + Adb.describe(e)
                        + "\nGere um novo código e tente de novo.", true);
            } finally {
                pending.finish();
            }
        }).start();
    }
}
