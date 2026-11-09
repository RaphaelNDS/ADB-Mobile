package com.bkp.adbmobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

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
 */
public class MainActivity extends Activity {

    private enum Status { INFO, SUCCESS, ERROR }

    private static final int REQUEST_SAVE_LOG = 10;
    private static final int REQUEST_LOG_FOLDER = 11;
    private static final String DEFAULT_MONITOR_PACKAGE = "br.com.backupautomacao.kiosk";

    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private SharedPreferences prefs;
    private RadioButton remoteOption;
    private View remoteBox;
    private EditText hostField;
    private EditText connectPortField;
    private TextView pairingHelp;
    private Button notificationPairing;
    private EditText portField;
    private EditText codeField;
    private TextView status;
    private TextView log;
    private Button[] actions;
    private EditText monitorPackageField;
    private TextView logFolderLabel;
    private Button toggleMonitorButton;
    private Spinner presetSpinner;
    private EditText commandField;
    private Button readButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("alvo", MODE_PRIVATE);

        ((TextView) findViewById(R.id.version)).setText("v" + versionName());

        remoteOption = findViewById(R.id.targetRemote);
        remoteBox = findViewById(R.id.remoteBox);
        hostField = findViewById(R.id.host);
        connectPortField = findViewById(R.id.connectPort);
        pairingHelp = findViewById(R.id.pairingHelp);
        notificationPairing = findViewById(R.id.pairNotification);
        portField = findViewById(R.id.pairPort);
        codeField = findViewById(R.id.pairCode);
        status = findViewById(R.id.status);
        log = findViewById(R.id.log);

        Button pairManual = findViewById(R.id.pairManual);
        Button run = findViewById(R.id.run);
        Button read = findViewById(R.id.read);
        readButton = read;
        Button saveLog = findViewById(R.id.saveLog);
        actions = new Button[]{notificationPairing, pairManual, run, read, saveLog};

        hostField.setText(prefs.getString("host", ""));
        connectPortField.setText(prefs.getString("port", "5555"));

        notificationPairing.setOnClickListener(v -> startNotificationPairing());
        pairManual.setOnClickListener(v -> manualPairing());
        run.setOnClickListener(v -> confirmAndRun());
        read.setOnClickListener(v -> readValue());
        saveLog.setOnClickListener(v -> chooseLogFile());

        monitorPackageField = findViewById(R.id.monitorPackage);
        logFolderLabel = findViewById(R.id.logFolder);
        toggleMonitorButton = findViewById(R.id.toggleMonitor);
        monitorPackageField.setText(prefs.getString("monitorPackage", DEFAULT_MONITOR_PACKAGE));
        setupCommands();
        updateFolderLabel();
        findViewById(R.id.chooseFolder).setOnClickListener(v -> chooseLogFolder());
        toggleMonitorButton.setOnClickListener(v -> toggleMonitor());
        LogMonitorService.listener = monitorListener;
        findViewById(R.id.clearLog).setOnClickListener(v -> log.setText(""));

        RadioGroup target = findViewById(R.id.target);
        target.check(prefs.getBoolean("remote", false) ? R.id.targetRemote : R.id.targetLocal);
        target.setOnCheckedChangeListener((group, id) -> {
            prefs.edit().putBoolean("remote", id == R.id.targetRemote).apply();
            updateMode();
        });
        updateMode();

