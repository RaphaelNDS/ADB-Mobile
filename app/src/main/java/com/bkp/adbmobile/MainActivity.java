package com.bkp.adbmobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.muntashirakon.adb.AdbPairingRequiredException;

/**
 * Executa comandos "adb shell" escolhidos numa lista ({@link AdbCommands}) ou digitados,
 * por exemplo: settings put system eth_device_conn 2
 *
 * Neste aparelho: usa root (su) se houver; senão, conecta à "Depuração sem fio" local
 * (pareamento feito uma vez) e roda o comando como usuário shell.
 * Em outro aparelho: conecta por IP:porta (Depuração sem fio pareada ou "adb tcpip 5555").
 *
 * Esta classe monta a tela e guarda o que as partes usam em comum: aparelho alvo, pareamento,
 * status e registro. Cada parte da tela fica na sua classe:
 * {@link CommandsPanel} (comando e execução), {@link DevicesPanel} (aparelhos salvos),
 * {@link FileTransfers} (instalar APK e enviar arquivo), {@link RemoteBrowser} (baixar arquivo),
 * {@link DeviceCapture} (log e captura de tela), {@link AppScreens} (apps do aparelho),
 * {@link MonitorPanel} (log automático), {@link LiveLogcat} (logcat ao vivo) e
 * {@link ConfigBackup} (exportar/importar). O Registro é guardado entre aberturas
 * ({@link LogHistory}).
 */
public class MainActivity extends Activity {

    enum Status { INFO, SUCCESS, ERROR }

    static final int REQUEST_SAVE_LOG = 10;
    static final int REQUEST_LOG_FOLDER = 11;
    static final int REQUEST_INSTALL_APK = 12;
    static final int REQUEST_PUSH_FILE = 13;
    static final int REQUEST_PULL_FILE = 14;
    static final int REQUEST_SAVE_SCREENSHOT = 15;
    static final int REQUEST_EXPORT_CONFIG = 16;
    static final int REQUEST_IMPORT_CONFIG = 17;
    static final int REQUEST_KIOSK_APK = 18;
    static final int REQUEST_SAVE_INVENTORY = 19;

    /** Motivo de falha de um aparelho que ainda não pareou com o app. */
    static final String NEEDS_PAIRING = "Exige pareamento. Conclua a etapa 1.";

    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    SharedPreferences prefs;
    private RadioButton remoteOption;
    private View remoteBox;
    private TextView pairingHelp;
    private Button notificationPairing;
    private Button qrPairing;
    private EditText portField;
    private EditText codeField;
    private TextView status;
    private TextView log;
    private Button[] actions;
    /** Progresso em andamento; finish() o encerra para não sobrescrever a mensagem final. */
    ProgressBoard activeBoard;

    DevicesPanel devices;
    DeviceRunner runner;
    CommandsPanel commands;
    MonitorPanel monitor;
    private FileTransfers transfers;
    private RemoteBrowser browser;
    private DeviceCapture capture;
    private AppScreens apps;
    private LiveLogcat liveLogcat;
    private ConfigBackup config;
    private Updater updater;
    private KioskSetup kiosk;
    private Inventory inventory;
    private LogHistory history;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Grava o Registro um pouco depois da última linha (várias linhas seguidas = uma gravação). */
    private final Runnable saveHistory = this::saveHistoryNow;

