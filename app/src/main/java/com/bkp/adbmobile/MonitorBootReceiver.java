package com.bkp.adbmobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Retoma o log automático depois de ligar o aparelho ou de atualizar o app, se ele estava ligado.
 * Esses dois eventos estão entre as exceções em que o Android permite iniciar um serviço em
 * primeiro plano a partir do segundo plano.
 */
public class MonitorBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            try {
                LogMonitorService.resume(context);
            } catch (Exception ignored) {
                // Se o Android recusar, o monitoramento volta quando a pessoa abrir o app.
            }
        }
    }
}
