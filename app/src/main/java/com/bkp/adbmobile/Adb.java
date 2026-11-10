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
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.net.NetworkInterface;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;

/** Operações ADB do app: pareamento, conexão e execução de comandos shell. */
public final class Adb {

    public static final String COMMAND = "settings put system eth_device_conn 2";
    public static final String READ_COMMAND = "settings get system eth_device_conn";
    /** Despeja o logcat atual (todos os buffers principais) e termina. */
    public static final String LOG_COMMAND = "logcat -d -v threadtime -b main,system,crash";
    /** Cabeçalho do arquivo de log com a identificação do aparelho. */
    public static final String DEVICE_INFO_COMMAND = "echo \"Fabricante: $(getprop ro.product.manufacturer)\";"
            + " echo \"Modelo: $(getprop ro.product.model)\";"
            + " echo \"Android: $(getprop ro.build.version.release) (API $(getprop ro.build.version.sdk))\";"
            + " echo \"Build: $(getprop ro.build.display.id)\";"
            + " echo \"Serial: $(getprop ro.serialno)\"";

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
        return run(local(context), command);
    }

    /**
     * Conecta em outro aparelho (host:port), executa os comandos em sequência e desconecta.
     * Funciona com a Depuração sem fio (Android 11+, já pareado) e com "adb tcpip 5555"
     * (nesse caso o alvo pede uma vez para autorizar a conexão).
     */
    public static String[] remoteShell(Context context, String host, int port, PortListener onPortChanged,
                                       String... commands) throws Exception {
        Shell shell = openRemote(context, host, port, onPortChanged);
        try {
            String[] results = new String[commands.length];
            for (int i = 0; i < commands.length; i++) results[i] = shell.run(commands[i]);
            return results;
        } finally {
            shell.close();
        }
    }

    /** Sessão de shell aberta num aparelho, para rodar vários comandos sem reconectar. */
    public interface Shell {
        String run(String command) throws Exception;

        /** Grava a saída do comando em {@code out} conforme chega (para saídas grandes). */
        void stream(String command, OutputStream out) throws Exception;

        /** Instala o APK no aparelho (atualiza se já existir, mantendo os dados). Devolve a saída do pm. */
        String install(File apk) throws Exception;

        /** Copia o arquivo para {@code remotePath} no aparelho (como "adb push"), sobrescrevendo. */
        void push(File file, String remotePath) throws Exception;

        void close();
    }

    /** Avisada quando a porta informada não respondeu e outra porta do mesmo IP funcionou. */
    public interface PortListener {
        void onPortChanged(int newPort);
    }

    /**
     * Conecta em outro aparelho e mantém a conexão aberta até close(). Se a porta não responder
     * (ela muda quando a Depuração sem fio reinicia), procura na rede a porta atual daquele IP.
     */
    public static Shell openRemote(Context context, String host, int port, PortListener onPortChanged)
            throws Exception {
        String unreachable = "Sem resposta de " + host + ":" + port + ". Confira o IP/porta, se o "
                + "aparelho está ligado e na mesma rede, e se a conexão foi autorizada na tela dele.";
        AdbManager manager;
        try {
            manager = connect(context, host, port);
        } catch (AdbPairingRequiredException e) {
            throw e;
        } catch (Exception first) {
            manager = null;
            for (int candidate : discoverConnectPorts(context, host, 5000)) {
                if (candidate == port) continue;
                try {
                    manager = connect(context, host, candidate);
                    if (onPortChanged != null) onPortChanged.onPortChanged(candidate);
                    break;
                } catch (AdbPairingRequiredException e) {
                    throw e;
                } catch (Exception ignored) {
                    // Anúncio antigo na rede; tenta o próximo.
                }
            }
            if (manager == null) throw new IOException(unreachable, first);
        }
        return wrap(manager);
    }

    /** Uma tentativa de conexão; em falha, libera o cliente e repassa o erro. */
    private static AdbManager connect(Context context, String host, int port) throws Exception {
        AdbManager manager = AdbManager.create(context);
        // Tempo para a pessoa aceitar o aviso "Permitir depuração?" no aparelho alvo.
        manager.setTimeout(60, TimeUnit.SECONDS);
        try {
            if (manager.connect(host, port)) return manager;
        } catch (Exception e) {
            release(manager);
            throw e;
        }
        release(manager);
        throw new IOException("Conexão recusada em " + host + ":" + port);
    }

    private static final String CONNECT_SERVICE = "_adb-tls-connect._tcp";

    /**
     * Portas de conexão que a Depuração sem fio anuncia na rede (mDNS) para {@code host}.
     * Pode vir mais de uma: a rede às vezes guarda anúncios antigos. Lista vazia se nada for achado
     * (Android 10 ou anterior não anuncia; "adb tcpip" também não).
     */
    public static List<Integer> discoverConnectPorts(Context context, final String host, long timeoutMs) {
        final List<Integer> ports = new ArrayList<>();
        final NsdManager nsd = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return ports;
        final LinkedBlockingQueue<NsdServiceInfo> found = new LinkedBlockingQueue<>();
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(NsdServiceInfo info) {
                found.add(info);
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
        try {
            nsd.discoverServices(CONNECT_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (Exception e) {
            return ports;
        }
        long end = System.currentTimeMillis() + timeoutMs;
        try {
            long left;
            while ((left = end - System.currentTimeMillis()) > 0) {
                NsdServiceInfo info = found.poll(left, TimeUnit.MILLISECONDS);
                if (info == null) break;
                // resolveService aceita um pedido por vez: resolve em sequência.
                final CountDownLatch resolved = new CountDownLatch(1);
                final NsdServiceInfo[] result = new NsdServiceInfo[1];
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override
                    public void onServiceResolved(NsdServiceInfo s) {
                        result[0] = s;
                        resolved.countDown();
                    }

                    @Override
                    public void onResolveFailed(NsdServiceInfo s, int errorCode) {
                        resolved.countDown();
                    }
                });
                resolved.await(3, TimeUnit.SECONDS);
                NsdServiceInfo s = result[0];
                if (s != null && s.getHost() != null && host.equals(s.getHost().getHostAddress())
                        && !ports.contains(s.getPort())) {
                    ports.add(s.getPort());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (Exception ignored) {
            }
        }
        return ports;
    }

    private static Shell wrap(final AdbManager manager) {
        return new Shell() {
            @Override
            public String run(String command) throws Exception {
                return Adb.run(manager, command);
            }

            @Override
            public void stream(String command, OutputStream out) throws Exception {
                Adb.stream(manager, command, out);
            }

            @Override
            public String install(File apk) throws Exception {
                return Adb.install(manager, apk);
            }

            @Override
            public void push(File file, String remotePath) throws Exception {
                Adb.push(manager, file, remotePath);
            }

            @Override
            public void close() {
                release(manager);
            }
        };
    }

    /** Este aparelho: ADB local (Depuração sem fio) e, se não der, root. */
    public static Shell openLocal(final Context context) {
        return new Shell() {
            /** Decidido no 1º comando, para não misturar saída parcial do ADB com a do root. */
            private Boolean root;

            @Override
            public String run(String command) throws Exception {
                if (root == null) {
                    try {
                        String out = shell(context, command);
                        root = false;
                        return out;
                    } catch (Exception adbError) {
                        try {
                            String out = su(command);
                            root = true;
                            return out;
                        } catch (Exception rootError) {
                            throw adbError;
                        }
                    }
                }
                return root ? su(command) : shell(context, command);
            }

            @Override
            public void stream(String command, OutputStream out) throws Exception {
                if (root == null) run("true");
                if (root) suStream(command, out);
                else Adb.stream(local(context), command, out);
            }

            @Override
            public String install(File apk) throws Exception {
                if (root == null) run("true");
                if (root) return installResult(su("pm install -r \"" + apk.getAbsolutePath() + "\" 2>&1"));
                return Adb.install(local(context), apk);
            }

            @Override
            public void push(File file, String remotePath) throws Exception {
                if (root == null) run("true");
                if (root) {
                    String dest = quote(remotePath);
                    int slash = remotePath.lastIndexOf('/');
                    String mkdir = slash > 0 ? "mkdir -p " + quote(remotePath.substring(0, slash)) + "; " : "";
                    String out = su(mkdir + "cp " + quote(file.getAbsolutePath()) + " " + dest
                            + " && chmod 664 " + dest + " 2>&1; echo \"EXIT:$?\"");
                    if (!out.endsWith("EXIT:0")) throw new IOException(out.replace("EXIT:", "código ").trim());
                    return;
                }
                Adb.push(local(context), file, remotePath);
            }

            @Override
            public void close() {
                // A conexão local é compartilhada pelo app inteiro e fica aberta.
            }
        };
    }

    /** Linha do dumpsys com a Activity em primeiro plano (Android 10-: mResumedActivity; 11+: topResumedActivity). */
    public static final String FOREGROUND_COMMAND = "dumpsys activity activities | grep -m1 ResumedActivity";

    private static final Pattern FOREGROUND_PACKAGE = Pattern.compile("u\\d+ ([^/\\s]+)/");

    /** Pacote do app em primeiro plano no aparelho da sessão ("" se não deu para identificar). */
    public static String foregroundPackage(Shell shell) throws Exception {
        Matcher m = FOREGROUND_PACKAGE.matcher(shell.run(FOREGROUND_COMMAND));
        return m.find() ? m.group(1) : "";
    }

    private static final Pattern PID_LINE = Pattern.compile("PID:(\\d*)");

    /**
     * Num só comando: pacote em primeiro plano e PID do processo principal de {@code pkg}
     * ("" se não está rodando). Se o PID muda, o app reiniciou.
     */
    public static String[] foregroundAndPid(Shell shell, String pkg) throws Exception {
        if (!pkg.matches("[A-Za-z0-9._]+")) throw new IOException("Pacote inválido: " + pkg);
        String out = shell.run(FOREGROUND_COMMAND + "; echo \"PID:$(pidof -s " + pkg + ")\"");
        Matcher fg = FOREGROUND_PACKAGE.matcher(out);
        Matcher pid = PID_LINE.matcher(out);
        return new String[]{fg.find() ? fg.group(1) : "", pid.find() ? pid.group(1) : ""};
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

    /** Marcador impresso depois do comando para saber que a saída terminou. */
    private static final String END_MARKER = "__ADB_MOBILE_END__";

    /**
     * Executa o comando e lê a saída até o marcador de fim. Não dá para confiar no fechamento do
     * stream: no libadb 3.1.1, se o aparelho fecha o stream com dados ainda na fila (saídas grandes,
     * como o logcat), o read() fica esperando para sempre depois de esvaziá-la.
     */
    private static String run(AdbManager manager, String command) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        stream(manager, command, out);
        return out.toString("UTF-8").trim();
    }

    /**
     * Como run(), mas grava a saída direto em {@code sink} conforme chega, sem guardar tudo na
     * memória (o logcat de alguns aparelhos passa de dezenas de MB).
     */
    private static void stream(AdbManager manager, String command, OutputStream sink) throws Exception {
        AdbStream stream = manager.openStream("shell:" + ascii(command) + "; echo " + END_MARKER);
        MarkerFilter out = new MarkerFilter(sink);
        try {
            InputStream in = stream.openInputStream();
            byte[] buf = new byte[16384];
            int n;
            try {
                while (!out.done && (n = in.read(buf)) > 0) out.write(buf, n);
            } catch (IOException e) {
                // O libadb lança "Stream closed" quando o comando termina, em vez de retornar -1.
                if (!stream.isClosed()) throw e;
            }
            out.finish();
        } finally {
            stream.close();
        }
    }

    /** Repassa a saída e para no marcador de fim (que pode chegar dividido entre dois pacotes). */
    private static final class MarkerFilter {
        private static final byte[] MARKER = END_MARKER.getBytes();
        private final OutputStream out;
        private byte[] pending = new byte[0];
        boolean done;

        MarkerFilter(OutputStream out) {
            this.out = out;
        }

        void write(byte[] b, int n) throws IOException {
            byte[] all = new byte[pending.length + n];
            System.arraycopy(pending, 0, all, 0, pending.length);
            System.arraycopy(b, 0, all, pending.length, n);
            int at = indexOf(all, MARKER);
            if (at >= 0) {
                out.write(all, 0, at);
                pending = new byte[0];
                done = true;
                return;
            }
            // Segura o final, que pode ser o começo do marcador.
            int keep = Math.min(all.length, MARKER.length - 1);
            out.write(all, 0, all.length - keep);
            pending = Arrays.copyOfRange(all, all.length - keep, all.length);
        }

        void finish() throws IOException {
            out.write(pending);
            pending = new byte[0];
        }

        private static int indexOf(byte[] data, byte[] what) {
            outer:
            for (int i = 0; i <= data.length - what.length; i++) {
                for (int j = 0; j < what.length; j++) {
                    if (data[i + j] != what[j]) continue outer;
                }
                return i;
            }
            return -1;
        }
    }

    // ---------- Instalação de APK ----------

    /**
     * Envia o APK para /data/local/tmp do aparelho e instala com "pm install -r" (o mesmo que o
     * "adb install" faz em aparelhos antigos; funciona do Android 5 em diante).
     */
    private static String install(AdbManager manager, File apk) throws Exception {
        String tmp = "/data/local/tmp/adbmobile-" + System.currentTimeMillis() + ".apk";
        try {
            push(manager, apk, tmp);
            return installResult(run(manager, "pm install -r " + tmp + " 2>&1"));
        } finally {
            try {
                run(manager, "rm -f " + tmp);
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- Envio de arquivo ----------

    /**
     * Envia o arquivo pelo ADB gravando com "cat" no destino (o "adb push" usa o serviço sync, que o
     * libadb não implementa). Cria a pasta se preciso e confere o tamanho no fim.
     */
    private static void push(AdbManager manager, File file, String remotePath) throws Exception {
        long size = file.length();
        String dest = quote(remotePath);
        int slash = remotePath.lastIndexOf('/');
        if (slash > 0) run(manager, "mkdir -p " + quote(remotePath.substring(0, slash)));
        AdbStream stream = manager.openStream("exec:" + ascii("cat > " + dest));
        try (InputStream in = new FileInputStream(file)) {
            // O libadb 3.1.1 manda vários pacotes seguidos se o bloco passar do máximo negociado,
            // e o aparelho derruba a conexão: os blocos precisam caber num pacote só.
            byte[] buf = new byte[manager.getAdbConnection().getMaxData()];
            int n;
            while ((n = in.read(buf)) > 0) stream.write(buf, 0, n);
            // Escrita vazia: só espera o aparelho confirmar o último bloco.
            stream.write(buf, 0, 0);
        } finally {
            stream.close();
        }
        waitForSize(manager, dest, size);
    }

    /**
     * O libadb 3.1.1 reserva o buffer do comando pelo número de caracteres, não de bytes UTF-8:
     * com acento (ex.: "ção") lança BufferOverflowException. Nesse caso o comando vai só com ASCII,
     * como {@code eval "$(printf '...\303\247...')"}, e o shell do aparelho remonta o texto original.
     */
    static String ascii(String command) {
        boolean plain = true;
        for (int i = 0; i < command.length() && plain; i++) plain = command.charAt(i) < 0x80;
        if (plain) return command;
        StringBuilder fmt = new StringBuilder();
        for (byte b : command.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c == '%') fmt.append("%%");
            else if (c == '\\') fmt.append("\\\\");
            else if (c >= 0x80 || c == '\'' || c < 0x20) fmt.append(String.format("\\%03o", c));
            else fmt.append((char) c);
        }
        return "eval \"$(printf '" + fmt + "')\"";
    }

    /** Caminho entre aspas simples para o shell do aparelho (aceita espaços e acentos). */
    static String quote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    /** O "cat" do aparelho ainda pode estar gravando o fim do arquivo quando o envio termina. */
    private static void waitForSize(AdbManager manager, String quotedPath, long size) throws Exception {
        String got = "";
        for (int i = 0; i < 20; i++) {
            got = run(manager, "stat -c %s " + quotedPath + " 2>/dev/null || wc -c < " + quotedPath
                    + " 2>/dev/null");
            if (got.equals(String.valueOf(size))) return;
            Thread.sleep(500);
        }
        if (!got.matches("\\d*")) throw new IOException("Não foi possível gravar no aparelho: " + got);
        throw new IOException("O arquivo não chegou inteiro ao aparelho (" + (got.isEmpty() ? 0 : got)
                + " de " + size + " bytes). Confira se a pasta de destino existe e tem permissão de "
                + "escrita, e tente novamente.");
    }

    /** "Success" do pm; senão lança o motivo (ex.: INSTALL_FAILED_UPDATE_INCOMPATIBLE). */
    private static String installResult(String out) throws IOException {
        if (out.contains("Success")) return out;
        Matcher m = Pattern.compile("Failure \\[([^\\]]+)\\]").matcher(out);
        String reason = m.find() ? m.group(1) : out.trim();
        throw new IOException(installHint(reason));
    }

    /** Explica os erros de instalação mais comuns. */
    private static String installHint(String reason) {
        if (reason.contains("UPDATE_INCOMPATIBLE"))
            return reason + " (assinatura diferente da versão instalada: desinstale o app no aparelho antes)";
        if (reason.contains("VERSION_DOWNGRADE"))
            return reason + " (o aparelho já tem uma versão mais nova)";
        if (reason.contains("INSUFFICIENT_STORAGE")) return reason + " (sem espaço no aparelho)";
        if (reason.contains("OLDER_SDK")) return reason + " (Android do aparelho é antigo demais para o app)";
        if (reason.contains("USER_RESTRICTED"))
            return reason + " (o aparelho bloqueou: ative \"Instalar via USB\" nas Opções do desenvolvedor)";
        return reason.isEmpty() ? "Instalação falhou sem mensagem do aparelho." : reason;
    }

    /** Como su(), mas grava a saída direto em {@code sink}. */
    private static void suStream(String command, OutputStream sink) throws Exception {
        Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) sink.write(buf, 0, n);
        int code = p.waitFor();
        if (code != 0) throw new IOException("su saiu com código " + code);
    }

    /** Conexão ADB local (127.0.0.1), conectando se preciso. */
    private static AdbManager local(Context context) throws Exception {
        AdbManager manager = AdbManager.get(context);
        if (!manager.isConnected()) {
            ensureWirelessDebuggingOn(context);
            if (!manager.autoConnect(context, 15000)) {
                throw new IOException("Não foi possível conectar ao ADB. Verifique se a "
                        + "\"Depuração sem fio\" está ativada e o Wi-Fi conectado.");
            }
        }
        return manager;
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
