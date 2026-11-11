package com.bkp.adbmobile;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.muntashirakon.adb.AdbPairingRequiredException;

import static com.bkp.adbmobile.MainActivity.Status;

/** "Instalar APK" e "Enviar arquivo": do armazenamento do celular para os aparelhos alvo. */
final class FileTransfers {

    private static final String DEFAULT_PUSH_DIR = "/sdcard/Download";

    private final MainActivity a;

    FileTransfers(MainActivity activity) {
        a = activity;
    }

    /** "neste aparelho", "em Quiosque 1" ou "em 3 aparelhos"; null (com aviso) se nenhum marcado. */
    private String where() {
        return a.isRemote() ? a.devices.whereSelected() : "neste aparelho";
    }

    private static String megabytes(File f) {
        return String.format(Locale.getDefault(), "%.1f MB", f.length() / 1048576.0);
    }

    /** Envia um arquivo local para cada aparelho marcado, com progresso; {@code send} faz o envio. */
    private interface Send {
        void to(Adb.Shell shell, Adb.Progress progress) throws Exception;
    }

    /**
     * Fluxo comum de instalar e enviar: abre cada alvo, roda {@code send} com o progresso e
     * termina com o resumo (vários aparelhos) ou a mensagem de sucesso/erro (este aparelho).
     */
    private void sendToTargets(String line, String sending, String verb, String result,
                               final String deviceResult, String localOk, String localError, final Send send) {
        if (a.isRemote()) {
            final List<Devices.Device> targets = a.devices.selected();
            if (targets == null) return;
            a.start(DevicesPanel.label(targets) + " " + line);
            a.log(sending + "... se o alvo solicitar, autorize a depuração na tela dele.");
            final Map<String, Adb.Progress> progressOf = new ProgressBoard(a, verb).items(targets);
            a.runner.run(targets, result, d -> {
                Adb.Shell shell = Adb.openRemote(a, d.host, d.port, a.devices.portChangedFor(d.host));
                try {
                    send.to(shell, progressOf.get(d.address()));
                } finally {
                    shell.close();
                }
                return deviceResult;
            });
            return;
        }
        a.start(line);
        final Adb.Progress progress = new ProgressBoard(a, verb).item("este aparelho");
        a.background(() -> {
            try {
                send.to(Adb.openLocal(a), progress);
                a.finish(Status.SUCCESS, localOk);
            } catch (AdbPairingRequiredException e) {
                a.pairingRequired(null);
            } catch (Exception e) {
                a.finish(Status.ERROR, localError + Adb.describe(e));
            }
        });
    }

    // ---------- Instalar APK ----------