    /**
     * Android 15+ desenha a tela atrás das barras do sistema (edge-to-edge obrigatório no
     * targetSdk 35) e o adjustResize deixa de encolher a tela para o teclado. A barra do app ganha
     * a altura da barra de status em cima (a cor dela continua por trás do relógio), e o resto
     * da tela se afasta da barra de navegação e do teclado.
     */
    private void fitSystemBars() {
        if (Build.VERSION.SDK_INT < 35) return;
        final ViewGroup root = (ViewGroup) ((ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        final View header = root.getChildAt(0);
        final int headerTop = header.getPaddingTop();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            header.setPadding(header.getPaddingLeft(), headerTop + bars.top, header.getPaddingRight(),
                    header.getPaddingBottom());
            v.setPadding(bars.left, 0, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsets.CONSUMED;
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        fitSystemBars();
        prefs = getSharedPreferences("alvo", MODE_PRIVATE);

        ((TextView) findViewById(R.id.version)).setText("v" + versionName());

        remoteOption = findViewById(R.id.targetRemote);
        remoteBox = findViewById(R.id.remoteBox);
        pairingHelp = findViewById(R.id.pairingHelp);
        notificationPairing = findViewById(R.id.pairNotification);
        qrPairing = findViewById(R.id.pairQr);
        portField = findViewById(R.id.pairPort);
        codeField = findViewById(R.id.pairCode);
        status = findViewById(R.id.status);
        log = findViewById(R.id.log);
        history = new LogHistory(getFilesDir());
        String previous = history.load();
        if (!previous.isEmpty()) log.setText(previous);

        Button pairManual = findViewById(R.id.pairManual);
        Button run = findViewById(R.id.run);
        Button read = findViewById(R.id.read);
        Button saveLog = findViewById(R.id.saveLog);
        Button installApk = findViewById(R.id.installApk);
        Button pushFile = findViewById(R.id.pushFile);
        Button pullFile = findViewById(R.id.pullFile);
        Button screenshot = findViewById(R.id.screenshot);
        Button chooseApp = findViewById(R.id.chooseApp);
        Button appInfo = findViewById(R.id.appInfo);
        Button runBatch = findViewById(R.id.runBatch);
        Button live = findViewById(R.id.liveLogcat);
        Button kioskSetup = findViewById(R.id.kioskSetup);
        Button inventoryButton = findViewById(R.id.inventory);
        actions = new Button[]{notificationPairing, qrPairing, pairManual, run, read, saveLog, installApk, pushFile,
                pullFile, screenshot, chooseApp, appInfo, runBatch, live, kioskSetup, inventoryButton};

        devices = new DevicesPanel(this);
        runner = new DeviceRunner(this);
        transfers = new FileTransfers(this);
        browser = new RemoteBrowser(this);
        capture = new DeviceCapture(this);
        apps = new AppScreens(this);
        // O monitor vem antes dos comandos: a lista de comandos usa o pacote dele.
        monitor = new MonitorPanel(this);
        commands = new CommandsPanel(this, read);
        liveLogcat = new LiveLogcat(this);
        config = new ConfigBackup(this);
        updater = new Updater(this);
        kiosk = new KioskSetup(this);
        inventory = new Inventory(this);

        notificationPairing.setOnClickListener(v -> startNotificationPairing());
        pairManual.setOnClickListener(v -> manualPairing());
        qrPairing.setOnClickListener(v -> new QrPairing(this).open());
        run.setOnClickListener(v -> commands.confirmAndRun());
        read.setOnClickListener(v -> commands.readValue());
        runBatch.setOnClickListener(v -> commands.chooseBatch());
        live.setOnClickListener(v -> liveLogcat.open());
        kioskSetup.setOnClickListener(v -> kiosk.choose());
        inventoryButton.setOnClickListener(v -> inventory.run());
        saveLog.setOnClickListener(v -> capture.chooseLogFile());
        installApk.setOnClickListener(v -> transfers.chooseApk());
        pushFile.setOnClickListener(v -> transfers.choosePushFile());
        pullFile.setOnClickListener(v -> browser.browse());
        screenshot.setOnClickListener(v -> capture.captureScreen());
        chooseApp.setOnClickListener(v -> apps.chooseApp());
        appInfo.setOnClickListener(v -> apps.showAppInfo(monitor.packageName()));
        findViewById(R.id.clearLog).setOnClickListener(v -> {
            log.setText("");
            main.removeCallbacks(saveHistory);
            history.clear();
        });
        findViewById(R.id.searchLog).setOnClickListener(v -> searchLog());
        findViewById(R.id.shareLog).setOnClickListener(v -> shareLog());

        RadioGroup target = findViewById(R.id.target);
        target.check(prefs.getBoolean("remote", false) ? R.id.targetRemote : R.id.targetLocal);
        target.setOnCheckedChangeListener((group, id) -> {
            prefs.edit().putBoolean("remote", id == R.id.targetRemote).apply();
            updateMode();
        });
        updateMode();

        setStatus(Status.INFO, "Pronto para executar.");
        // Só quando o app abre de fato: a tela recriada (ex.: tela dividida, fonte) já tem essas linhas.
        if (savedInstanceState == null) {
            append("----- App aberto em " + new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(new Date())
                    + " (v" + versionName() + ") -----");
            append("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") - "
                    + Build.MANUFACTURER + " " + Build.MODEL);
            updater.checkOnStart();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        monitor.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Grava já: o Android pode encerrar o app em segundo plano sem aviso.
        main.removeCallbacks(saveHistory);
        // Na hora, não em segundo plano: a tela recriada lê o arquivo logo em seguida e perdia
        // as últimas linhas se a gravação ainda não tivesse terminado.
        String text = log.getText().toString();
        if (!text.isEmpty() && !history.save(text)) {
            append("Aviso: não foi possível guardar o Registro para a próxima abertura.");
        }
    }

    @Override
    protected void onDestroy() {
        monitor.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        switch (requestCode) {
            case REQUEST_LOG_FOLDER:
                monitor.onFolderChosen(uri, data);
                break;
            case REQUEST_INSTALL_APK:
                transfers.onApkChosen(uri);
                break;
            case REQUEST_PUSH_FILE:
                transfers.onPushFileChosen(uri);
                break;
            case REQUEST_PULL_FILE:
                browser.pullFile(uri);
                break;
            case REQUEST_SAVE_SCREENSHOT:
                capture.saveScreenshot(uri);
                break;
            case REQUEST_EXPORT_CONFIG:
                config.onExportFileChosen(uri);
                break;
            case REQUEST_IMPORT_CONFIG:
                config.onImportFileChosen(uri);
                break;
            case REQUEST_KIOSK_APK:
                kiosk.onApkChosen(uri);
                break;
            case REQUEST_SAVE_INVENTORY:
                inventory.onCsvFileChosen(uri);
                break;
            case REQUEST_SAVE_LOG:
                capture.onLogFileChosen(uri);
                break;
        }
    }

    // ---------- Aparelho alvo ----------

    boolean isRemote() {
        return remoteOption.isChecked();
    }

    private void updateMode() {
        boolean remote = isRemote();
        remoteBox.setVisibility(remote ? View.VISIBLE : View.GONE);
        notificationPairing.setVisibility(remote ? View.GONE : View.VISIBLE);
        qrPairing.setVisibility(remote ? View.VISIBLE : View.GONE);
        if (remote) {
            pairingHelp.setText("No aparelho alvo, abra \"Depuração por Wi-Fi\" > \"Parear o dispositivo com "
                    + "um código de pareamento\" e informe abaixo a porta e o código exibidos "
                    + "(essa porta é diferente da porta de conexão), ou toque em \"Parear por QR code\" e leia o "
                    + "código em \"Parear o dispositivo com código QR\". Não é necessário para Android 10 ou anterior.");
        } else {
            pairingHelp.setText("Em Configurações > Opções do desenvolvedor, ative \"Depuração sem fio\" "
                    + "(Samsung: \"Depuração por Wi-Fi\") e toque em \"Parear dispositivo com código de "
                    + "pareamento\". Digite o código na notificação do ADB Mobile ou, com a tela dividida, "
                    + "nos campos abaixo.");
        }
        if (!remote && Build.VERSION.SDK_INT < 30) {
            append("Aviso: \"Depuração sem fio\" exige Android 11+. Neste aparelho o modo local requer root.");
        }
    }

    /**
     * Alvo das operações de um aparelho só (log, captura, apps, baixar arquivo): o único marcado
     * em "Outro aparelho". Devolve false (com aviso) se não há exatamente um; com true, o alvo
     * fica em {@code out[0]} (null = este aparelho).
     */
    boolean oneTarget(String what, Devices.Device[] out) {
        out[0] = null;
        if (!isRemote()) return true;
        out[0] = devices.singleTarget(what);
        return out[0] != null;
    }

    /** Abre uma sessão no alvo (null = este aparelho). */
    Adb.Shell openTarget(Devices.Device target) throws Exception {
        return target != null
                ? Adb.openRemote(this, target.host, target.port, devices.portChangedFor(target.host))
                : Adb.openLocal(this);
    }

    /** "[ip:porta] " para um aparelho remoto, "" para este aparelho (início das linhas do registro). */
    static String prefix(Devices.Device target) {
        return target != null ? "[" + target.address() + "] " : "";
    }

    /** Tarefa que usa uma sessão no aparelho alvo (roda em segundo plano). */
    interface ShellTask {
        void run(Adb.Shell shell) throws Exception;
    }

    /**
     * Abre o aparelho alvo (este ou o único marcado), roda {@code task} e fecha. Erros viram status
     * de erro com {@code errorPrefix} na frente.
     */
    void withTargetShell(String what, String line, final String errorPrefix, final ShellTask task) {
        final Devices.Device[] t = new Devices.Device[1];
        if (!oneTarget(what, t)) return;
        final Devices.Device target = t[0];
        start(prefix(target) + line);
        background(() -> {
            try {
                Adb.Shell shell = openTarget(target);
                try {
                    task.run(shell);
                } finally {
                    shell.close();
                }
            } catch (AdbPairingRequiredException e) {
                pairingRequired(target != null ? target.address() : null);
            } catch (Exception e) {
                finish(Status.ERROR, errorPrefix + Adb.describe(e));
            }
        });
    }

    // ---------- Pareamento ----------

    private void startNotificationPairing() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            setStatus(Status.INFO, "Permita as notificações e toque novamente em \"Parear via notificação\".");
            return;
        }
        Adb.startPairingDiscovery(this);
        Adb.showPairingNotification(this, "Abra \"Parear dispositivo com código de pareamento\" "
                + "na Depuração sem fio e digite aqui o código de 6 dígitos.", true);
        append("Notificação de pareamento criada; abrindo Opções do desenvolvedor.");
        setStatus(Status.INFO, "Aguardando o código de pareamento na notificação.");

        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        Bundle args = new Bundle();
        args.putString(":settings:fragment_args_key", "toggle_adb_wireless");
        intent.putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
        intent.putExtra(":settings:show_fragment_args", args);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            append("Abra manualmente: Configurações > Opções do desenvolvedor > Depuração sem fio.");
        }
    }

