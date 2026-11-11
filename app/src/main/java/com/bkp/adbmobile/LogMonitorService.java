package com.bkp.adbmobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import io.github.muntashirakon.adb.AdbPairingRequiredException;

/**
 * Fica observando o app em primeiro plano no aparelho alvo. Quando o app monitorado abre, reinicia
 * ou já está aberto ao iniciar, salva o logcat completo como .txt na pasta escolhida. Roda em
 * primeiro plano (com notificação) para continuar funcionando enquanto a pessoa usa outro app.
 *
 * A configuração fica nas preferências: o serviço volta sozinho depois de reiniciar o aparelho,
 * de atualizar o app ou de o Android encerrá-lo (ver {@link MonitorBootReceiver}).
 */
public class LogMonitorService extends Service {

    private static final String ACTION_STOP = "com.bkp.adbmobile.STOP_MONITOR";
    private static final String ACTION_RESUME = "com.bkp.adbmobile.RESUME_MONITOR";

    // Preferências (mesmo arquivo da tela). "monitorActive" = o usuário deixou o monitoramento ligado.
    static final String PREFS = "alvo";
    static final String KEY_ACTIVE = "monitorActive";
    static final String KEY_HOST = "monitorHost";
    static final String KEY_PORT = "monitorPort";
    static final String KEY_PACKAGE = "monitorPackage";
    static final String KEY_FOLDER = "logFolder";

    private static final String CHANNEL_ID = "monitor";
    private static final String ALERT_CHANNEL_ID = "monitor_alerts";
    private static final int NOTIFICATION_ID = 2;
    private static final int ALERT_NOTIFICATION_ID = 3;
    private static final long POLL_MS = 2000;
    /** Espera após o app abrir, para o log incluir a inicialização dele. */
    private static final long CAPTURE_DELAY_MS = 5000;
    private static final long RETRY_MS = 5000;
    /** Quantos logs automáticos do app ficam na pasta; os mais antigos são apagados. */
    private static final int MAX_LOGS = 10;

    /** Recebe os eventos do monitoramento na thread principal (a tela, quando aberta). */
    interface Listener {
        void onMonitorEvent(String message, boolean running);
    }

    static volatile boolean running;
    static volatile Listener listener;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopping;
    private Thread worker;
    private SharedPreferences prefs;
    private String host;
    private volatile int port;
    private String pkg;
    private Uri folder;
    private int captures;

