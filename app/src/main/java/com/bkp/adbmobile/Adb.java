package com.bkp.adbmobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.NetworkInterface;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;

/** Operações ADB do app: pareamento, conexão e execução de comandos shell. */
public final class Adb {

    public static final String COMMAND = "settings put system eth_device_conn 2";
    public static final String READ_COMMAND = "settings get system eth_device_conn";

    static final String CHANNEL_ID = "pairing";
    static final int NOTIFICATION_ID = 1;
    static final String KEY_CODE = "pairing_code";

    private static final String PAIRING_SERVICE = "_adb-tls-pairing._tcp";

    /** Porta do serviço de pareamento deste aparelho, descoberta via mDNS (-1 = ainda não achou). */
    static volatile int pairingPort = -1;
    private static NsdManager.DiscoveryListener discoveryListener;

    private Adb() {
    }

    // ---------- Pareamento ----------

    /** Começa a procurar o serviço "Parear com código" da Depuração sem fio. */
    public static synchronized void startPairingDiscovery(Context context) {
        if (discoveryListener != null) return;
        final NsdManager nsd = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        pairingPort = -1;
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(NsdServiceInfo info) {
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override
                    public void onServiceResolved(NsdServiceInfo resolved) {
                        if (isLocal(resolved)) pairingPort = resolved.getPort();
                    }

                    @Override
                    public void onResolveFailed(NsdServiceInfo i, int errorCode) {
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }
        };
        nsd.discoverServices(PAIRING_SERVICE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    public static synchronized void stopPairingDiscovery(Context context) {
        if (discoveryListener == null) return;
        NsdManager nsd = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        try {
            nsd.stopServiceDiscovery(discoveryListener);
        } catch (Exception ignored) {
        }
        discoveryListener = null;
    }

    private static boolean isLocal(NsdServiceInfo info) {
        try {
            return info.getHost() != null && NetworkInterface.getByInetAddress(info.getHost()) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Pareia com o código de 6 dígitos. Se port <= 0, usa a porta descoberta via mDNS. */
    public static void pair(Context context, int port, String code) throws Exception {
        if (port <= 0) {
            for (int i = 0; i < 20 && pairingPort <= 0; i++) Thread.sleep(500);
            port = pairingPort;
        }
        if (port <= 0) {
            throw new IOException("Serviço de pareamento não encontrado. Deixe aberta a tela "
                    + "\"Parear dispositivo com código de pareamento\".");
        }
        if (!AdbManager.get(context).pair("127.0.0.1", port, code.trim())) {
            throw new IOException("Pareamento recusado (código errado ou expirado).");
        }
        stopPairingDiscovery(context);
    }

    /** Pareia com outro aparelho da rede (IP e porta mostrados em "Parear com código" nele). */
    public static void pairRemote(Context context, String host, int port, String code) throws Exception {
        AdbManager manager = AdbManager.create(context);
        try {
            if (!manager.pair(host, port, code.trim())) {
                throw new IOException("Pareamento recusado (código errado ou expirado).");
            }
        } finally {
            release(manager);
        }
    }

    /** Notificação com campo de resposta para digitar o código sem sair das Configurações. */
    public static void showPairingNotification(Context context, String text, boolean withInput) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Pareamento ADB",
                    NotificationManager.IMPORTANCE_HIGH));
            builder = new Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH);
        }
        builder.setSmallIcon(R.drawable.ic_terminal)
                .setColor(0xFF0B3C6E)
                .setContentTitle("ADB Mobile - pareamento")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(false);