    private void manualPairing() {
        final String port = portField.getText().toString().trim();
        final String code = codeField.getText().toString().trim();
        if (port.isEmpty() || code.isEmpty()) {
            setStatus(Status.ERROR, "Informe a porta de pareamento e o código de 6 dígitos.");
            return;
        }
        if (isRemote()) {
            final Devices.Device t = devices.formTarget();
            if (t == null) return;
            start("Pareando com " + t.host + ":" + port + "...");
            background(() -> {
                try {
                    Adb.pairRemote(this, t.host, Integer.parseInt(port), code);
                    finish(Status.SUCCESS, "Pareado com " + t.host + ". Confira a porta de conexão e execute o comando.");
                } catch (Exception e) {
                    finish(Status.ERROR, "Falha no pareamento: " + Adb.describe(e));
                }
            });
            return;
        }
        start("Pareando na porta " + port + "...");
        background(() -> {
            try {
                Adb.pair(this, Integer.parseInt(port), code);
                finish(Status.SUCCESS, "Pareado com sucesso. Agora execute o comando.");
            } catch (Exception e) {
                finish(Status.ERROR, "Falha no pareamento: " + Adb.describe(e));
            }
        });
    }

    /**
     * Termina a operação com o aviso de pareamento e abre uma janela explicando: só o texto no
     * status passava despercebido e parecia que o app tinha travado.
     * {@code target} é o aparelho remoto ("ip:porta"), ou null para este aparelho.
     */
    void pairingRequired(final String target) {
        finish(Status.ERROR, target != null ? "O aparelho alvo exige pareamento. Conclua a etapa 1."
                : "Aparelho ainda não pareado. Conclua a etapa 1.");
        runOnUiThread(() -> showPairingDialog(target));
    }

