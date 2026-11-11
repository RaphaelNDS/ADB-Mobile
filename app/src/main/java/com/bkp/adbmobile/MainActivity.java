package com.bkp.adbmobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final int REQUEST_PULL_FILE = 14;
    private static final int REQUEST_SAVE_SCREENSHOT = 15;
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
    /** Itens da lista de comandos: "Meus comandos" (se houver) e depois os prontos. */
    private final List<AdbCommands.Preset> presets = new ArrayList<>();
    private ArrayAdapter<AdbCommands.Preset> presetAdapter;
    private SavedCommands savedCommands;
    /** Posição já mostrada no campo; o Spinner avisa seleções que não devem trocar o texto. */
    private int shownPreset;
    private Button deleteCommandButton;
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
        Button pullFile = findViewById(R.id.pullFile);
        Button screenshot = findViewById(R.id.screenshot);
        Button chooseApp = findViewById(R.id.chooseApp);
        Button appInfo = findViewById(R.id.appInfo);
        actions = new Button[]{notificationPairing, pairManual, run, read, saveLog, installApk, pushFile,
                pullFile, screenshot, chooseApp, appInfo};

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
        pullFile.setOnClickListener(v -> browseRemoteFiles());
        screenshot.setOnClickListener(v -> captureScreen());
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
     * termina com um resumo quando o último responder. Com vários aparelhos, o resumo no registro
     * agrupa as falhas pelo motivo e lista o resultado de cada aparelho que deu certo.
     */
    private void runOnDevices(final List<Devices.Device> targets, final String verb, final DeviceTask task) {
        final int total = targets.size();
        final AtomicInteger done = new AtomicInteger();
        // Resultado de cada aparelho, na ordem da lista: motivo da falha (null se deu certo) e saída.
        final String[] errors = new String[total];
        final String[] results = new String[total];
        for (int i = 0; i < total; i++) {
            final int index = i;
            final Devices.Device d = targets.get(i);
            background(() -> {
                String prefix = "[" + d.label() + "] ";
                try {
                    results[index] = task.run(d);
                    log(prefix + "OK" + (results[index].isEmpty() ? "" : ": " + results[index]));
                } catch (AdbPairingRequiredException e) {
                    errors[index] = NEEDS_PAIRING;
                    log(prefix + "ERRO: " + errors[index]);
                } catch (Exception e) {
                    errors[index] = commandError(e);
                    log(prefix + "ERRO: " + errors[index]);
                }
                int finished = done.incrementAndGet();
                if (finished < total) {
                    showDevicesProgress(done, finished, total);
                    return;
                }
                // O incrementAndGet garante que o último vê os resultados gravados pelos outros.
                finishDevices(targets, verb, errors, results);
            });
        }
    }

    /** "Em andamento... 1 de 3 concluído" no status, enquanto não há outro progresso na tela. */
    private void showDevicesProgress(final AtomicInteger done, final int finished, final int total) {
        runOnUiThread(() -> {
            // Ignora se a operação já terminou ou se um aviso mais novo já foi mostrado.
            if (activeBoard != null || done.get() != finished) return;
            setStatus(Status.INFO, "Em andamento... " + finished + " de " + total
                    + (finished == 1 ? " concluído" : " concluídos"));
        });
    }

    private void finishDevices(List<Devices.Device> targets, String verb, String[] errors, String[] results) {
        int total = targets.size();
        List<String> unpaired = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (NEEDS_PAIRING.equals(errors[i])) unpaired.add(targets.get(i).label());
        }
        if (!unpaired.isEmpty()) {
            final String names = TextUtils.join(", ", unpaired);
            runOnUiThread(() -> showPairingDialog(names));
        }
        if (total == 1) {
            String label = targets.get(0).label();
            if (errors[0] != null) finish(Status.ERROR, "Falha em " + label + ": " + errors[0]);
            else finish(Status.SUCCESS, results[0].isEmpty() ? verb + " em " + label + "." : label + ": " + results[0]);
            return;
        }
        // Falhas agrupadas pelo motivo (o mesmo erro em vários aparelhos aparece uma vez só).
        Map<String, List<String>> byReason = new LinkedHashMap<>();
        List<String> okPlain = new ArrayList<>();
        List<String> okWithResult = new ArrayList<>();
        List<String> failedLabels = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            String label = targets.get(i).label();
            if (errors[i] != null) {
                failedLabels.add(label);
                List<String> list = byReason.get(errors[i]);
                if (list == null) byReason.put(errors[i], list = new ArrayList<>());
                list.add(label);
            } else if (results[i].isEmpty()) {
                okPlain.add(label);
            } else {
                okWithResult.add(label + ": " + results[i]);
            }
        }
        int ok = total - failedLabels.size();
        StringBuilder summary = new StringBuilder("Resumo: " + ok + " de " + total + " OK");
        if (!failedLabels.isEmpty()) summary.append(", ").append(failedLabels.size()).append(" com falha");
        for (Map.Entry<String, List<String>> e : byReason.entrySet()) {
            summary.append("\n  FALHA (").append(e.getValue().size()).append("): ")
                    .append(TextUtils.join(", ", e.getValue())).append("\n    ").append(e.getKey());
        }
        if (!okPlain.isEmpty()) {
            summary.append("\n  OK (").append(okPlain.size()).append("): ").append(TextUtils.join(", ", okPlain));
        }
        for (String line : okWithResult) summary.append("\n  OK ").append(line);
        log(summary.toString());

        if (failedLabels.isEmpty()) {
            finish(Status.SUCCESS, verb + " nos " + total + " aparelhos.");
        } else if (byReason.size() == 1) {
            finish(Status.ERROR, "OK em " + ok + " de " + total + ". Falhou em "
                    + TextUtils.join(", ", failedLabels) + ": " + byReason.keySet().iterator().next());
        } else {
            finish(Status.ERROR, "OK em " + ok + " de " + total + ". Falhou em "
                    + TextUtils.join(", ", failedLabels) + " (motivos no resumo do registro).");
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

    /** Tag das views de título de grupo na lista de comandos. */
    private static final String HEADER = "header";

    private void setupCommands() {
        presetSpinner = findViewById(R.id.preset);
        commandField = findViewById(R.id.command);
        savedCommands = new SavedCommands(this);
        deleteCommandButton = findViewById(R.id.deleteCommand);
        findViewById(R.id.saveCommand).setOnClickListener(v -> askSaveCommand());
        deleteCommandButton.setOnClickListener(v -> confirmDeleteCommand());
        loadPresets();
        final float dp = getResources().getDisplayMetrics().density;
        ArrayAdapter<AdbCommands.Preset> adapter = new ArrayAdapter<AdbCommands.Preset>(this,
                android.R.layout.simple_spinner_item, presets) {
            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }

            @Override
            public boolean isEnabled(int position) {
                return !getItem(position).isHeader();
            }

            // O Spinner só aceita um tipo de view (getViewTypeCount() = 1), então a view reciclada
            // pode ser de título ou de item; a tag HEADER diferencia as duas.
            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                AdbCommands.Preset p = getItem(position);
                boolean recycledHeader = convertView != null && HEADER.equals(convertView.getTag());
                if (!p.isHeader()) {
                    return super.getDropDownView(position, recycledHeader ? null : convertView, parent);
                }
                // Título do grupo: pequeno, em maiúsculas e na cor do app, como os títulos dos cartões.
                TextView title = recycledHeader ? (TextView) convertView : new TextView(getContext());
                title.setTag(HEADER);
                title.setText(p.label.toUpperCase(Locale.getDefault()));
                title.setTextSize(12);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setLetterSpacing(0.08f);
                title.setTextColor(color(R.color.primary));
                title.setBackgroundColor(color(R.color.primary_tint));
                title.setPadding((int) (16 * dp), (int) (10 * dp), (int) (16 * dp), (int) (6 * dp));
                return title;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetAdapter = adapter;
        presetSpinner.setAdapter(adapter);

        final int saved = savedPresetPosition();
        // O Spinner avisa a seleção inicial ao desenhar; ela não deve sobrescrever o texto salvo.
        shownPreset = saved;
        presetSpinner.setSelection(saved, false);
        commandField.setText(prefs.getString("command", fill(presets.get(saved).command)));
        updatePresetButtons();
        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == shownPreset) return;
                if (presets.get(position).isHeader()) {
                    // Título de grupo não é comando: pula para o primeiro item do grupo.
                    presetSpinner.setSelection(Math.min(position + 1, presets.size() - 1));
                    return;
                }
                shownPreset = position;
                prefs.edit().putString("presetLabel", presets.get(position).key()).apply();
                commandField.setText(fill(presets.get(position).command));
                if (presets.get(position).command.isEmpty()) commandField.requestFocus();
                updatePresetButtons();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** Monta a lista: "Meus comandos" no topo, quando há algum salvo, e depois os prontos. */
    private void loadPresets() {
        presets.clear();
        List<AdbCommands.Preset> mine = savedCommands.load();
        if (!mine.isEmpty()) {
            presets.add(AdbCommands.header(AdbCommands.MY_COMMANDS));
            presets.addAll(mine);
        }
        Collections.addAll(presets, AdbCommands.PRESETS);
    }

    /**
     * Recarrega a lista depois de salvar ou excluir e seleciona o item de {@code key} (ou o
     * primeiro, se ele não existe mais). Não troca o texto do campo.
     */
    private void reloadPresets(String key) {
        loadPresets();
        presetAdapter.notifyDataSetChanged();
        prefs.edit().putString("presetLabel", key).apply();
        int position = savedPresetPosition();
        shownPreset = position;
        presetSpinner.setSelection(position, false);
        updatePresetButtons();
    }

    /** Posição do comando escolhido da última vez (ver {@link AdbCommands#positionOf}). */
    private int savedPresetPosition() {
        return AdbCommands.positionOf(presets, prefs.getString("presetLabel", null), prefs.getInt("preset", 0));
    }

    private AdbCommands.Preset selectedPreset() {
        int i = presetSpinner.getSelectedItemPosition();
        AdbCommands.Preset p = presets.get(i < 0 ? 0 : i);
        return p.isHeader() ? presets.get(savedPresetPosition()) : p;
    }

    private void updatePresetButtons() {
        AdbCommands.Preset p = selectedPreset();
        readButton.setVisibility(p.read != null ? View.VISIBLE : View.GONE);
        deleteCommandButton.setVisibility(p.userSaved ? View.VISIBLE : View.GONE);
    }

    /** Pede um nome e salva o texto do campo em "Meus comandos". */
    private void askSaveCommand() {
        final String cmd = commandField.getText().toString().trim();
        if (cmd.isEmpty()) {
            setStatus(Status.ERROR, "Digite o comando antes de salvar.");
            return;
        }
        final AdbCommands.Preset current = selectedPreset();
        final float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (24 * dp), (int) (8 * dp), (int) (24 * dp), 0);
        final EditText nameField = new EditText(this);
        nameField.setHint("Nome do comando");
        nameField.setSingleLine(true);
        // Ao editar um salvo, sugere o mesmo nome para atualizar em vez de duplicar.
        if (current.userSaved) {
            nameField.setText(current.label);
            nameField.setSelection(current.label.length());
        }
        box.addView(nameField);
        final CheckBox confirmBox = new CheckBox(this);
        confirmBox.setText("Pedir confirmação antes de executar");
        confirmBox.setChecked(current.confirm);
        box.addView(confirmBox);
        TextView preview = new TextView(this);
        preview.setText("adb shell " + cmd);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextSize(12);
        preview.setTextColor(color(R.color.text_primary));
        preview.setPadding(0, (int) (8 * dp), 0, 0);
        box.addView(preview);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Salvar em \"" + AdbCommands.MY_COMMANDS + "\"")
                .setView(box)
                .setPositiveButton("Salvar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        // Botão tratado à mão para a janela não fechar quando o nome está vazio.
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String name = nameField.getText().toString().trim();
            if (name.isEmpty()) {
                nameField.setError("Informe um nome");
                return;
            }
            boolean sameItem = current.userSaved && current.label.equals(name);
            if (!sameItem && savedCommands.contains(name)) {
                new AlertDialog.Builder(this)
                        .setMessage("Já existe um comando chamado \"" + name + "\". Substituir?")
                        .setPositiveButton("Substituir", (d, w) -> {
                            dialog.dismiss();
                            saveCommand(name, cmd, confirmBox.isChecked());
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
                return;
            }
            dialog.dismiss();
            saveCommand(name, cmd, confirmBox.isChecked());
        }));
        dialog.show();
    }

    private void saveCommand(String name, String cmd, boolean confirm) {
        boolean replaced = savedCommands.put(name, cmd, confirm);
        prefs.edit().putString("command", cmd).apply();
        reloadPresets(AdbCommands.saved(name, cmd, confirm).key());
        String message = (replaced ? "Comando atualizado: " : "Comando salvo: ") + name;
        append(message);
        setStatus(Status.SUCCESS, message);
    }

    private void confirmDeleteCommand() {
        final AdbCommands.Preset p = selectedPreset();
        if (!p.userSaved) return;
        new AlertDialog.Builder(this)
                .setTitle("Excluir comando")
                .setMessage("Excluir \"" + p.label + "\" de \"" + AdbCommands.MY_COMMANDS + "\"?\n\nadb shell "
                        + p.command)
                .setPositiveButton("Excluir", (d, w) -> {
                    savedCommands.remove(p.label);
                    // Chave que não existe: volta para o primeiro comando da lista. Com null, valeria
                    // o índice antigo da 2.9, que aponta para um comando qualquer.
                    reloadPresets("");
                    AdbCommands.Preset now = selectedPreset();
                    String text = fill(now.command);
                    commandField.setText(text);
                    prefs.edit().putString("presetLabel", now.key()).putString("command", text).apply();
                    String message = "Comando excluído: " + p.label;
                    append(message);
                    setStatus(Status.INFO, message);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Troca {pacote} pelo pacote informado no Log automático. */
    private String fill(String command) {
        String pkg = monitorPackageField.getText().toString().trim();
        return pkg.isEmpty() ? command : command.replace(AdbCommands.PACKAGE, pkg);
    }

    /** Erro do comando em português; troca o texto do Android por uma explicação quando conhecida. */
    private static String commandError(Exception e) {
        String friendly = AdbCommands.failure(Adb.describe(e));
        return friendly != null ? friendly : Adb.describe(e);
    }

    /** Lança a falha indicada pela saída do comando (o shell não devolve o código de saída). */
    private static void checkOutput(String out) throws IOException {
        String failure = AdbCommands.failure(out);
        if (failure != null) throw new IOException(failure);
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
            runCommand(cmd, preset);
            return;
        }
        String where = "no aparelho alvo";
        if (isRemote()) {
            List<Devices.Device> targets = selectedDevices();
            if (targets == null) return;
            if (targets.size() > 1) where = "em " + targets.size() + " aparelhos";
            else where = "em " + targets.get(0).label();
        }
        String message = "Executar " + where + "?\n\nadb shell " + cmd;
        if (preset.warning != null) message += "\n\nAtenção: " + preset.warning;
        new AlertDialog.Builder(this)
                .setTitle(preset.label)
                .setMessage(message)
                .setPositiveButton("Executar", (d, w) -> runCommand(cmd, preset))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /**
     * Executa {@code cmd} no aparelho alvo e, se o comando escolhido tem leitura, lê o valor
     * resultante. Falhas conhecidas na saída (ex.: erros do dpm) viram erro em português.
     */
    private void runCommand(final String cmd, AdbCommands.Preset preset) {
        final String read = preset.read;
        final String name = preset.readName;
        if (isRemote()) {
            final List<Devices.Device> targets = selectedDevices();
            if (targets == null) return;
            start(targetsLabel(targets) + " $ " + cmd);
            log("Conectando... se o alvo solicitar, autorize a depuração na tela dele.");
            runOnDevices(targets, read == null ? "Executado" : "Aplicado", d -> {
                String[] out = read == null
                        ? Adb.remoteShell(this, d.host, d.port, portChangedFor(d.host), cmd)
                        : Adb.remoteShell(this, d.host, d.port, portChangedFor(d.host), cmd, read);
                checkOutput(out[0]);
                logOutput("[" + d.label() + "] ", out[0]);
                return read == null ? "" : name + " = " + out[1];
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
                    String out = Adb.su(cmd);
                    checkOutput(out);
                    logOutput("", out);
                    finish(Status.SUCCESS, read == null ? "Executado via root."
                            : "Aplicado via root. " + name + " = " + Adb.su(read));
                    return;
                }
                // 2) ADB pela Depuração sem fio
                String out = Adb.shell(this, cmd);
                checkOutput(out);
                logOutput("", out);
                String value = read == null ? null : Adb.shell(this, read);
                Adb.grantSelfSecureSettings(this);
                finish(Status.SUCCESS, read == null ? "Executado neste aparelho."
                        : "Aplicado neste aparelho. " + name + " = " + value);
            } catch (AdbPairingRequiredException e) {
                pairingRequired(null);
            } catch (Exception e) {
                finish(Status.ERROR, "Falha: " + commandError(e));
            }
        });
    }

    private void readValue() {
        final AdbCommands.Preset preset = selectedPreset();
        final String read = preset.read;
        if (read == null) return;
        final String name = preset.readName;
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
                pairingRequired(null);
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
            final Map<String, Adb.Progress> progressOf = progressItems(new ProgressBoard("Enviando o APK"), targets);
            runOnDevices(targets, "Instalado", d -> {
                Adb.Progress progress = progressOf.get(d.address());
                Adb.Shell shell = Adb.openRemote(this, d.host, d.port, portChangedFor(d.host));
                try {
                    shell.install(apk, progress);
                } finally {
                    shell.close();
                }
                return "";
            });
            return;
        }
        start("instalar " + app);
        final Adb.Progress progress = new ProgressBoard("Enviando o APK").item("este aparelho");
        background(() -> {
            try {
                Adb.openLocal(this).install(apk, progress);
                finish(Status.SUCCESS, "Instalado neste aparelho.");
            } catch (AdbPairingRequiredException e) {
                pairingRequired(null);
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
            final Map<String, Adb.Progress> progressOf = progressItems(new ProgressBoard("Enviando"), targets);
            runOnDevices(targets, "Enviado", d -> {
                Adb.Progress progress = progressOf.get(d.address());
                Adb.Shell shell = Adb.openRemote(this, d.host, d.port, portChangedFor(d.host));
                try {
                    shell.push(file, remotePath, progress);
                } finally {
                    shell.close();
                }
                return "enviado para " + remotePath;
            });
            return;
        }
        start("push " + file.getName() + " " + remotePath);
        final Adb.Progress progress = new ProgressBoard("Enviando").item("este aparelho");
        background(() -> {
            try {
                Adb.openLocal(this).push(file, remotePath, progress);
                finish(Status.SUCCESS, "Enviado neste aparelho: " + remotePath);
            } catch (AdbPairingRequiredException e) {
                pairingRequired(null);
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
                pairingRequired(target != null ? target[0] + ":" + target[1] : null);
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
                .setNeutralButton("Ações", (dlg, w) -> showAppActions(title, d))
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

    /** Menu de ações do app (as que fazem sentido no estado atual) e a saída completa do dumpsys. */
    private void showAppActions(final String title, final AppInfo.Details d) {
        final List<AppInfo.Action> list = AppInfo.actions(d);
        String[] labels = new String[list.size() + 1];
        for (int i = 0; i < list.size(); i++) labels[i] = list.get(i).label;
        labels[list.size()] = "Ver saída completa (dumpsys)";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, (dlg, which) -> {
                    if (which == list.size()) {
                        append("dumpsys package " + d.pkg + ":");
                        logOutput("", d.dumpsys);
                        setStatus(Status.INFO, "Saída completa do dumpsys no registro abaixo.");
                        return;
                    }
                    AppInfo.Action a = list.get(which);
                    if (!a.confirm) {
                        runAppAction(title, d, a);
                        return;
                    }
                    String warning = a == AppInfo.Action.CLEAR
                            ? "Apaga todos os dados do app (configurações, login, banco de dados). Não dá para desfazer."
                            : a == AppInfo.Action.UNINSTALL ? "Remove o app e todos os dados dele do aparelho."
                            : "O app some da tela e para de abrir até ser ativado de novo.";
                    new AlertDialog.Builder(this)
                            .setTitle(a.label + "?")
                            .setMessage(title + "\n" + d.pkg + "\n\n" + warning)
                            .setPositiveButton(a.label, (x, y) -> runAppAction(title, d, a))
                            .setNegativeButton("Cancelar", (x, y) -> showAppDetails(title, d))
                            .show();
                })
                .setNegativeButton("Voltar", (dlg, w) -> showAppDetails(title, d))
                .show();
    }

    /** Executa a ação no aparelho alvo e reabre as informações já atualizadas. */
    private void runAppAction(final String title, final AppInfo.Details d, final AppInfo.Action a) {
        withTargetShell("Ações do app", a.label.toLowerCase(Locale.ROOT) + " " + d.pkg, a.label + " falhou: ", shell -> {
            String message = AppInfo.run(shell, d, a);
            if (a == AppInfo.Action.UNINSTALL) {
                finish(Status.SUCCESS, message);
                return;
            }
            // "am start" e "force-stop" voltam antes do app terminar de abrir/fechar.
            if (a == AppInfo.Action.OPEN || a == AppInfo.Action.STOP) Thread.sleep(1500);
            AppInfo.Details updated = AppInfo.details(shell, d.pkg);
            finish(Status.SUCCESS, message);
            runOnUiThread(() -> showAppDetails(title, updated));
        });
    }

    // ---------- Baixar arquivo ----------

    /** Alvo do download escolhido no navegador (null = este aparelho) e o caminho do arquivo. */
    private Object[] pullTarget;
    private String pullPath;
    private boolean pullPending;

    /** Abre o navegador de pastas do aparelho alvo para escolher o arquivo a baixar. */
    private void browseRemoteFiles() {
        Object[] t = null;
        if (isRemote()) {
            t = singleTarget("Baixar arquivo");
            if (t == null) return;
        }
        final Object[] target = t;
        final String startDir = prefs.getString("pullDir", "/sdcard/Download");
        start((target != null ? "[" + target[0] + ":" + target[1] + "] " : "") + "procurar arquivo em " + startDir);
        background(() -> {
            Adb.Shell shell = null;
            try {
                shell = openTarget(target);
                String dir = startDir;
                List<RemoteFiles.Entry> entries;
                try {
                    entries = RemoteFiles.list(shell, dir);
                } catch (java.io.IOException e) {
                    dir = "/sdcard";
                    entries = RemoteFiles.list(shell, dir);
                }
                final Adb.Shell open = shell;
                final String firstDir = dir;
                final List<RemoteFiles.Entry> first = entries;
                finish(Status.INFO, "Escolha o arquivo a baixar.");
                runOnUiThread(() -> showRemoteBrowser(open, target, firstDir, first));
            } catch (AdbPairingRequiredException e) {
                if (shell != null) shell.close();
                pairingRequired(target != null ? target[0] + ":" + target[1] : null);
            } catch (Exception e) {
                if (shell != null) shell.close();
                finish(Status.ERROR, "Não foi possível abrir as pastas: " + Adb.describe(e));
            }
        });
    }

    private Adb.Shell openTarget(Object[] target) throws Exception {
        return target != null
                ? Adb.openRemote(this, (String) target[0], (Integer) target[1], portChangedFor((String) target[0]))
                : Adb.openLocal(this);
    }

    // java.util.function só existe a partir do Android 7; o app roda desde o 5.
    private interface FolderOpener {
        void accept(String path);
    }

    private interface FolderShower {
        void accept(String path, List<RemoteFiles.Entry> entries);
    }

    /** Lista da pasta; tocar numa pasta entra nela, tocar num arquivo escolhe onde salvar. */
    private void showRemoteBrowser(final Adb.Shell shell, final Object[] target, String startDir,
                                   List<RemoteFiles.Entry> startEntries) {
        final String[] dir = {startDir};
        final List<Object> items = new ArrayList<>();
        final ArrayAdapter<Object> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items);
        final android.widget.ListView listView = new android.widget.ListView(this);
        listView.setAdapter(adapter);
        final TextView empty = new TextView(this);
        float dp = getResources().getDisplayMetrics().density;
        empty.setPadding((int) (24 * dp), (int) (12 * dp), (int) (24 * dp), 0);
        empty.setText("Pasta vazia ou sem permissão de leitura.");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(empty);
        box.addView(listView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.55)));

        final String up = "⬆  .. (pasta acima)";
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(startDir)
                .setView(box)
                .setNeutralButton("Ir para...", null)
                .setNegativeButton("Cancelar", null)
                .create();
        final FolderShower show = (path, entries) -> {
            dir[0] = path;
            dialog.setTitle(path);
            items.clear();
            if (!path.equals("/")) items.add(up);
            items.addAll(entries);
            empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
            adapter.notifyDataSetChanged();
            listView.setSelection(0);
        };
        final FolderOpener open = path -> {
            setStatus(Status.INFO, "Abrindo " + path + "...");
            background(() -> {
                try {
                    List<RemoteFiles.Entry> entries = RemoteFiles.list(shell, path);
                    runOnUiThread(() -> {
                        show.accept(path, entries);
                        setStatus(Status.INFO, "Escolha o arquivo a baixar.");
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> setStatus(Status.ERROR, Adb.describe(e)));
                }
            });
        };
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Object item = items.get(position);
            if (item == up) {
                open.accept(RemoteFiles.parent(dir[0]));
                return;
            }
            RemoteFiles.Entry e = (RemoteFiles.Entry) item;
            String path = RemoteFiles.join(dir[0], e.name);
            if (e.dir) {
                open.accept(path);
                return;
            }
            prefs.edit().putString("pullDir", dir[0]).apply();
            pullTarget = target;
            pullPath = path;
            pullPending = true;
            dialog.dismiss();
            chooseWhereToSave(e.name);
        });
        // Fecha a conexão quando o navegador some (escolheu arquivo, cancelou ou tocou fora).
        dialog.setOnDismissListener(x -> background(shell::close));
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            final EditText field = new EditText(this);
            field.setSingleLine(true);
            field.setText(dir[0]);
            LinearLayout pad = new LinearLayout(this);
            int p = (int) (20 * dp);
            pad.setPadding(p, 0, p, 0);
            pad.addView(field, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            new AlertDialog.Builder(this)
                    .setTitle("Ir para a pasta")
                    .setView(pad)
                    .setPositiveButton("Abrir", (d2, w) -> {
                        String path = field.getText().toString().trim();
                        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
                        if (!path.startsWith("/")) {
                            setStatus(Status.ERROR, "Informe o caminho completo (ex.: /sdcard/Download).");
                            return;
                        }
                        open.accept(path);
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        }));
        show.accept(startDir, startEntries);
        dialog.show();
    }

    private void chooseWhereToSave(String name) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(intent, REQUEST_PULL_FILE);
        } catch (Exception e) {
            pullPending = false;
            setStatus(Status.ERROR, "Nenhum seletor de arquivos disponível neste aparelho.");
        }
    }

    /** Baixa o arquivo escolhido no navegador para o local escolhido no celular. */
    private void pullFile(final Uri uri) {
        if (uri == null) {
            pullPending = false;
            setStatus(Status.INFO, "Download cancelado.");
            return;
        }
        if (!pullPending) {
            // A tela foi recriada enquanto o seletor estava aberto e perdeu o arquivo escolhido.
            DeviceLog.delete(getContentResolver(), uri);
            setStatus(Status.ERROR, "Escolha o arquivo de novo em \"Baixar arquivo\".");
            return;
        }
        pullPending = false;
        final Object[] target = pullTarget;
        final String path = pullPath;
        final String name = fileName(uri);
        start((target != null ? "[" + target[0] + ":" + target[1] + "] " : "") + "pull " + path);
        final Adb.Progress progress = new ProgressBoard("Baixando").item(name);
        background(() -> {
            try {
                Adb.Shell shell = openTarget(target);
                long size;
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new java.io.IOException("não foi possível gravar em " + name);
                    size = shell.pull(path, out, progress);
                } finally {
                    shell.close();
                }
                finish(Status.SUCCESS, "Baixado: " + name + " (" + RemoteFiles.formatSize(size) + ")");
            } catch (AdbPairingRequiredException e) {
                DeviceLog.delete(getContentResolver(), uri);
                finish(Status.ERROR, "Conclua o pareamento (etapa 1).");
            } catch (Exception e) {
                DeviceLog.delete(getContentResolver(), uri);
                finish(Status.ERROR, "Falha no download: " + Adb.describe(e));
            }
        });
    }

    // ---------- Capturar tela ----------

    private byte[] screenshotPng;

    private void captureScreen() {
        withTargetShell("Capturar tela", "capturar tela", "Não foi possível capturar a tela: ", shell -> {
            final byte[] png = RemoteFiles.screenshot(shell);
            final android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
            if (bmp == null) throw new java.io.IOException("a imagem recebida não é um PNG válido");
            finish(Status.SUCCESS, "Tela capturada (" + bmp.getWidth() + "x" + bmp.getHeight() + ").");
            runOnUiThread(() -> showScreenshot(png, bmp));
        });
    }

    private void showScreenshot(final byte[] png, android.graphics.Bitmap bmp) {
        float dp = getResources().getDisplayMetrics().density;
        android.widget.ImageView image = new android.widget.ImageView(this);
        image.setImageBitmap(bmp);
        image.setAdjustViewBounds(true);
        image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int pad = (int) (12 * dp);
        image.setPadding(pad, pad, pad, 0);
        image.setMaxHeight((int) (getResources().getDisplayMetrics().heightPixels * 0.65));
        new AlertDialog.Builder(this)
                .setTitle("Tela do aparelho")
                .setView(image)
                .setPositiveButton("Salvar", (d, w) -> {
                    screenshotPng = png;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/png");
                    intent.putExtra(Intent.EXTRA_TITLE, "tela_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",
                            Locale.getDefault()).format(new Date()) + ".png");
                    try {
                        startActivityForResult(intent, REQUEST_SAVE_SCREENSHOT);
                    } catch (Exception e) {
                        setStatus(Status.ERROR, "Nenhum seletor de arquivos disponível neste aparelho.");
                    }
                })
                .setNeutralButton("Atualizar", (d, w) -> captureScreen())
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void saveScreenshot(final Uri uri) {
        final byte[] png = screenshotPng;
        screenshotPng = null;
        if (uri == null) {
            setStatus(Status.INFO, "Captura não salva.");
            return;
        }
        if (png == null) {
            DeviceLog.delete(getContentResolver(), uri);
            setStatus(Status.ERROR, "Capture a tela de novo para salvar.");
            return;
        }
        final String name = fileName(uri);
        background(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("não foi possível gravar em " + name);
                out.write(png);
                runOnUiThread(() -> {
                    append("Captura salva: " + name);
                    setStatus(Status.SUCCESS, "Captura salva: " + name);
                });
            } catch (Exception e) {
                DeviceLog.delete(getContentResolver(), uri);
                runOnUiThread(() -> setStatus(Status.ERROR, "Não foi possível salvar: " + Adb.describe(e)));
            }
        });
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
        if (requestCode == REQUEST_PULL_FILE) {
            pullFile(resultCode == RESULT_OK && data != null ? data.getData() : null);
            return;
        }
        if (requestCode == REQUEST_SAVE_SCREENSHOT) {
            saveScreenshot(resultCode == RESULT_OK && data != null ? data.getData() : null);
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
                pairingRequired(t != null ? t[0] + ":" + t[1] : null);
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

    /** Progresso em andamento; finish() o encerra para não sobrescrever a mensagem final. */
    private ProgressBoard activeBoard;

    /**
     * Mostra no status a porcentagem de envios/downloads ("Enviando: Quiosque 1 45% · Quiosque 2 80%"),
     * sem redesenhar a tela a cada bloco. Criar no thread da tela, depois de start().
     */
    private final class ProgressBoard {
        private final String verb;
        private final Map<String, long[]> items = new LinkedHashMap<>();
        private long lastPost;

        ProgressBoard(String verb) {
            this.verb = verb;
            activeBoard = this;
        }

        Adb.Progress item(final String name) {
            synchronized (this) {
                items.put(name, new long[]{0, 0});
            }
            return (done, total) -> update(name, done, total);
        }

        private void update(String name, long done, long total) {
            final String text;
            synchronized (this) {
                long[] v = items.get(name);
                if (v == null) return;
                v[0] = done;
                v[1] = total;
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastPost < 300 && done < total) return;
                lastPost = now;
                text = render();
            }
            runOnUiThread(() -> {
                if (activeBoard == this) setStatus(Status.INFO, text);
            });
        }

        private String render() {
            if (items.size() == 1) {
                long[] v = items.values().iterator().next();
                return verb + "... " + percent(v) + "% (" + RemoteFiles.formatSize(v[0]) + " de "
                        + RemoteFiles.formatSize(v[1]) + ")";
            }
            if (items.size() > 4) {
                int sum = 0, done = 0;
                for (long[] v : items.values()) {
                    sum += percent(v);
                    if (v[1] > 0 && v[0] >= v[1]) done++;
                }
                return verb + " para " + items.size() + " aparelhos: " + (sum / items.size()) + "% ("
                        + done + " concluídos)";
            }
            StringBuilder b = new StringBuilder(verb).append(":");
            String sep = " ";
            for (Map.Entry<String, long[]> e : items.entrySet()) {
                b.append(sep).append(e.getKey()).append(' ').append(percent(e.getValue())).append('%');
                sep = " · ";
            }
            return b.toString();
        }

        private int percent(long[] v) {
            return v[1] <= 0 ? 0 : (int) (v[0] * 100 / v[1]);
        }
    }

    /** Registra todos os aparelhos no painel antes de começar (para a lista sair completa). */
    private static Map<String, Adb.Progress> progressItems(ProgressBoard board, List<Devices.Device> targets) {
        Map<String, Adb.Progress> map = new LinkedHashMap<>();
        for (Devices.Device d : targets) map.put(d.address(), board.item(d.label()));
        return map;
    }

    /** Motivo de falha de um aparelho que ainda não pareou com o app. */
    private static final String NEEDS_PAIRING = "Exige pareamento. Conclua a etapa 1.";

    /**
     * Termina a operação com o aviso de pareamento e abre uma janela explicando: só o texto no
     * status passava despercebido e parecia que o app tinha travado.
     * {@code target} é o aparelho remoto ("ip:porta"), ou null para este aparelho.
     */
    private void pairingRequired(final String target) {
        finish(Status.ERROR, target != null ? "O aparelho alvo exige pareamento. Conclua a etapa 1."
                : "Aparelho ainda não pareado. Conclua a etapa 1.");
        runOnUiThread(() -> showPairingDialog(target));
    }

    /** Janela "Pareamento necessário" com atalho para a etapa 1. */
    private void showPairingDialog(String target) {
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

    /** Fim de uma operação (chamado de qualquer thread). */
    private void finish(Status s, String message) {
        runOnUiThread(() -> {
            activeBoard = null;
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