        if (withInput && Build.VERSION.SDK_INT >= 24) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(context, 0,
                    new Intent(context, PairingReceiver.class), flags);
            RemoteInput input = new RemoteInput.Builder(KEY_CODE).setLabel("Código de pareamento").build();
            builder.addAction(new Notification.Action.Builder(null, "Digitar código", pi)
                    .addRemoteInput(input).build());
        }
        nm.notify(NOTIFICATION_ID, builder.build());
    }

    // ---------- Execução ----------

    /** Executa um comando como usuário shell pelo ADB local e retorna a saída. */
    public static String shell(Context context, String command) throws Exception {
        AdbManager manager = AdbManager.get(context);
        if (!manager.isConnected()) {
            ensureWirelessDebuggingOn(context);
            if (!manager.autoConnect(context, 15000)) {
                throw new IOException("Não foi possível conectar ao ADB. Verifique se a "
                        + "\"Depuração sem fio\" está ativada e o Wi-Fi conectado.");
            }
        }
        return run(manager, command);
    }

    /**
     * Conecta em outro aparelho (host:port), executa os comandos em sequência e desconecta.
     * Funciona com a Depuração sem fio (Android 11+, já pareado) e com "adb tcpip 5555"
     * (nesse caso o alvo pede uma vez para autorizar a conexão).
     */
    public static String[] remoteShell(Context context, String host, int port, String... commands)
            throws Exception {
        AdbManager manager = AdbManager.create(context);
        try {
            // Tempo para a pessoa aceitar o aviso "Permitir depuração?" no aparelho alvo.
            manager.setTimeout(60, TimeUnit.SECONDS);
            String unreachable = "Sem resposta de " + host + ":" + port + ". Confira o IP/porta (a porta "
                    + "muda quando a depuração reinicia), se o aparelho está ligado e na mesma rede, "
                    + "e se a conexão foi autorizada na tela dele.";
            boolean connected;
            try {
                connected = manager.connect(host, port);
            } catch (AdbPairingRequiredException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(unreachable, e);
            }
            if (!connected) throw new IOException(unreachable);
            String[] results = new String[commands.length];
            for (int i = 0; i < commands.length; i++) results[i] = run(manager, commands[i]);
            return results;
        } finally {
            release(manager);
        }
    }

    /**
     * Encerra uma conexão remota. Não usa close(): ele tenta destruir a chave privada (compartilhada
     * entre conexões) e lança DestroyFailedException, mascarando o erro original.
     */
    private static void release(AdbManager manager) {
        try {
            manager.disconnect();
        } catch (Exception ignored) {
        }
    }

    /** Mensagem legível de uma exceção (algumas vêm sem texto). */
    public static String describe(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static String run(AdbManager manager, String command) throws Exception {
        AdbStream stream = manager.openStream("shell:" + command);
        try {
            InputStream in = stream.openInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            try {
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (IOException e) {
                // O libadb lança "Stream closed" quando o comando termina, em vez de retornar -1.
                if (!stream.isClosed()) throw e;
            }
            return out.toString("UTF-8").trim();
        } finally {
            stream.close();
        }
    }

    /** Executa via root (su), para aparelhos que têm. Lança exceção se não houver su. */
    public static String su(String command) throws Exception {
        Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        InputStream in = p.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        int code = p.waitFor();
        if (code != 0) throw new IOException("su saiu com código " + code + ": " + out.toString().trim());
        return out.toString("UTF-8").trim();
    }

    public static boolean hasSecureSettings(Context context) {
        return context.checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Se o app já tem WRITE_SECURE_SETTINGS, religa a Depuração sem fio sozinho. */
    @SuppressLint("MissingPermission")
    private static void ensureWirelessDebuggingOn(Context context) throws InterruptedException {
        if (Build.VERSION.SDK_INT < 30 || !hasSecureSettings(context)) return;
        if (Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0) == 1) return;
        Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", 1);
        Thread.sleep(3000);
    }

    /** Concede WRITE_SECURE_SETTINGS ao próprio app (precisa de conexão ADB ativa). */
    public static void grantSelfSecureSettings(Context context) {
        if (hasSecureSettings(context)) return;
        try {
            shell(context, "pm grant " + context.getPackageName() + " "
                    + Manifest.permission.WRITE_SECURE_SETTINGS);
        } catch (Exception ignored) {
        }
    }
}
