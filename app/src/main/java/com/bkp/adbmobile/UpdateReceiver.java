package com.bkp.adbmobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * Resultado da instalação da atualização (PackageInstaller). Na primeira vez o Android pede a
 * confirmação da pessoa (STATUS_PENDING_USER_ACTION): abre a tela dele. Se der certo, o app é
 * reiniciado pelo próprio Android; se falhar, mostra o motivo.
 */
public class UpdateReceiver extends BroadcastReceiver {

    static final String ACTION = "com.bkp.adbmobile.UPDATE_RESULT";

    @SuppressWarnings("deprecation") // getParcelableExtra(nome, classe) só existe no Android 13+.
    private static Intent confirmIntent(Intent intent) {
        if (android.os.Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        return intent.getParcelableExtra(Intent.EXTRA_INTENT);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = confirmIntent(intent);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context, "Atualização não instalada"
                    + (message != null ? ": " + message : ".")
                    + (status == PackageInstaller.STATUS_FAILURE_ABORTED ? " (cancelada)" : ""), Toast.LENGTH_LONG).show();
        }
    }
}