    /** Janela "Pareamento necessário" com atalho para a etapa 1. */
    void showPairingDialog(String target) {
        if (isFinishing() || isDestroyed()) return;
        String message = target == null
                ? "Este aparelho ainda não foi pareado com o ADB Mobile, por isso o comando não foi "
                + "executado.\n\nFaça o pareamento na etapa 1 (só é preciso uma vez) e tente de novo."
                : target + " ainda não foi pareado com o ADB Mobile, por isso o comando não foi "
                + "executado nele.\n\nFaça o pareamento na etapa 1 (só é preciso uma vez) e tente de novo.";
        new AlertDialog.Builder(this)
                .setTitle("Pareamento necessário")
                .setMessage(message)
                .setPositiveButton("Ir para o pareamento", (d, w) -> goToPairing())
                .setNegativeButton("Fechar", null)
                .show();
    }

    /** Rola até o cartão de pareamento e põe o cursor no campo da porta. */
    private void goToPairing() {
        final View card = findViewById(R.id.pairCard);
        final android.widget.ScrollView scroll = findViewById(R.id.scroll);
        scroll.post(() -> scroll.smoothScrollTo(0, card.getTop()));
        portField.requestFocus();
    }

    // ---------- Arquivos do celular ----------

    /** Abre um seletor de arquivo ou pasta do sistema; false (com aviso) se não há nenhum. */
    boolean openPicker(Intent intent, int requestCode) {
        try {
            startActivityForResult(intent, requestCode);
            return true;
        } catch (Exception e) {
            setStatus(Status.ERROR, Intent.ACTION_OPEN_DOCUMENT_TREE.equals(intent.getAction())
                    ? "Nenhum seletor de pastas disponível neste aparelho."
                    : "Nenhum seletor de arquivos disponível neste aparelho.");
            return false;
        }
    }

