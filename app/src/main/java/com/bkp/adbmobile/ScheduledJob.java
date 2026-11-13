package com.bkp.adbmobile;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.muntashirakon.adb.AdbPairingRequiredException;

/** Roda o comando agendado (ver {@link Schedule}) e já agenda o dia seguinte. */
public class ScheduledJob extends JobService {

    private static final String CHANNEL_ID = "agenda";
    private static final int NOTIFICATION_ID = 4;

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try {
                runAll(this);
            } finally {
                Schedule.scheduleNext(this);
                jobFinished(params, false);
            }
        }, "agenda").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // O próximo dia já é agendado no fim do runAll; não repete este.
        return false;
    }

    /** Roda em todos os alvos, em sequência; cada resultado vai para o Registro. */
    static void runAll(Context c) {
        SharedPreferences p = Schedule.prefs(c);
        if (!p.getBoolean("on", false)) return;
        String label = p.getString("label", "");
        String cmd = p.getString("command", "");
        String stamp = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date());
        PendingLog.add(c, "[agenda " + stamp + "] " + label);
        int ok = 0, total = 0;
        if (p.getBoolean("local", true)) {
            total = 1;
            if (runOn(c, null, null, cmd)) ok++;
        } else {
            final SharedPreferences alvo = c.getSharedPreferences("alvo", Context.MODE_PRIVATE);
            List<Devices.Device> saved = Devices.load(alvo);
            for (String host : p.getString("hosts", "").split(",")) {
                if (host.isEmpty()) continue;
                total++;
                Devices.Device d = Devices.find(saved, host);
                if (d == null) {
                    PendingLog.add(c, "[agenda] [" + host + "] ERRO: não está mais em Aparelhos salvos.");
                    continue;
                }
                if (runOn(c, d, alvo, cmd)) ok++;
            }
        }
        notify(c, label + ": " + ok + " de " + total + (total == 1 ? " aparelho" : " aparelhos") + " OK", ok == total);
    }

    private static boolean runOn(Context c, final Devices.Device d, final SharedPreferences alvo, String cmd) {
        String prefix = "[agenda] " + (d == null ? "" : "[" + d.label() + "] ");
        try {
            Adb.Shell shell = d == null ? Adb.openLocal(c)
                    : Adb.openRemote(c, d.host, d.port, newPort -> Devices.updatePort(alvo, d.host, newPort));
            String out;
            try {
                out = shell.run(cmd);
            } finally {
                shell.close();
            }
            String failure = AdbCommands.failure(out);
            if (failure != null) {
                PendingLog.add(c, prefix + "ERRO: " + failure);
                return false;
            }
            String text = out.trim();
            if (text.length() > 4000) text = text.substring(0, 4000) + "\n... (saída cortada)";
            PendingLog.add(c, prefix + "OK" + (text.isEmpty() ? "" : "\n" + text));
            return true;
        } catch (AdbPairingRequiredException e) {
            PendingLog.add(c, prefix + "ERRO: " + MainActivity.NEEDS_PAIRING);
        } catch (Exception e) {
            PendingLog.add(c, prefix + "ERRO: " + DeviceRunner.errorText(e));
        }
        return false;
    }

    private static void notify(Context c, String text, boolean ok) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        Notification n = Notifications.builder(c, CHANNEL_ID, "Comando agendado", false)
                .setSmallIcon(R.drawable.ic_terminal)
                .setColor(ok ? 0xFF1E7F4F : 0xFFB42318)
                .setContentTitle("ADB Mobile - comando agendado")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 3, new Intent(c, MainActivity.class), immutable))
                .build();
        nm.notify(NOTIFICATION_ID, n);
    }
}