        setStatus(Status.INFO, "Pronto para executar.");
        append("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") - "
                + Build.MANUFACTURER + " " + Build.MODEL);
    }

    private boolean isRemote() {
        return remoteOption.isChecked();
    }

    private void updateMode() {
        boolean remote = isRemote();
        remoteBox.setVisibility(remote ? View.VISIBLE : View.GONE);
        notificationPairing.setVisibility(remote ? View.GONE : View.VISIBLE);
        if (remote) {
            pairingHelp.setText("No aparelho alvo, abra \"Depuração por Wi-Fi\" > \"Parear o dispositivo com "
                    + "um código de pareamento\" e informe abaixo a porta e o código exibidos "
                    + "(essa porta é diferente da porta de conexão). Não é necessário para Android 10 ou anterior.");
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

    /** IP e porta de conexão do alvo remoto; null (com aviso) se inválidos. */
    private Object[] remoteTarget() {
        String host = hostField.getText().toString().trim();
        String port = connectPortField.getText().toString().trim();
        if (host.isEmpty() || port.isEmpty()) {
            setStatus(Status.ERROR, "Informe o IP e a porta de conexão do aparelho alvo.");
            return null;
        }
        prefs.edit().putString("host", host).putString("port", port).apply();
        return new Object[]{host, Integer.parseInt(port)};
    }

    private void startNotificationPairing() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != 0) {
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
            final Object[] t = remoteTarget();
            if (t == null) return;
            start("Pareando com " + t[0] + ":" + port + "...");
            background(() -> {
                try {
                    Adb.pairRemote(this, (String) t[0], Integer.parseInt(port), code);
                    finish(Status.SUCCESS, "Pareado com " + t[0] + ". Confira a porta de conexão e execute o comando.");
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

    // ---------- Comandos ----------

    private void setupCommands() {
        presetSpinner = findViewById(R.id.preset);
        commandField = findViewById(R.id.command);
        ArrayAdapter<AdbCommands.Preset> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, AdbCommands.PRESETS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(adapter);

        final int saved = Math.max(0, Math.min(prefs.getInt("preset", 0), AdbCommands.PRESETS.length - 1));
        presetSpinner.setSelection(saved, false);
        commandField.setText(prefs.getString("command", fill(AdbCommands.PRESETS[saved].command)));
        updateReadButton();
        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            /** O Spinner avisa a seleção inicial ao desenhar; ela não deve sobrescrever o texto salvo. */
            private int shown = saved;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == shown) return;
                shown = position;
                prefs.edit().putInt("preset", position).apply();
                commandField.setText(fill(AdbCommands.PRESETS[position].command));
                if (AdbCommands.PRESETS[position].command.isEmpty()) commandField.requestFocus();
                updateReadButton();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private AdbCommands.Preset selectedPreset() {
        int i = presetSpinner.getSelectedItemPosition();
        return AdbCommands.PRESETS[i < 0 ? 0 : i];
    }

    private void updateReadButton() {
        readButton.setVisibility(selectedPreset().read != null ? View.VISIBLE : View.GONE);
    }

    /** Troca {pacote} pelo pacote informado no Log automático. */
    private String fill(String command) {
        String pkg = monitorPackageField.getText().toString().trim();
        return pkg.isEmpty() ? command : command.replace(AdbCommands.PACKAGE, pkg);
    }

    /** Nome do valor lido: "settings get system eth_device_conn" -> "eth_device_conn". */
    private static String valueName(String readCommand) {
        String[] parts = readCommand.trim().split("\\s+");
        return parts[parts.length - 1];
    }

    private void confirmAndRun() {
        final String cmd = commandField.getText().toString().trim();
        if (cmd.isEmpty()) {
            setStatus(Status.ERROR, "Escolha ou digite um comando.");
            return;
        }
        if (cmd.contains(AdbCommands.PACKAGE)) {
            setStatus(Status.ERROR, "Informe o pacote do app (em \"Log automático\") ou edite o comando.");
            return;
        }
        prefs.edit().putString("command", cmd).apply();
        final AdbCommands.Preset preset = selectedPreset();
        if (!preset.confirm) {
            runCommand(cmd, preset.read);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(preset.label)
                .setMessage("Executar no aparelho alvo?\n\nadb shell " + cmd)
                .setPositiveButton("Executar", (d, w) -> runCommand(cmd, preset.read))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Executa {@code cmd} no aparelho alvo e, se {@code read} não for null, lê o valor resultante. */
    private void runCommand(final String cmd, final String read) {
        if (isRemote()) {
            final Object[] t = remoteTarget();
            if (t == null) return;
            start("[" + t[0] + ":" + t[1] + "] $ " + cmd);
            log("Conectando... se o alvo solicitar, autorize a depuração na tela dele.");
            background(() -> {
                try {
                    String[] out = read == null
                            ? Adb.remoteShell(this, (String) t[0], (Integer) t[1], portChanged, cmd)
                            : Adb.remoteShell(this, (String) t[0], (Integer) t[1], portChanged, cmd, read);
                    logOutput(out[0]);
                    finish(Status.SUCCESS, read == null ? "Executado em " + t[0] + "."
                            : "Aplicado em " + t[0] + ". " + valueName(read) + " = " + out[1]);
                } catch (AdbPairingRequiredException e) {
                    finish(Status.ERROR, "O aparelho alvo exige pareamento. Conclua a etapa 1.");
                } catch (Exception e) {
                    finish(Status.ERROR, "Falha: " + Adb.describe(e));
                }
            });
            return;
        }

        start("$ " + cmd);
        background(() -> {
            // 1) Root, se existir (testado antes para não executar o comando duas vezes)
            boolean root;
            try {
                Adb.su("true");
                root = true;
            } catch (Exception e) {
                root = false;
                log("Root indisponível; usando ADB local.");
            }
            try {
                if (root) {
                    logOutput(Adb.su(cmd));
                    finish(Status.SUCCESS, read == null ? "Executado via root."
                            : "Aplicado via root. " + valueName(read) + " = " + Adb.su(read));
                    return;
                }
                // 2) ADB pela Depuração sem fio
                logOutput(Adb.shell(this, cmd));
                String value = read == null ? null : Adb.shell(this, read);
                Adb.grantSelfSecureSettings(this);
                finish(Status.SUCCESS, read == null ? "Executado neste aparelho."
                        : "Aplicado neste aparelho. " + valueName(read) + " = " + value);
            } catch (AdbPairingRequiredException e) {
                finish(Status.ERROR, "Aparelho ainda não pareado. Conclua a etapa 1.");
            } catch (Exception e) {
                finish(Status.ERROR, "Falha: " + Adb.describe(e));
            }
        });
    }

    private void readValue() {
        final String read = selectedPreset().read;
        if (read == null) return;
        final String name = valueName(read);
        if (isRemote()) {
            final Object[] t = remoteTarget();
            if (t == null) return;
            start("[" + t[0] + ":" + t[1] + "] $ " + read);
            background(() -> {
                try {
                    String[] out = Adb.remoteShell(this, (String) t[0], (Integer) t[1], portChanged, read);
                    finish(Status.INFO, "Valor atual em " + t[0] + ": " + name + " = " + out[0]);
                } catch (AdbPairingRequiredException e) {
                    finish(Status.ERROR, "O aparelho alvo exige pareamento. Conclua a etapa 1.");
                } catch (Exception e) {
                    finish(Status.ERROR, "Não foi possível ler: " + Adb.describe(e));
                }
            });
            return;
        }
        start("$ " + read);
        background(() -> {
            try {
                finish(Status.INFO, "Valor atual: " + name + " = " + Adb.shell(this, read));
            } catch (AdbPairingRequiredException e) {
                finish(Status.ERROR, "Aparelho ainda não pareado. Conclua a etapa 1.");
            } catch (Exception e) {
                try {
                    finish(Status.INFO, "Valor atual (root): " + name + " = " + Adb.su(read));
                } catch (Exception e2) {
                    finish(Status.ERROR, "Não foi possível ler: " + Adb.describe(e));
                }
            }
        });
    }

    /** Mostra a saída do comando no registro (cortada se for muito grande). */
    private void logOutput(String out) {
        if (out == null || out.isEmpty()) return;
        final int max = 20000;
        log(out.length() <= max ? out
                : out.substring(0, max) + "\n... (saída cortada; " + out.length() + " caracteres)");
    }

    // ---------- Log do aparelho ----------

    /** Abre o seletor do sistema para escolher onde salvar o log (.txt). */
    private void chooseLogFile() {
        Object[] t = null;
        if (isRemote()) {
            t = remoteTarget();
            if (t == null) return;
        }
        String who = t != null ? (String) t[0] : (Build.MODEL == null ? "aparelho" : Build.MODEL);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, DeviceLog.fileName(who));
        try {
            startActivityForResult(intent, REQUEST_SAVE_LOG);
        } catch (Exception e) {
            setStatus(Status.ERROR, "Nenhum seletor de arquivos disponível neste aparelho.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_LOG_FOLDER) {
            onFolderChosen(resultCode == RESULT_OK && data != null ? data.getData() : null, data);
            return;
        }
        if (requestCode != REQUEST_SAVE_LOG) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            setStatus(Status.INFO, "Geração de log cancelada.");
            return;
        }
        // Recalcula o alvo: a tela pode ter sido recriada enquanto o seletor estava aberto.
        Object[] t = null;
        if (isRemote()) {
            t = remoteTarget();
            if (t == null) return;
        }
        saveLog(data.getData(), t);
    }

    /** Coleta o logcat do aparelho alvo e grava no arquivo escolhido. */
    private void saveLog(final Uri uri, final Object[] t) {
        start((t != null ? "[" + t[0] + ":" + t[1] + "] " : "") + "$ " + Adb.LOG_COMMAND);
        background(() -> {
            try {
                Adb.Shell shell = t != null ? Adb.openRemote(this, (String) t[0], (Integer) t[1], portChanged)
                        : Adb.openLocal(this);
                long lines;
                try {
                    lines = DeviceLog.save(shell, getContentResolver(), uri,
                            t != null ? t[0] + ":" + t[1] : "este aparelho", null).lines;
                } finally {
                    shell.close();
                }
                finish(Status.SUCCESS, "Log salvo (" + lines + " linhas): " + fileName(uri));
            } catch (AdbPairingRequiredException e) {
                DeviceLog.delete(getContentResolver(), uri);
                finish(Status.ERROR, t != null ? "O aparelho alvo exige pareamento. Conclua a etapa 1."
                        : "Aparelho ainda não pareado. Conclua a etapa 1.");
            } catch (Exception e) {
                DeviceLog.delete(getContentResolver(), uri);
                finish(Status.ERROR, "Não foi possível gerar o log: " + Adb.describe(e));
            }
        });
    }

    // ---------- Log automático ----------

    private void chooseLogFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_LOG_FOLDER);
        } catch (Exception e) {
            setStatus(Status.ERROR, "Nenhum seletor de pastas disponível neste aparelho.");
        }
    }

    private void onFolderChosen(Uri tree, Intent data) {
        if (tree == null) {
            setStatus(Status.INFO, "Escolha da pasta cancelada.");
            return;
        }
        try {
            // Mantém o acesso à pasta depois de reiniciar o app/aparelho.
            getContentResolver().takePersistableUriPermission(tree,
                    data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        } catch (Exception ignored) {
        }
        prefs.edit().putString("logFolder", tree.toString()).apply();
        updateFolderLabel();
        append("Pasta dos logs automáticos: " + folderName(tree));
        setStatus(Status.SUCCESS, "Pasta dos logs definida.");
    }

    private Uri logFolder() {
        String s = prefs.getString("logFolder", null);
        return s == null ? null : Uri.parse(s);
    }

    /** "primary:Download/Logs" -> "Download/Logs" (o ID da pasta é o caminho na maioria dos aparelhos). */
    private static String folderName(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int colon = id.indexOf(':');
            String path = colon >= 0 ? id.substring(colon + 1) : id;
            return path.isEmpty() ? "Armazenamento interno" : path;
        } catch (Exception e) {
            return tree.toString();
        }
    }

    private void updateFolderLabel() {
        Uri folder = logFolder();
        logFolderLabel.setText("Pasta: " + (folder == null ? "não escolhida" : folderName(folder)));
    }

    private void toggleMonitor() {
        if (LogMonitorService.running || LogMonitorService.isActive(this)) {
            LogMonitorService.stop(this);
            updateMonitorButton(false);
            return;
        }
        String pkg = monitorPackageField.getText().toString().trim();
        if (pkg.isEmpty()) {
            setStatus(Status.ERROR, "Informe o pacote do app a monitorar.");
            return;
        }
        Uri folder = logFolder();
        if (folder == null) {
            setStatus(Status.ERROR, "Escolha a pasta onde os logs automáticos serão salvos.");
            return;
        }
        String host = null;
        int port = 0;
    if (isRemote()) {
            Object[] t = remoteTarget();
            if (t == null) return;
            host = (String) t[0];
            port = (Integer) t[1];
        }
        prefs.edit().putString("monitorPackage", pkg).apply();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != 0) {
            // Sem a permissão o monitoramento funciona, só não aparece a notificação.
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        LogMonitorService.start(this, host, port, pkg, folder);
        updateMonitorButton(true);
    }

    private void updateMonitorButton(boolean running) {
        toggleMonitorButton.setText(running ? "Parar monitoramento" : "Iniciar monitoramento");
        monitorPackageField.setEnabled(!running);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Ficou ligado mas não está rodando (ex.: o Android recusou retomar no boot): retoma agora.
        if (LogMonitorService.isActive(this) && !LogMonitorService.running) LogMonitorService.resume(this);
        updateMonitorButton(LogMonitorService.running || LogMonitorService.isActive(this));
    }

    @Override
    protected void onDestroy() {
        if (LogMonitorService.listener == monitorListener) LogMonitorService.listener = null;
        super.onDestroy();
    }

    private final LogMonitorService.Listener monitorListener = (message, running) -> {
        append("[auto] " + message);
        updateMonitorButton(running);
        // O monitoramento pode ter achado uma porta nova e salvo nas preferências.
        String saved = prefs.getString("port", "");
        if (!connectPortField.hasFocus() && !saved.equals(connectPortField.getText().toString())) {
            connectPortField.setText(saved);
        }
    };

    /** A porta informada não respondeu e a busca na rede achou a atual: atualiza o campo. */
    private final Adb.PortListener portChanged = newPort -> runOnUiThread(() -> {
        connectPortField.setText(String.valueOf(newPort));
        prefs.edit().putString("port", String.valueOf(newPort)).apply();
        append("Porta de conexão mudou; usando " + newPort + " (encontrada na rede).");
    });

    /** Nome do arquivo escolhido no seletor (o Uri em si costuma ser só um ID). */
    private String fileName(Uri uri) {
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
    private void start(String line) {
        append(line);
        setStatus(Status.INFO, "Em andamento...");
        for (Button b : actions) b.setEnabled(false);
    }

    /** Fim de uma operação (chamado de qualquer thread). */
    private void finish(Status s, String message) {
        runOnUiThread(() -> {
            append((s == Status.ERROR ? "ERRO: " : s == Status.SUCCESS ? "OK: " : "") + message);
            setStatus(s, message);
            for (Button b : actions) b.setEnabled(true);
        });
    }

    private void setStatus(Status s, String message) {
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

    @SuppressWarnings("deprecation")
    private int color(int id) {
        return getResources().getColor(id);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void background(Runnable r) {
        new Thread(r).start();
    }

    private void log(String s) {
        runOnUiThread(() -> append(s));
    }

    private void append(String s) {
        if (log.length() > 0) log.append("\n");
        log.append(clock.format(new Date()) + "  " + s);
    }
}
