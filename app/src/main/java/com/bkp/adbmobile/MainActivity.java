package com.bkp.adbmobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.muntashirakon.adb.AdbPairingRequiredException;

/**
 * Executa o equivalente a: adb shell settings put system eth_device_conn 2
 *
 * Neste aparelho: usa root (su) se houver; senão, conecta à "Depuração sem fio" local
 * (pareamento feito uma vez) e roda o comando como usuário shell.
 * Em outro aparelho: conecta por IP:porta (Depuração sem fio pareada ou "adb tcpip 5555").
 */
public class MainActivity extends Activity {

    private enum Status { INFO, SUCCESS, ERROR }

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("alvo", MODE_PRIVATE);

        ((TextView) findViewById(R.id.command)).setText("adb shell " + Adb.COMMAND);
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
        actions = new Button[]{notificationPairing, pairManual, run, read};

        hostField.setText(prefs.getString("host", ""));
        connectPortField.setText(prefs.getString("port", "5555"));

        notificationPairing.setOnClickListener(v -> startNotificationPairing());
        pairManual.setOnClickListener(v -> manualPairing());
        run.setOnClickListener(v -> runCommand());
        read.setOnClickListener(v -> readValue());
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

    private void runCommand() {
        if (isRemote()) {
            final Object[] t = remoteTarget();
            if (t == null) return;
            start("[" + t[0] + ":" + t[1] + "] $ " + Adb.COMMAND);
            log("Conectando... se o alvo solicitar, autorize a depuração na tela dele.");
            background(() -> {
                try {
                    String[] out = Adb.remoteShell(this, (String) t[0], (Integer) t[1],
                            Adb.COMMAND, Adb.READ_COMMAND);
                    if (!out[0].isEmpty()) log(out[0]);
                    finish(Status.SUCCESS, "Aplicado em " + t[0] + ". eth_device_conn = " + out[1]);
                } catch (AdbPairingRequiredException e) {
                    finish(Status.ERROR, "O aparelho alvo exige pareamento. Conclua a etapa 1.");
                } catch (Exception e) {
                    finish(Status.ERROR, "Falha: " + Adb.describe(e));
                }
            });
            return;
        }

        start("$ " + Adb.COMMAND);
        background(() -> {
            // 1) Root, se existir
            try {
                Adb.su(Adb.COMMAND);
                finish(Status.SUCCESS, "Aplicado via root. eth_device_conn = " + Adb.su(Adb.READ_COMMAND));
                return;
            } catch (Exception e) {
                log("Root indisponível; usando ADB local.");
            }
            // 2) ADB pela Depuração sem fio
            try {
                String out = Adb.shell(this, Adb.COMMAND);
                if (!out.isEmpty()) log(out);
                String value = Adb.shell(this, Adb.READ_COMMAND);
                Adb.grantSelfSecureSettings(this);
                finish(Status.SUCCESS, "Aplicado neste aparelho. eth_device_conn = " + value);
            } catch (AdbPairingRequiredException e) {
                finish(Status.ERROR, "Aparelho ainda não pareado. Conclua a etapa 1.");
            } catch (Exception e) {
                finish(Status.ERROR, "Falha: " + Adb.describe(e));
            }
        });
    }

    private void readValue() {
        if (isRemote()) {
            final Object[] t = remoteTarget();
            if (t == null) return;
            start("[" + t[0] + ":" + t[1] + "] $ " + Adb.READ_COMMAND);
            background(() -> {
                try {
                    String[] out = Adb.remoteShell(this, (String) t[0], (Integer) t[1], Adb.READ_COMMAND);
                    finish(Status.INFO, "Valor atual em " + t[0] + ": eth_device_conn = " + out[0]);
                } catch (AdbPairingRequiredException e) {
                    finish(Status.ERROR, "O aparelho alvo exige pareamento. Conclua a etapa 1.");
                } catch (Exception e) {
                    finish(Status.ERROR, "Não foi possível ler: " + Adb.describe(e));
                }
            });
            return;
        }
        start("$ " + Adb.READ_COMMAND);
        background(() -> {
            try {
                finish(Status.INFO, "Valor atual: eth_device_conn = " + Adb.shell(this, Adb.READ_COMMAND));
            } catch (AdbPairingRequiredException e) {
                finish(Status.ERROR, "Aparelho ainda não pareado. Conclua a etapa 1.");
            } catch (Exception e) {
                try {
                    finish(Status.INFO, "Valor atual (root): eth_device_conn = " + Adb.su(Adb.READ_COMMAND));
                } catch (Exception e2) {
                    finish(Status.ERROR, "Não foi possível ler: " + Adb.describe(e));
                }
            }
        });
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