    /** Escolhe o .apk no armazenamento do celular. */
    void chooseApk() {
        if (a.isRemote() && a.devices.selected() == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Alguns gerenciadores não marcam o .apk com o tipo certo.
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive", "application/octet-stream"});
        a.openPicker(intent, MainActivity.REQUEST_INSTALL_APK);
    }

    /** Copia o APK escolhido para o cache, lê nome/versão e pede confirmação. */
    void onApkChosen(final Uri uri) {
        if (uri == null) {
            a.setStatus(Status.INFO, "Instalação cancelada.");
            return;
        }
        final String name = a.fileName(uri);
        a.setStatus(Status.INFO, "Lendo " + name + "...");
        a.background(() -> {
            final File apk = new File(a.getCacheDir(), "install.apk");
            try {
                a.copyToFile(uri, apk);
            } catch (Exception e) {
                a.runOnUiThread(() -> a.setStatus(Status.ERROR, "Não foi possível ler " + name + ": " + Adb.describe(e)));
                return;
            }
            PackageManager pm = a.getPackageManager();
            final PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), 0);
            if (info == null) {
                apk.delete();
                a.runOnUiThread(() -> a.setStatus(Status.ERROR, name + " não é um APK válido."));
                return;
            }
            info.applicationInfo.sourceDir = apk.getPath();
            info.applicationInfo.publicSourceDir = apk.getPath();
            final String app = info.applicationInfo.loadLabel(pm) + " " + info.versionName;
            final String details = app + "\n" + info.packageName + "\n" + name + " (" + megabytes(apk) + ")";
            a.runOnUiThread(() -> confirmInstall(apk, app, details));
        });
    }

    private void confirmInstall(final File apk, final String app, String details) {
        String where = where();
        if (where == null) return;
        a.setStatus(Status.INFO, "Confirme a instalação.");
        new AlertDialog.Builder(a)
                .setTitle("Instalar APK")
                .setMessage("Instalar " + where + "?\n\n" + details
                        + "\n\nSe o app já existir, é atualizado mantendo os dados.")
                .setPositiveButton("Instalar", (d, w) -> sendToTargets("instalar " + app, "Enviando o APK",
                        "Enviando o APK", "Instalado", "", "Instalado neste aparelho.", "Falha na instalação: ",
                        (shell, progress) -> shell.install(apk, progress)))
                .setNegativeButton("Cancelar", (d, w) -> a.setStatus(Status.INFO, "Instalação cancelada."))
                .show();
    }

    // ---------- Enviar arquivo ----------

    /** Escolhe qualquer arquivo no armazenamento do celular para enviar (como "adb push"). */
    void choosePushFile() {
        if (a.isRemote() && a.devices.selected() == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        a.openPicker(intent, MainActivity.REQUEST_PUSH_FILE);
    }

    /** Copia o arquivo escolhido para o cache e pergunta a pasta de destino. */
    void onPushFileChosen(final Uri uri) {
        if (uri == null) {
            a.setStatus(Status.INFO, "Envio cancelado.");
            return;
        }
        // Barra no nome criaria subpastas no destino.
        final String name = a.fileName(uri).replace('/', '_');
        a.setStatus(Status.INFO, "Lendo " + name + "...");
        a.background(() -> {
            File dir = new File(a.getCacheDir(), "push");
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            dir.mkdirs();
            final File file = new File(dir, name);
            try {
                a.copyToFile(uri, file);
            } catch (Exception e) {
                a.runOnUiThread(() -> a.setStatus(Status.ERROR, "Não foi possível ler " + name + ": " + Adb.describe(e)));
                return;
            }
            a.runOnUiThread(() -> confirmPush(file));
        });
    }

    private void confirmPush(final File file) {
        String where = where();
        if (where == null) return;
        final EditText dirField = new EditText(a);
        dirField.setSingleLine(true);
        dirField.setText(a.prefs.getString("pushDir", DEFAULT_PUSH_DIR));
        LinearLayout box = new LinearLayout(a);
        box.setPadding(a.dp(20), 0, a.dp(20), 0);
        box.addView(dirField, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        a.setStatus(Status.INFO, "Confirme o envio.");
        new AlertDialog.Builder(a)
                .setTitle("Enviar arquivo")
                .setMessage("Enviar " + where + "?\n\n" + file.getName() + " (" + megabytes(file)
                        + ")\n\nPasta de destino no aparelho (arquivo com o mesmo nome é substituído):")
                .setView(box)
                .setPositiveButton("Enviar", (d, w) -> {
                    String dir = dirField.getText().toString().trim();
                    while (dir.length() > 1 && dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
                    if (!dir.startsWith("/")) {
                        a.setStatus(Status.ERROR, "Informe a pasta de destino com o caminho completo (ex.: "
                                + DEFAULT_PUSH_DIR + ").");
                        return;
                    }
                    a.prefs.edit().putString("pushDir", dir).apply();
                    pushFile(file, (dir.equals("/") ? "" : dir) + "/" + file.getName());
                })
                .setNegativeButton("Cancelar", (d, w) -> a.setStatus(Status.INFO, "Envio cancelado."))
                .show();
    }

    private void pushFile(final File file, final String remotePath) {
        sendToTargets("push " + file.getName() + " " + remotePath, "Enviando o arquivo", "Enviando",
                "Enviado", "enviado para " + remotePath, "Enviado neste aparelho: " + remotePath, "Falha no envio: ",
                (shell, progress) -> shell.push(file, remotePath, progress));
    }
}