    /** Liga o monitoramento e guarda a configuração para ele voltar sozinho depois. */
    static void start(Context context, String host, int port, String pkg, Uri folder) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_ACTIVE, true)
                .putString(KEY_HOST, host)
                .putInt(KEY_PORT, port)
                .putString(KEY_PACKAGE, pkg)
                .putString(KEY_FOLDER, folder.toString())
                .apply();
        launch(context);
    }

    /** Retoma o monitoramento salvo (ao ligar o aparelho ou depois de atualizar o app). */
    static void resume(Context context) {
        if (isActive(context)) launch(context, ACTION_RESUME);
    }

    private static void launch(Context context) {
        launch(context, null);
    }

    private static void launch(Context context, String action) {
        Intent intent = new Intent(context, LogMonitorService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    /** Desliga o monitoramento: ele não volta mais sozinho até ser iniciado de novo. */
    static void stop(Context context) {
        markInactive(context);
        context.stopService(new Intent(context, LogMonitorService.class));
    }

    static boolean isActive(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ACTIVE, false);
    }

    private static void markInactive(Context context) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // startForegroundService exige startForeground logo, mesmo que o serviço vá parar em seguida.
        if (!enterForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            markInactive(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker != null) return START_STICKY;

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        host = prefs.getString(KEY_HOST, null);
        port = prefs.getInt(KEY_PORT, 0);
        pkg = prefs.getString(KEY_PACKAGE, null);
        String folderText = prefs.getString(KEY_FOLDER, null);
        if (!prefs.getBoolean(KEY_ACTIVE, false) || pkg == null || folderText == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        folder = Uri.parse(folderText);

        running = true;
        // Sem intent = o Android recriou o serviço; RESUME = aparelho ligado ou app atualizado.
        boolean restored = intent == null || ACTION_RESUME.equals(intent.getAction());
        event((restored ? "Monitoramento retomado: " : "Monitorando ") + pkg + " em " + targetLabel() + ".");
        worker = new Thread(this::loop, "log-monitor");
        worker.start();
        // Se o Android encerrar o serviço, ele é recriado e lê a configuração salva.
        return START_STICKY;
    }

    private boolean enterForeground() {
        Notification n = notification("Iniciando o monitoramento...");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            return true;
        } catch (Exception e) {
            // Android 12+ pode recusar iniciar em segundo plano fora das exceções (boot, atualização).
            return false;
        }
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

    /** A porta antiga não respondeu e a busca na rede achou a atual: passa a usar e salva. */
    private void onPortChanged(int newPort) {
        int old = port;
        port = newPort;
        prefs.edit().putInt(KEY_PORT, newPort).apply();
        // Mantém a lista de aparelhos da tela coerente com o monitoramento.
        if (host != null) Devices.updatePort(prefs, host, newPort);
        event("Porta de conexão mudou: " + old + " → " + newPort + " (encontrada na rede).");
    }

    /**
     * Por que o log vai ser salvo, para o Registro. "reiniciou" só quando havia um processo e o
     * PID mudou; se não havia processo, o app foi "aberto" (veio para a frente) ou "iniciou de
     * novo" (o processo voltou sem mudar o que está na tela, ex.: travou e reabriu sozinho).
     * Só muda o texto: quando salvar continua decidido no loop().
     */
    static String captureReason(boolean opened, String lastPid, String pid) {
        if (lastPid != null && !lastPid.isEmpty() && !pid.isEmpty() && !pid.equals(lastPid)) {
            return "reiniciou (PID " + lastPid + " → " + pid + ")";
        }
        return opened ? "aberto" : "iniciou de novo (PID " + pid + ")";
    }

    private void loop() {
        Adb.Shell shell = null;
        String last = null; // null = primeiro ciclo
        String lastPid = null;
        while (!stopping) {
            try {
                if (shell == null) {
                    shell = host != null ? Adb.openRemote(this, host, port, this::onPortChanged)
                            : Adb.openLocal(this);
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
                        event(pkg + " " + captureReason(opened, lastPid, pid) + "; capturando log em "
                                + CAPTURE_DELAY_MS / 1000 + " s...");
                        Thread.sleep(CAPTURE_DELAY_MS);
                    }
                    capture(shell);
                    // O PID lido antes pode estar vazio ou velho: ao abrir, a tela aparece antes do
                    // processo subir. Sem reler, o ciclo seguinte via "iniciou de novo" e salvava
                    // um segundo log do mesmo processo (visto no tablet: PID - e depois 19304).
                    pid = Adb.foregroundAndPid(shell, pkg)[1];
                }
                last = current;
                lastPid = pid;
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                break;
            } catch (AdbPairingRequiredException e) {
                // Tentar de novo não resolve: precisa de um novo código de pareamento.
                event("O aparelho alvo exige pareamento. Faça a etapa 1 e inicie o monitoramento de novo.");
                markInactive(this);
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

    /** Até onde o log já foi verificado neste alvo, para não acusar de novo um crash antigo. */
    private String sinceKey() {
        return "lastLogTime|" + (host != null ? host : "local") + "|" + pkg;
    }

    private void capture(Adb.Shell shell) throws Exception {
        String name = DeviceLog.fileName(pkg);
        Uri file = DeviceLog.createInFolder(getContentResolver(), folder, name);
        DeviceLog.Result log;
        try {
            log = DeviceLog.save(shell, getContentResolver(), file, targetLabel(), "App monitorado: " + pkg,
                    pkg, prefs.getString(sinceKey(), null));
        } catch (Exception e) {
            DeviceLog.delete(getContentResolver(), file);
            throw e;
        }
        if (log.lastTime != null) prefs.edit().putString(sinceKey(), log.lastTime).apply();
        captures++;

        String problems = log.problems();
        if (problems != null) {
            // Marca no nome do arquivo para achar fácil entre os 10 guardados.
            String flagged = name.replace(".txt", "_" + (log.crashes > 0 ? "CRASH" : "ANR") + ".txt");
            if (DeviceLog.rename(getContentResolver(), file, flagged)) name = flagged;
            alert(pkg + ": " + problems + " desde o log anterior. Arquivo: " + name);
        }

        int removed = DeviceLog.prune(getContentResolver(), folder, DeviceLog.filePrefix(pkg), MAX_LOGS);
        event("Log salvo (" + log.lines + " linhas" + (problems != null ? ", " + problems : "") + "): " + name
                + (removed > 0 ? " (" + removed + " antigo(s) apagado(s); mantidos os últimos " + MAX_LOGS + ")" : ""));
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

    /** Notificação de destaque (som/pop-up) para crash ou ANR do app monitorado. */
    private void alert(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(ALERT_CHANNEL_ID, "Crashes do app monitorado",
                    NotificationManager.IMPORTANCE_HIGH));
            builder = new Notification.Builder(this, ALERT_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL);
        }
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        builder.setSmallIcon(R.drawable.ic_terminal)
                .setColor(0xFFB3261E)
                .setContentTitle("ADB Mobile - erro no app monitorado")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), immutable));
        // Um alerta por captura (não substitui o anterior).
        nm.notify(ALERT_NOTIFICATION_ID + captures, builder.build());
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
