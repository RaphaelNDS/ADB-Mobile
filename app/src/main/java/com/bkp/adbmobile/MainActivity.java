package com.bkp.adbmobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final int REQUEST_INSTALL_APK = 12;
    private static final int REQUEST_PUSH_FILE = 13;
    private static final String DEFAULT_PUSH_DIR = "/sdcard/Download";
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
    private List<Devices.Device> devices;
    private LinearLayout devicesList;
    private TextView devicesEmpty;
    private TextView devicesSummary;
    private EditText deviceNameField;

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
        Button installApk = findViewById(R.id.installApk);
        Button pushFile = findViewById(R.id.pushFile);
        Button chooseApp = findViewById(R.id.chooseApp);
        Button appInfo = findViewById(R.id.appInfo);
        actions = new Button[]{notificationPairing, pairManual, run, read, saveLog, installApk, pushFile,
                chooseApp, appInfo};

        hostField.setText(prefs.getString("host", ""));
        connectPortField.setText(prefs.getString("port", "5555"));
        setupDevices();

        notificationPairing.setOnClickListener(v -> startNotificationPairing());
        pairManual.setOnClickListener(v -> manualPairing());
        run.setOnClickListener(v -> confirmAndRun());
        read.setOnClickListener(v -> readValue());
        saveLog.setOnClickListener(v -> chooseLogFile());
        installApk.setOnClickListener(v -> chooseApk());
        pushFile.setOnClickListener(v -> choosePushFile());
        chooseApp.setOnClickListener(v -> chooseApp());
        appInfo.setOnClickListener(v -> showAppInfo(monitorPackageField.getText().toString().trim()));

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

    /** IP e porta digitados no formulário (usados no pareamento); null (com aviso) se inválidos. */
    private Object[] remoteTarget() {
        String host = hostField.getText().toString().trim();
        String portText = connectPortField.getText().toString().trim();
        int port = Devices.parsePort(portText);
        if (host.isEmpty() || port < 0) {
            setStatus(Status.ERROR, "Informe o IP e a porta de conexão do aparelho alvo.");
            return null;
        }
        prefs.edit().putString("host", host).putString("port", portText).apply();
        return new Object[]{host, port};
    }

    // ---------- Aparelhos salvos ----------

    private void setupDevices() {
        devicesList = findViewById(R.id.devicesList);
        devicesEmpty = findViewById(R.id.devicesEmpty);
        devicesSummary = findViewById(R.id.devicesSummary);
        deviceNameField = findViewById(R.id.deviceName);
        devices = Devices.load(prefs);
        findViewById(R.id.saveDevice).setOnClickListener(v -> saveDevice());
        findViewById(R.id.selectAll).setOnClickListener(v -> toggleAll());
        renderDevices();
    }

    /** Recarrega a lista salva (o monitoramento pode ter atualizado uma porta). */
    private void reloadDevices() {
        devices = Devices.load(prefs);
        renderDevices();
    }

    private void renderDevices() {
        devicesList.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        for (final Devices.Device d : devices) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setMinimumHeight((int) (48 * dp));

            CheckBox check = new CheckBox(this);
            check.setChecked(d.selected);
            check.setOnCheckedChangeListener((b, checked) -> {
                d.selected = checked;
                Devices.save(prefs, devices);
                updateDevicesSummary();
            });

            TextView text = new TextView(this);
            text.setText(d.label() + (d.label().equals(d.host) ? "" : "\n" + d.host) + ":" + d.port);
            text.setTextColor(color(R.color.text_primary));
            text.setTextSize(14);
            text.setPadding((int) (4 * dp), (int) (6 * dp), (int) (4 * dp), (int) (6 * dp));
            text.setBackgroundResource(resolveSelectable());
            // Tocar no nome preenche o formulário, para editar ou parear esse aparelho.
            text.setOnClickListener(v -> {
                deviceNameField.setText(d.name);
                hostField.setText(d.host);
                connectPortField.setText(String.valueOf(d.port));
            });
            row.addView(check);
            row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView remove = new TextView(this);
            remove.setText("Remover");
            remove.setTextColor(color(R.color.error));
            remove.setTextSize(13);
            remove.setPadding((int) (10 * dp), (int) (10 * dp), (int) (4 * dp), (int) (10 * dp));
            remove.setBackgroundResource(resolveSelectable());
            remove.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Remover aparelho")
                    .setMessage("Remover " + d.label() + " (" + d.address() + ") da lista?")
                    .setPositiveButton("Remover", (dlg, w) -> {
                        devices.remove(d);
                        Devices.save(prefs, devices);
                        renderDevices();
                    })
                    .setNegativeButton("Cancelar", null)
                    .show());
            row.addView(remove);
            devicesList.addView(row);
        }
        devicesEmpty.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
        updateDevicesSummary();
    }

    private int resolveSelectable() {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return value.resourceId;
    }

    private void updateDevicesSummary() {
        int n = Devices.selected(devices).size();
        devicesSummary.setVisibility(devices.isEmpty() ? View.GONE : View.VISIBLE);
        devicesSummary.setText(n == 0 ? "Nenhum aparelho marcado."
                : n == 1 ? "1 aparelho marcado." : n + " aparelhos marcados: os comandos rodam em todos ao mesmo tempo.");
        ((TextView) findViewById(R.id.selectAll)).setText(
                !devices.isEmpty() && n == devices.size() ? "Desmarcar todos" : "Marcar todos");
    }

    private void toggleAll() {
        boolean all = !devices.isEmpty() && Devices.selected(devices).size() == devices.size();
        for (Devices.Device d : devices) d.selected = !all;
        Devices.save(prefs, devices);
        renderDevices();
    }

    /** Adiciona o aparelho do formulário ou, se o IP já está na lista, atualiza nome e porta. */
    private void saveDevice() {
        Object[] t = remoteTarget();
        if (t == null) return;
        String host = (String) t[0];
        int port = (Integer) t[1];
        String name = deviceNameField.getText().toString().trim();
        Devices.Device d = Devices.find(devices, host);
        if (d == null) {
            devices.add(new Devices.Device(name, host, port, true));
            append("Aparelho salvo: " + (name.isEmpty() ? host : name) + " (" + host + ":" + port + ")");
        } else {
            d.name = name;
            d.port = port;
            d.selected = true;
            append("Aparelho atualizado: " + d.label() + " (" + d.address() + ")");
        }
        Devices.save(prefs, devices);
        renderDevices();
        deviceNameField.setText("");
        setStatus(Status.SUCCESS, "Aparelho salvo e marcado.");
    }

    /** Aparelhos marcados; null (com aviso) se nenhum. */
    private List<Devices.Device> selectedDevices() {
        List<Devices.Device> list = Devices.selected(devices);
        if (list.isEmpty()) {
            setStatus(Status.ERROR, devices.isEmpty()
                    ? "Salve ao menos um aparelho em \"Outro aparelho\"."
                    : "Marque ao menos um aparelho na lista.");
            return null;
        }
        return list;
    }

    /** Para log e monitoramento, que trabalham com um aparelho: exige exatamente um marcado. */
    private Object[] singleTarget(String what) {
        List<Devices.Device> list = selectedDevices();
        if (list == null) return null;
        if (list.size() > 1) {
            setStatus(Status.ERROR, what + " funciona com um aparelho por vez. Deixe só um marcado.");
            return null;
        }
        Devices.Device d = list.get(0);
        return new Object[]{d.host, d.port};
    }

    /** O que fazer em cada aparelho; devolve o resumo para o registro ("" se não houver). */
    private interface DeviceTask {
        String run(Devices.Device d) throws Exception;
    }

    /**
     * Roda {@code task} em todos os aparelhos ao mesmo tempo (cada um tem a sua conexão) e
     * termina com um resumo quando o último responder.
     */
    private void runOnDevices(final List<Devices.Device> targets, final String verb, final DeviceTask task) {
        final int total = targets.size();
        final AtomicInteger done = new AtomicInteger();
        final List<String> failed = Collections.synchronizedList(new ArrayList<>());
        final String[] single = new String[1];
        for (final Devices.Device d : targets) {
            background(() -> {
                String prefix = "[" + d.label() + "] ";
                try {
                    String result = task.run(d);
                    single[0] = result.isEmpty() ? verb + " em " + d.label() + "." : d.label() + ": " + result;
                    log(prefix + "OK" + (result.isEmpty() ? "" : ": " + result));
                } catch (AdbPairingRequiredException e) {
                    failed.add(d.label());
                    single[0] = d.label() + " exige pareamento. Conclua a etapa 1.";
                    log(prefix + "ERRO: exige pareamento. Conclua a etapa 1.");
                } catch (Exception e) {
                    failed.add(d.label());
                    single[0] = "Falha em " + d.label() + ": " + Adb.describe(e);
                    log(prefix + "ERRO: " + Adb.describe(e));
                }
                if (done.incrementAndGet() < total) return;
                if (total == 1) {
                    finish(failed.isEmpty() ? Status.SUCCESS : Status.ERROR, single[0]);
                } else if (failed.isEmpty()) {
                    finish(Status.SUCCESS, verb + " nos " + total + " aparelhos.");
                } else {
                    finish(Status.ERROR, "Falhou em " + failed.size() + " de " + total + ": "
                            + TextUtils.join(", ", failed) + ".");
                }
            });
        }
    }

    /** Avisa quando a porta de um aparelho mudou e a busca na rede achou a atual. */
    private Adb.PortListener portChangedFor(final String host) {
        return newPort -> {
            Devices.updatePort(prefs, host, newPort);
            runOnUiThread(() -> {
                reloadDevices();
                if (host.equals(hostField.getText().toString().trim())) {
                    connectPortField.setText(String.valueOf(newPort));
                }
                append("Porta de " + host + " mudou; usando " + newPort + " (encontrada na rede).");
            });
        };
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
        String where = "no aparelho alvo";
        if (isRemote()) {
            List<Devices.Device> targets = selectedDevices();
            if (targets == null) return;
            if (targets.size() > 1) where = "em " + targets.size() + " aparelhos";
            else where = "em " + targets.get(0).label();
        }
        new AlertDialog.Builder(this)
                .setTitle(preset.label)
                .setMessage("Executar " + where + "?\n\nadb shell " + cmd)
                .setPositiveButton("Executar", (d, w) -> runCommand(cmd, preset.read))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Executa {@code cmd} no aparelho alvo e, se {@code read} não for null, lê o valor resultante. */
    private void runCommand(final String cmd, final String read) {
        if (isRemote()) {
            final List<Devices.Device> targets = selectedDevices();
            if (targets == null) return;
            start(targetsLabel(targets) + " $ " + cmd);
            log("Conectando... se o alvo solicitar, autorize a depuração na tela dele.");
            runOnDevices(targets, read == null ? "Executado" : "Aplicado", d -> {
                String[] out = read == null
                        ? Adb.remoteShell(this, d.host, d.port, portChangedFor(d.host), cmd)
                        : Adb.remoteShell(this, d.host, d.port, portChangedFor(d.host), cmd, read);
                logOutput("[" + d.label() + "] ", out[0]);
                return read == null ? "" : valueName(read) + " = " + out[1];
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
                    logOutput("", Adb.su(cmd));
                    finish(Status.SUCCESS, read == null ? "Executado via root."
                            : "Aplicado via root. " + valueName(read) + " = " + Adb.su(read));
                    return;
                }
                // 2) ADB pela Depuração sem fio
                logOutput("", Adb.shell(this, cmd));
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
            final List<Devices.Device> targets = selectedDevices();
            if (targets == null) return;
            start(targetsLabel(targets) + " $ " + read);
            runOnDevices(targets, "Lido", d -> name + " = "
                    + Adb.remoteShell(this, d.host, d.port, portChangedFor(d.host), read)[0]);
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
    private void logOutput(String prefix, String out) {
        if (out == null || out.isEmpty()) return;
        final int max = 20000;
        String text = out.length() <= max ? out
                : out.substring(0, max) + "\n... (saída cortada; " + out.length() + " caracteres)";
        // Com prefixo (vários aparelhos), a saída começa na linha de baixo para não desalinhar.
        log(prefix.isEmpty() ? text : prefix + "\n" + text);
    }

    /** "[Quiosque 1]" para um aparelho, "[3 aparelhos]" para vários. */
    private static String targetsLabel(List<Devices.Device> targets) {
        return targets.size() == 1 ? "[" + targets.get(0).label() + "]" : "[" + targets.size() + " aparelhos]";
    }

    // ---------- Instalar APK ----------

    /** Escolhe o .apk no armazenamento do celular. */
    private void chooseApk() {
        if (isRemote() && selectedDevices() == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Alguns gerenciadores não marcam o .apk com o tipo certo.
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive", "application/octet-stream"});
        try {
            startActivityForResult(intent, REQUEST_INSTALL_APK);
        } catch (Exception e) {
            setStatus(Status.ERROR, "Nenhum seletor de arquivos disponível neste aparelho.");
        }
    }

    /** Copia o APK escolhido para o cache, lê nome/versão e pede confirmação. */
    private void onApkChosen(final Uri uri) {
        if (uri == null) {
            setStatus(Status.INFO, "Instalação cancelada.");
            return;
        }
        final String name = fileName(uri);
        setStatus(Status.INFO, "Lendo " + name + "...");
        background(() -> {
            final File apk = new File(getCacheDir(), "install.apk");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(apk)) {
                if (in == null) throw new java.io.IOException("não foi possível abrir o arquivo");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Exception e) {
                runOnUiThread(() -> setStatus(Status.ERROR, "Não foi possível ler " + name + ": " + Adb.describe(e)));
                return;
            }
            PackageManager pm = getPackageManager();
            final PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), 0);
            if (info == null) {
                apk.delete();
                runOnUiThread(() -> setStatus(Status.ERROR, name + " não é um APK válido."));
                return;
            }
            info.applicationInfo.sourceDir = apk.getPath();
            info.applicationInfo.publicSourceDir = apk.getPath();
            final String app = info.applicationInfo.loadLabel(pm) + " " + info.versionName;
            final String details = app + "\n" + info.packageName + "\n" + name + " ("
                    + String.format(Locale.getDefault(), "%.1f MB", apk.length() / 1048576.0) + ")";
            runOnUiThread(() -> confirmInstall(apk, app, details));
        });
    }

    private void confirmInstall(final File apk, final String app, String details) {
        String where = "neste aparelho";
        if (isRemote()) {
            List<Devices.Device> targets = selectedDevices();
            if (targets == null) return;
            where = targets.size() > 1 ? "em " + targets.size() + " aparelhos" : "em " + targets.get(0).label();
        }
        setStatus(Status.INFO, "Confirme a instalação.");
        new AlertDialog.Builder(this)
                .setTitle("Instalar APK")
                .setMessage("Instalar " + where + "?\n\n" + details
                        + "\n\nSe o app já existir, é atualizado mantendo os dados.")
                .setPositiveButton("Instalar", (d, w) -> installApk(apk, app))
                .setNegativeButton("Cancelar", (d, w) -> setStatus(Status.INFO, "Instalação cancelada."))
                .show();
    }

    private void installApk(final File apk, final String app) {
        if (isRemote()) {
            final List<Devices.Device> targets = selectedDevices();
            if (targets == null) return;
            start(targetsLabel(targets) + " instalar " + app);
            log("Enviando o APK... se o alvo solicitar, autorize a depuração na tela dele.");
            runOnDevices(targets, "Instalado", d -> {
                Adb.Shell shell = Adb.openRemote(this, d.host, d.port, portChangedFor(d.host));
                try {
                    shell.install(apk);
                } finally {
                    shell.close();
                }
                return "";
            });
            return;
        }
        start("instalar " + app);
        background(() -> {
            try {
                Adb.openLocal(this).install(apk);
                finish(Status.SUCCESS, "Instalado neste aparelho.");
            } catch (AdbPairingRequiredException e) {
                finish(Status.ERROR, "Aparelho ainda não pareado. Conclua a etapa 1.");
            } catch (Exception e) {
                finish(Status.ERROR, "Falha na instalação: " + Adb.describe(e));
            }
        });
    }

    // ---------- Enviar arquivo ----------

    /** Escolhe qualquer arquivo no armazenamento do celular para enviar (como "adb push"). */
    private void choosePushFile() {
        if (isRemote() && selectedDevices() == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_PUSH_FILE);
        } catch (Exception e) {
            setStatus(Status.ERROR, "Nenhum seletor de arquivos disponível neste aparelho.");
        }
    }

    /** Copia o arquivo escolhido para o cache e pergunta a pasta de destino. */
    private void onPushFileChosen(final Uri uri) {
        if (uri == null) {
            setStatus(Status.INFO, "Envio cancelado.");
            return;
        }
        // Barra no nome criaria subpastas no destino.
        final String name = fileName(uri).replace('/', '_');
        setStatus(Status.INFO, "Lendo " + name + "...");
        background(() -> {
            File dir = new File(getCacheDir(), "push");
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            dir.mkdirs();
            final File file = new File(dir, name);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(file)) {
                if (in == null) throw new java.io.IOException("não foi possível abrir o arquivo");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Exception e) {
                runOnUiThread(() -> setStatus(Status.ERROR, "Não foi possível ler " + name + ": " + Adb.describe(e)));
                return;
            }
            runOnUiThread(() -> confirmPush(file));
        });
    }

    private void confirmPush(final File file) {
        String where = "neste aparelho";
        if (isRemote()) {
            List<Devices.Device> targets = selectedDevices();
            if (targets == null) return;
            where = targets.size() > 1 ? "em " + targets.size() + " aparelhos" : "em " + targets.get(0).label();
        }
        final EditText dirField = new EditText(this);
        dirField.setSingleLine(true);
        dirField.setText(prefs.getString("pushDir", DEFAULT_PUSH_DIR));
        LinearLayout box = new LinearLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(dirField, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        setStatus(Status.INFO, "Confirme o envio.");
        new AlertDialog.Builder(this)
                .setTitle("Enviar arquivo")
                .setMessage("Enviar " + where + "?\n\n" + file.getName() + " ("
                        + String.format(Locale.getDefault(), "%.1f MB", file.length() / 1048576.0)
                        + ")\n\nPasta de destino no aparelho (arquivo com o mesmo nome é substituído):")
                .setView(box)
                .setPositiveButton("Enviar", (d, w) -> {
                    String dir = dirField.getText().toString().trim();
                    while (dir.length() > 1 && dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
                    if (!dir.startsWith("/")) {
                        setStatus(Status.ERROR, "Informe a pasta de destino com o caminho completo (ex.: "
                                + DEFAULT_PUSH_DIR + ").");
                        return;
                    }
                    prefs.edit().putString("pushDir", dir).apply();
                    pushFile(file, (dir.equals("/") ? "" : dir) + "/" + file.getName());
                })
                .setNegativeButton("Cancelar", (d, w) -> setStatus(Status.INFO, "Envio cancelado."))
                .show();
    }

    private void pushFile(final File file, final String remotePath) {
        if (isRemote()) {
            final List<Devices.Device> targets = selectedDevices();
            if (targets == null) return;
            start(targetsLabel(targets) + " push " + file.getName() + " " + remotePath);
            log("Enviando o arquivo... se o alvo solicitar, autorize a depuração na tela dele.");
            runOnDevices(targets, "Enviado", d -> {
                Adb.Shell shell = Adb.openRemote(this, d.host, d.port, portChangedFor(d.host));
                try {
                    shell.push(file, remotePath);
                } finally {
                    shell.close();
                }
                return "enviado para " + remotePath;
            });
            return;
        }
        start("push " + file.getName() + " " + remotePath);
        background(() -> {
            try {
                Adb.openLocal(this).push(file, remotePath);
                finish(Status.SUCCESS, "Enviado neste aparelho: " + remotePath);
            } catch (AdbPairingRequiredException e) {
                finish(Status.ERROR, "Aparelho ainda não pareado. Conclua a etapa 1.");
            } catch (Exception e) {
                finish(Status.ERROR, "Falha no envio: " + Adb.describe(e));
            }
        });
    }

    // ---------- Apps do aparelho ----------

    /** Tarefa que usa uma sessão no aparelho alvo (roda em segundo plano). */
    private interface ShellTask {
        void run(Adb.Shell shell) throws Exception;
    }

    /**
     * Abre o aparelho alvo (este ou o único marcado), roda {@code task} e fecha. Erros viram status
     * de erro com {@code what} na frente.
     */
    private void withTargetShell(String what, String line, final String errorPrefix, final ShellTask task) {
        Object[] t = null;
        if (isRemote()) {
            t = singleTarget(what);
            if (t == null) return;
        }
        final Object[] target = t;
        start((target != null ? "[" + target[0] + ":" + target[1] + "] " : "") + line);
        background(() -> {
            try {
                Adb.Shell shell = target != null
                        ? Adb.openRemote(this, (String) target[0], (Integer) target[1], portChangedFor((String) target[0]))
                        : Adb.openLocal(this);
                try {
                    task.run(shell);
                } finally {
                    shell.close();
                }
            } catch (AdbPairingRequiredException e) {
                finish(Status.ERROR, target != null ? "O aparelho alvo exige pareamento. Conclua a etapa 1."
                        : "Aparelho ainda não pareado. Conclua a etapa 1.");
            } catch (Exception e) {
                finish(Status.ERROR, errorPrefix + Adb.describe(e));
            }
        });
    }

    /** Busca os apps instalados no aparelho alvo e abre a lista para escolher um. */
    private void chooseApp() {
        final boolean local = !isRemote();
        withTargetShell("Escolher app", "listar apps instalados", "Não foi possível listar os apps: ", shell -> {
            List<AppInfo.App> apps = AppInfo.list(shell);
            // Neste aparelho dá para mostrar o nome dos apps; no remoto o ADB só informa o pacote.
            if (local) {
                PackageManager pm = getPackageManager();
                for (AppInfo.App a : apps) {
                    try {
                        a.label = pm.getApplicationLabel(pm.getApplicationInfo(a.pkg, 0)).toString();
                    } catch (Exception ignored) {
                    }
                }
                AppInfo.sort(apps);
            }
            int user = 0;
            for (AppInfo.App a : apps) if (!a.system) user++;
            finish(Status.INFO, apps.size() + " apps encontrados (" + user + " instalados pelo usuário).");
            runOnUiThread(() -> showAppPicker(apps));
        });
    }

    private void showAppPicker(final List<AppInfo.App> apps) {
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, (int) (8 * dp), pad, 0);

        final EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Buscar por nome ou pacote");
        box.addView(search);

        final CheckBox showSystem = new CheckBox(this);
        showSystem.setText("Mostrar apps do sistema");
        box.addView(showSystem);

        final android.widget.ListView listView = new android.widget.ListView(this);
        final List<AppInfo.App> shown = new ArrayList<>();
        final ArrayAdapter<AppInfo.App> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, shown);
        listView.setAdapter(adapter);
        box.addView(listView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.5)));

        final Runnable filter = () -> {
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (AppInfo.App a : apps) {
                if (a.system && !showSystem.isChecked() && !a.foreground) continue;
                if (!q.isEmpty() && !a.pkg.toLowerCase(Locale.ROOT).contains(q)
                        && (a.label == null || !a.label.toLowerCase(Locale.ROOT).contains(q))) continue;
                shown.add(a);
            }
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                filter.run();
            }
        });
        showSystem.setOnCheckedChangeListener((b, checked) -> filter.run());
        filter.run();

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Escolher app")
                .setView(box)
                .setNegativeButton("Fechar", null)
                .show();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            showAppInfo(shown.get(position).pkg);
        });
    }

    /** Lê e mostra as informações do pacote no aparelho alvo. */
    private void showAppInfo(final String pkg) {
        if (pkg.isEmpty()) {
            setStatus(Status.ERROR, "Informe o pacote ou toque em \"Escolher app\".");
            return;
        }
        if (!AppInfo.validPackage(pkg)) {
            setStatus(Status.ERROR, "Pacote inválido: " + pkg);
            return;
        }
        final boolean local = !isRemote();
        withTargetShell("Informações do app", "informações de " + pkg, "Não foi possível ler o app: ", shell -> {
            AppInfo.Details d = AppInfo.details(shell, pkg);
            String title = pkg;
            if (local) {
                try {
                    PackageManager pm = getPackageManager();
                    title = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                } catch (Exception ignored) {
                }
            }
            final String dialogTitle = title;
            finish(Status.INFO, "Informações de " + pkg + " carregadas.");
            runOnUiThread(() -> showAppDetails(dialogTitle, d));
        });
    }

    private void showAppDetails(String title, final AppInfo.Details d) {
        float dp = getResources().getDisplayMetrics().density;
        TextView text = new TextView(this);
        text.setText(d.text);
        text.setTextIsSelectable(true);
        text.setTextSize(13);
        text.setTextColor(color(R.color.text_primary));
        text.setLineSpacing(2 * dp, 1);
        int pad = (int) (20 * dp);
        text.setPadding(pad, (int) (8 * dp), pad, (int) (8 * dp));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(text);

        boolean current = d.pkg.equals(monitorPackageField.getText().toString().trim());
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setNeutralButton("Saída completa", (dlg, w) -> {
                    append("dumpsys package " + d.pkg + ":");
                    logOutput("", d.dumpsys);
                    setStatus(Status.INFO, "Saída completa do dumpsys no registro abaixo.");
                })
                .setNegativeButton("Fechar", null);
        if (!current) {
            b.setPositiveButton("Usar este pacote", (dlg, w) -> {
                if (!monitorPackageField.isEnabled()) {
                    setStatus(Status.ERROR, "Pare o monitoramento antes de trocar o pacote.");
                    return;
                }
                monitorPackageField.setText(d.pkg);
                prefs.edit().putString("monitorPackage", d.pkg).apply();
                setStatus(Status.SUCCESS, "Pacote definido: " + d.pkg);
            });
        }
        b.show();
    }

    // ---------- Log do aparelho ----------

    /** Abre o seletor do sistema para escolher onde salvar o log (.txt). */
    private void chooseLogFile() {
        Object[] t = null;
        if (isRemote()) {
            t = singleTarget("Gerar log");
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
        if (requestCode == REQUEST_INSTALL_APK) {
            onApkChosen(resultCode == RESULT_OK && data != null ? data.getData() : null);
            return;
        }
        if (requestCode == REQUEST_PUSH_FILE) {
            onPushFileChosen(resultCode == RESULT_OK && data != null ? data.getData() : null);
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
            t = singleTarget("Gerar log");
            if (t == null) return;
        }
        saveLog(data.getData(), t);
    }

    /** Coleta o logcat do aparelho alvo e grava no arquivo escolhido. */
    private void saveLog(final Uri uri, final Object[] t) {
        start((t != null ? "[" + t[0] + ":" + t[1] + "] " : "") + "$ " + Adb.LOG_COMMAND);
        background(() -> {
            try {
                Adb.Shell shell = t != null ? Adb.openRemote(this, (String) t[0], (Integer) t[1],
                        portChangedFor((String) t[0]))
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
            Object[] t = singleTarget("O monitoramento");
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
        // O monitoramento pode ter achado uma porta nova e salvo na lista de aparelhos.
        reloadDevices();
    };

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
