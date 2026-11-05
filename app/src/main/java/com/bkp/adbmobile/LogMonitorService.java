package com.bkp.adbmobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import io.github.muntashirakon.adb.AdbPairingRequiredException;

/**
 * Fica observando o app em primeiro plano no aparelho alvo. Quando o app monitorado é aberto,
 * salva o logcat completo como .txt na pasta escolhida. Roda em primeiro plano (com notificação)
 * para continuar funcionando enquanto a pessoa usa outro app.
 */
public class LogMonitorService extends Service {

    static final String EXTRA_HOST = "host";
    static final String EXTRA_PORT = "port";
    static final String EXTRA_PACKAGE = "package";
    static final String EXTRA_FOLDER = "folder";
    private static final String ACTION_STOP = "com.bkp.adbmobile.STOP_MONITOR";

    private static final String CHANNEL_ID = "monitor";
    private static final int NOTIFICATION_ID = 2;
    private static final long POLL_MS = 2000;
    /** Espera após o app abrir, para o log incluir a inicialização dele. */
    private static final long CAPTURE_DELAY_MS = 5000;
    private static final long RETRY_MS = 5000;

    /** Recebe os eventos do monitoramento na thread principal (a tela, quando aberta). */
    interface Listener {
        void onMonitorEvent(String message, boolean running);
    }

    static volatile boolean running;
    static volatile Listener listener;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopping;
    private Thread worker;
    private String host;
    private int port;
    private String pkg;
    private Uri folder;
    private int captures;

    static void start(Context context, String host, int port, String pkg, Uri folder) {
        Intent intent = new Intent(context, LogMonitorService.class)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_PACKAGE, pkg)
                .putExtra(EXTRA_FOLDER, folder.toString());
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, LogMonitorService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker != null) return START_NOT_STICKY;

        host = intent.getStringExtra(EXTRA_HOST);
        port = intent.getIntExtra(EXTRA_PORT, 0);
        pkg = intent.getStringExtra(EXTRA_PACKAGE);
        folder = Uri.parse(intent.getStringExtra(EXTRA_FOLDER));

        Notification n = notification("Aguardando " + pkg + " abrir em " + targetLabel() + ".");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        running = true;
        event("Monitorando " + pkg + " em " + targetLabel() + ".");
        worker = new Thread(this::loop, "log-monitor");
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        if (worker != null) worker.interrupt();
        running = false;
        event("Monitoramento parado (" + captures + " log(s) salvos).");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private String targetLabel() {
        return host != null ? host + ":" + port : "este aparelho";
    }

    private void loop() {
        Adb.Shell shell = null;
        String last = null; // null = primeiro ciclo
        String lastPid = null;
        while (!stopping) {
            try {
                if (shell == null) {
                    shell = host != null ? Adb.openRemote(this, host, port) : Adb.openLocal(this);
                }
                String[] state = Adb.foregroundAndPid(shell, pkg);
                String current = state[0];
                String pid = state[1];
                if (!current.equals(last)) {
                    event("Em primeiro plano: " + (current.isEmpty() ? "(não identificado)" : current));
                }
                if (lastPid != null && !lastPid.isEmpty() && pid.isEmpty()) {
                    event(pkg + " foi encerrado (PID " + lastPid + ").");
                }

                boolean opened = pkg.equals(current) && !pkg.equals(last);
                // PID novo com o app já rodando antes = reinício (ex.: travou e voltou sozinho).
                boolean restarted = !pid.isEmpty() && lastPid != null && !pid.equals(lastPid);
                if (opened || restarted) {
                    if (last == null) {
                        // Já estava aberto quando o monitoramento começou (comum em app de quiosque).
                        event(pkg + " já está aberto; capturando log agora...");
                    } else {
                        event(pkg + (restarted ? " reiniciou (PID " + (lastPid.isEmpty() ? "-" : lastPid)
                                + " → " + pid + ")" : " aberto") + "; capturando log em "
                                + CAPTURE_DELAY_MS / 1000 + " s...");
                        Thread.sleep(CAPTURE_DELAY_MS);
                    }
                    capture(shell);
                }
                last = current;
                lastPid = pid;
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                break;
            } catch (AdbPairingRequiredException e) {
                // Tentar de novo não resolve: precisa de um novo código de pareamento.
                event("O aparelho alvo exige pareamento. Faça a etapa 1 e inicie o monitoramento de novo.");
                main.post(this::stopSelf);
                break;
            } catch (Exception e) {
                if (stopping) break;
                event("Falha no monitoramento: " + Adb.describe(e) + " Tentando de novo...");
                if (shell != null) shell.close();
                shell = null;
                try {
                    Thread.sleep(RETRY_MS);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
        if (shell != null) shell.close();
    }

    private void capture(Adb.Shell shell) throws Exception {
        String name = DeviceLog.fileName(pkg);
        Uri file = DeviceLog.createInFolder(getContentResolver(), folder, name);
        long lines;
        try {
            lines = DeviceLog.save(shell, getContentResolver(), file, targetLabel(), "App monitorado: " + pkg);
        } catch (Exception e) {
            DeviceLog.delete(getContentResolver(), file);
            throw e;
        }
        captures++;
        event("Log salvo (" + lines + " linhas): " + name);
    }

    /** Atualiza a notificação e avisa a tela (se estiver aberta). */
    private void event(final String message) {
        if (!stopping) {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(NOTIFICATION_ID, notification(message));
        }
        final boolean isRunning = running;
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onMonitorEvent(message, isRunning);
        });
    }

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Log automático",
                    NotificationManager.IMPORTANCE_LOW));
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), immutable);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, LogMonitorService.class).setAction(ACTION_STOP), immutable);
        builder.setSmallIcon(R.drawable.ic_terminal)
                .setColor(0xFF0B3C6E)
                .setContentTitle("ADB Mobile - log automático (" + captures + ")")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(0, "Parar", stop).build());
        return builder.build();
    }
}