    /** Copia o arquivo escolhido no seletor para {@code dest} (o ADB precisa de um arquivo local). */
    void copyToFile(Uri uri, File dest) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IOException("não foi possível abrir o arquivo");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** Nome do arquivo escolhido no seletor (o Uri em si costuma ser só um ID). */
    String fileName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return uri.getLastPathSegment();
    }

    // ---------- Estado da tela ----------

    /** Início de uma operação: registra, mostra "em andamento" e bloqueia os botões. */
    void start(String line) {
        append(line);
        setStatus(Status.INFO, "Em andamento...");
        for (Button b : actions) b.setEnabled(false);
    }

    /** Fim de uma operação (chamado de qualquer thread). */
    void finish(Status s, String message) {
        runOnUiThread(() -> {
            activeBoard = null;
            append((s == Status.ERROR ? "ERRO: " : s == Status.SUCCESS ? "OK: " : "") + message);
            setStatus(s, message);
            for (Button b : actions) b.setEnabled(true);
        });
    }

    void setStatus(Status s, String message) {
        status.setText(message);
        switch (s) {
            case SUCCESS:
                status.setBackgroundResource(R.drawable.bg_status_success);
                status.setTextColor(color(R.color.success));
                break;
            case ERROR:
                status.setBackgroundResource(R.drawable.bg_status_error);
                status.setTextColor(color(R.color.error));
                break;
            default:
                status.setBackgroundResource(R.drawable.bg_status_info);
                status.setTextColor(color(R.color.info));
        }
    }

    /** Mostra a saída do comando no registro (cortada se for muito grande). */
    void logOutput(String prefix, String out) {
        if (out == null || out.isEmpty()) return;
        final int max = 20000;
        String text = out.length() <= max ? out
                : out.substring(0, max) + "\n... (saída cortada; " + out.length() + " caracteres)";
        // Com prefixo (vários aparelhos), a saída começa na linha de baixo para não desalinhar.
        log(prefix.isEmpty() ? text : prefix + "\n" + text);
    }

    /** Linha no registro, de qualquer thread. */
    void log(String s) {
        runOnUiThread(() -> append(s));
    }

    /** Linha no registro, no thread da tela. */
    void append(String s) {
        if (log.length() > 0) log.append("\n");
        log.append(clock.format(new Date()) + "  " + s);
        // A tela também não cresce sem limite (o arquivo guarda o mesmo trecho).
        if (log.length() > 2 * LogHistory.MAX_CHARS) {
            log.setText(LogHistory.trim(log.getText().toString(), LogHistory.MAX_CHARS));
        }
        main.removeCallbacks(saveHistory);
        main.postDelayed(saveHistory, 1000);
    }

    // ---------- Histórico do Registro ----------

    private void saveHistoryNow() {
        if (history == null) return;
        final String text = log.getText().toString();
        if (text.isEmpty()) return;
        background(() -> {
            if (!history.save(text)) log("Aviso: não foi possível guardar o Registro para a próxima abertura.");
        });
    }

    /** Janela com as linhas do Registro que contêm o texto digitado (atualiza ao digitar). */
    private void searchLog() {
        final String all = log.getText().toString();
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText query = new EditText(this);
        query.setHint("Texto a buscar (ex.: ERRO, Kiosk, 172.16)");
        query.setSingleLine(true);
        box.addView(query);
        final TextView count = new TextView(this);
        count.setTextSize(12);
        count.setTextColor(color(R.color.text_secondary));
        box.addView(count);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        final TextView result = new TextView(this);
        result.setTypeface(android.graphics.Typeface.MONOSPACE);
        result.setTextSize(11);
        result.setTextIsSelectable(true);
        result.setTextColor(color(R.color.console_text));
        result.setBackgroundResource(R.drawable.bg_console);
        result.setPadding(dp(8), dp(8), dp(8), dp(8));
        scroll.addView(result);
        box.addView(scroll, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.45)));
        query.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                java.util.List<String> found = LogHistory.search(all, s.toString());
                result.setText(android.text.TextUtils.join("\n", found));
                count.setText(s.toString().trim().isEmpty() ? ""
                        : found.size() + (found.size() == 1 ? " linha encontrada" : " linhas encontradas"));
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
        new AlertDialog.Builder(this)
                .setTitle("Buscar no Registro")
                .setView(box)
                .setPositiveButton("Fechar", null)
                .show();
    }

    /** Envia o Registro como texto (e-mail, WhatsApp, Drive...). */
    private void shareLog() {
        String text = log.getText().toString();
        if (text.isEmpty()) {
            setStatus(Status.INFO, "O Registro está vazio.");
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Registro do ADB Mobile - " + Build.MODEL);
        send.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(send, "Compartilhar Registro"));
        } catch (Exception e) {
            setStatus(Status.ERROR, "Nenhum app para compartilhar texto neste aparelho.");
        }
    }

    void background(Runnable r) {
        new Thread(r).start();
    }

    @SuppressWarnings("deprecation")
    int color(int id) {
        return getResources().getColor(id);
    }

    /** {@code value} em dp convertido para pixels. */
    int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
