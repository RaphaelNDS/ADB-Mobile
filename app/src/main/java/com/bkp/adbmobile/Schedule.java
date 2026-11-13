package com.bkp.adbmobile;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Button;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * "Agendar comando diário": um comando que roda sozinho todo dia por volta de um horário, neste
 * aparelho ou nos aparelhos marcados no momento de agendar. Usa o JobScheduler do Android (sem
 * permissão extra; continua depois de reiniciar; o Android pode atrasar alguns minutos para
 * economizar bateria). O resultado vai para o Registro e para uma notificação.
 */
final class Schedule {

    static final int JOB_ID = 4201;
    /** Margem que o Android pode usar depois do horário pedido. */
    static final long WINDOW_MS = 10 * 60 * 1000;

    private static final String PREFS = "agenda";

    private final MainActivity a;
    private final Button button;

    Schedule(MainActivity activity) {
        a = activity;
        button = a.findViewById(R.id.schedule);
        button.setOnClickListener(v -> open());
        updateButton();
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Milissegundos de {@code now} até a próxima vez que der {@code hour}:{@code minute}. */
    static long nextDelay(Calendar now, int hour, int minute) {
        Calendar next = (Calendar) now.clone();
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, minute);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (!next.after(now)) next.add(Calendar.DAY_OF_MONTH, 1);
        return next.getTimeInMillis() - now.getTimeInMillis();
    }

    static String time(int hour, int minute) {
        return String.format(Locale.US, "%02d:%02d", hour, minute);
    }

    /** Agenda a próxima execução (ou cancela, se desligado). */
    static void scheduleNext(Context c) {
        SharedPreferences p = prefs(c);
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (!p.getBoolean("on", false)) {
            js.cancel(JOB_ID);
            return;
        }
        long delay = nextDelay(Calendar.getInstance(), p.getInt("hour", 8), p.getInt("minute", 0));
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, ScheduledJob.class))
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + WINDOW_MS)
                .setPersisted(true)
                .build());
    }

    /** "Kiosk: status às 08:00 em 3 aparelhos" (ou null se não há agendamento). */
    static String describe(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("on", false)) return null;
        String hosts = p.getString("hosts", "");
        int n = hosts.isEmpty() ? 0 : hosts.split(",").length;
        String where = p.getBoolean("local", true) ? "neste aparelho" : n == 1 ? "em " + p.getString("where", hosts)
                : "em " + n + " aparelhos";
        return p.getString("label", "") + " às " + time(p.getInt("hour", 8), p.getInt("minute", 0)) + " " + where;
    }

    private void updateButton() {
        String d = describe(a);
        button.setText(d == null ? "Agendar comando diário" : "Agendado: " + d);
    }

    private void open() {
        String d = describe(a);
        if (d == null) {
            chooseTime();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle("Comando agendado")
                .setMessage("Todo dia: " + d + ".\n\nadb shell " + prefs(a).getString("command", "")
                        + "\n\nO resultado aparece no Registro e numa notificação.")
                .setPositiveButton("Trocar pelo comando atual", (x, w) -> chooseTime())
                .setNegativeButton("Remover", (x, w) -> {
                    prefs(a).edit().putBoolean("on", false).apply();
                    scheduleNext(a);
                    a.append("Agendamento removido.");
                    a.setStatus(MainActivity.Status.INFO, "Agendamento removido.");
                    updateButton();
                })
                .setNeutralButton("Fechar", null)
                .show();
    }

    /** Usa o comando do campo e os aparelhos marcados agora. */
    private void chooseTime() {
        final String[] cmd = a.commands.currentForSchedule();
        if (cmd == null) return;
        final boolean local = !a.isRemote();
        final List<Devices.Device> targets = local ? new ArrayList<>() : a.devices.selected();
        if (targets == null) return;
        SharedPreferences p = prefs(a);
        new TimePickerDialog(a, (picker, hour, minute) -> confirm(cmd, local, targets, hour, minute),
                p.getInt("hour", 8), p.getInt("minute", 0), true).show();
    }

    private void confirm(final String[] cmd, final boolean local, final List<Devices.Device> targets,
                         final int hour, final int minute) {
        final String where = local ? "neste aparelho" : targets.size() == 1 ? "em " + targets.get(0).label()
                : "em " + targets.size() + " aparelhos";
        new AlertDialog.Builder(a)
                .setTitle("Agendar comando diário")
                .setMessage("Rodar \"" + cmd[0] + "\" todo dia por volta das " + time(hour, minute) + " " + where
                        + ".\n\nadb shell " + cmd[1]
                        + "\n\nO Android pode atrasar alguns minutos para economizar bateria.")
                .setPositiveButton("Agendar", (x, w) -> {
                    StringBuilder hosts = new StringBuilder();
                    for (Devices.Device d : targets) hosts.append(hosts.length() > 0 ? "," : "").append(d.host);
                    prefs(a).edit().putBoolean("on", true).putString("label", cmd[0]).putString("command", cmd[1])
                            .putBoolean("local", local).putString("hosts", hosts.toString())
                            .putString("where", targets.size() == 1 ? targets.get(0).label() : "")
                            .putInt("hour", hour).putInt("minute", minute).apply();
                    scheduleNext(a);
                    String d = describe(a);
                    a.append("Agendado: " + d + ".");
                    a.setStatus(MainActivity.Status.SUCCESS, "Agendado: " + d + ".");
                    updateButton();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
