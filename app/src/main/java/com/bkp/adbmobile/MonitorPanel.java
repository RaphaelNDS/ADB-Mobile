package com.bkp.adbmobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import static com.bkp.adbmobile.MainActivity.Status;

/** Cartão "Log automático": pacote monitorado, pasta dos logs e o {@link LogMonitorService}. */
final class MonitorPanel {

    private static final String DEFAULT_PACKAGE = "br.com.backupautomacao.kiosk";

    private final MainActivity a;
    private final EditText packageField;
    private final TextView folderLabel;
    private final Button toggleButton;

    private final LogMonitorService.Listener listener;

    MonitorPanel(MainActivity activity) {
        a = activity;
        packageField = a.findViewById(R.id.monitorPackage);
        folderLabel = a.findViewById(R.id.logFolder);
        toggleButton = a.findViewById(R.id.toggleMonitor);
        packageField.setText(a.prefs.getString("monitorPackage", DEFAULT_PACKAGE));
        updateFolderLabel();
        a.findViewById(R.id.chooseFolder).setOnClickListener(v -> chooseFolder());
        toggleButton.setOnClickListener(v -> toggle());
        listener = (message, running) -> {
            a.append("[auto] " + message);
            updateButton(running);
            // O monitoramento pode ter achado uma porta nova e salvo na lista de aparelhos.
            a.devices.reload();
        };
        LogMonitorService.listener = listener;
    }

    /** Pacote informado (também usado no {pacote} dos comandos e em "Informações do app"). */
    String packageName() {
        return packageField.getText().toString().trim();
    }

    /** "Usar este pacote" nas informações do app. */
    void usePackage(String pkg) {
        if (!packageField.isEnabled()) {
            a.setStatus(Status.ERROR, "Pare o monitoramento antes de trocar o pacote.");
            return;
        }
        packageField.setText(pkg);
        a.prefs.edit().putString("monitorPackage", pkg).apply();
        a.setStatus(Status.SUCCESS, "Pacote definido: " + pkg);
    }

    void onResume() {
        // Ficou ligado mas não está rodando (ex.: o Android recusou retomar no boot): retoma agora.
        if (LogMonitorService.isActive(a) && !LogMonitorService.running) LogMonitorService.resume(a);
        updateButton(LogMonitorService.running || LogMonitorService.isActive(a));
    }

    void onDestroy() {
        if (LogMonitorService.listener == listener) LogMonitorService.listener = null;
    }

    // ---------- Pasta dos logs ----------

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        a.openPicker(intent, MainActivity.REQUEST_LOG_FOLDER);
    }

    // As flags vêm do seletor de pasta (só as de leitura/escrita do Intent passam no &).
    @SuppressLint("WrongConstant")
    void onFolderChosen(Uri tree, Intent data) {
        if (tree == null) {
            a.setStatus(Status.INFO, "Escolha da pasta cancelada.");
            return;
        }
        try {
            // Mantém o acesso à pasta depois de reiniciar o app/aparelho.
            a.getContentResolver().takePersistableUriPermission(tree,
                    data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        } catch (Exception ignored) {
        }
        a.prefs.edit().putString("logFolder", tree.toString()).apply();
        updateFolderLabel();
        a.append("Pasta dos logs automáticos: " + folderName(tree));
        a.setStatus(Status.SUCCESS, "Pasta dos logs definida.");
    }

    private Uri folder() {
        String s = a.prefs.getString("logFolder", null);
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
        Uri folder = folder();
        folderLabel.setText("Pasta: " + (folder == null ? "não escolhida" : folderName(folder)));
    }

    // ---------- Monitoramento ----------

    private void toggle() {
        if (LogMonitorService.running || LogMonitorService.isActive(a)) {
            LogMonitorService.stop(a);
            updateButton(false);
            return;
        }
        String pkg = packageName();
        if (pkg.isEmpty()) {
            a.setStatus(Status.ERROR, "Informe o pacote do app a monitorar.");
            return;
        }
        Uri folder = folder();
        if (folder == null) {
            a.setStatus(Status.ERROR, "Escolha a pasta onde os logs automáticos serão salvos.");
            return;
        }
        Devices.Device[] t = new Devices.Device[1];
        if (!a.oneTarget("O monitoramento", t)) return;
        String host = t[0] != null ? t[0].host : null;
        int port = t[0] != null ? t[0].port : 0;
        a.prefs.edit().putString("monitorPackage", pkg).apply();
        if (Build.VERSION.SDK_INT >= 33
                && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Sem a permissão o monitoramento funciona, só não aparece a notificação.
            a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        LogMonitorService.start(a, host, port, pkg, folder);
        updateButton(true);
    }

    private void updateButton(boolean running) {
        toggleButton.setText(running ? "Parar monitoramento" : "Iniciar monitoramento");
        packageField.setEnabled(!running);
    }
}
