package com.bkp.adbmobile;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import io.github.muntashirakon.adb.AdbPairingRequiredException;

/**
 * "Preparar Kiosk": em cada aparelho marcado (ou neste), instala o APK do Kiosk, define o Kiosk
 * como Device Owner (se ainda não for) e abre o Kiosk. Para montar vários aparelhos de uma vez.
 */
final class KioskSetup {

    private final MainActivity a;

    KioskSetup(MainActivity activity) {
        a = activity;
    }

    /**
     * Os três passos num aparelho. Device Owner já sendo o Kiosk não é erro (instala por cima e
     * abre); outro Device Owner é: o Android não troca sozinho. Devolve o resumo do aparelho.
     */
    static String setup(Adb.Shell shell, File apk, Adb.Progress progress) throws Exception {
        shell.install(apk, progress);
        String before = owner(shell);
        boolean already = isKiosk(before);
        if (!already) {
            if (!before.isEmpty()) {
                throw new IOException("Já existe outro Device Owner (" + before + "). Remova-o antes.");
            }
            String out = shell.run("dpm set-device-owner " + AdbCommands.KIOSK_ADMIN + " 2>&1");
            String failure = AdbCommands.failure(out);
            if (failure != null) throw new IOException(failure);
            // Confere de verdade (a saída do dpm muda entre versões do Android).
            String after = owner(shell);
            if (!isKiosk(after)) throw new IOException("O Device Owner não foi definido: " + out.trim());
        }
        shell.run("monkey -p " + AdbCommands.KIOSK_PACKAGE + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1");
        return already ? "instalado (já era Device Owner) e aberto" : "instalado, Device Owner definido e aberto";
    }

    /** Componente do Device Owner atual, ou "" se não há. */
    private static String owner(Adb.Shell shell) throws Exception {
        String o = shell.run(AdbCommands.DEVICE_OWNER_READ).trim();
        return o.equals("nenhum") ? "" : o;
    }

    private static boolean isKiosk(String owner) {
        return owner.startsWith(AdbCommands.KIOSK_PACKAGE + "/");
    }

    void choose() {
        if (a.isRemote() && a.devices.selected() == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive", "application/octet-stream"});
        a.openPicker(intent, MainActivity.REQUEST_KIOSK_APK);
    }

    void onApkChosen(final Uri uri) {
        if (uri == null) {
            a.setStatus(MainActivity.Status.INFO, "Preparar Kiosk cancelado.");
            return;
        }
        final String name = a.fileName(uri);
        a.setStatus(MainActivity.Status.INFO, "Lendo " + name + "...");
        a.background(() -> {
            final File apk = new File(a.getCacheDir(), "kiosk.apk");
            try {
                a.copyToFile(uri, apk);
            } catch (Exception e) {
                a.runOnUiThread(() -> a.setStatus(MainActivity.Status.ERROR, "Não foi possível ler " + name + ": " + Adb.describe(e)));
                return;
            }
            PackageInfo info = a.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
            if (info == null || !AdbCommands.KIOSK_PACKAGE.equals(info.packageName)) {
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                a.runOnUiThread(() -> a.setStatus(MainActivity.Status.ERROR, name + " não é o app Kiosk ("
                        + AdbCommands.KIOSK_PACKAGE + ")."));
                return;
            }
            final String version = info.versionName;
            a.runOnUiThread(() -> confirm(apk, version));
        });
    }

    private void confirm(final File apk, final String version) {
        final List<Devices.Device> targets = a.isRemote() ? a.devices.selected() : null;
        if (a.isRemote() && targets == null) return;
        String where = targets == null ? "neste aparelho" : targets.size() == 1 ? "em " + targets.get(0).label()
                : "em " + targets.size() + " aparelhos";
        a.setStatus(MainActivity.Status.INFO, "Confirme a preparação do Kiosk.");
        new AlertDialog.Builder(a)
                .setTitle("Preparar Kiosk " + version)
                .setMessage("Instalar o Kiosk " + version + ", defini-lo como Device Owner e abri-lo " + where + "."
                        + "\n\nAtenção: o Device Owner só sai pelo próprio Kiosk ou restaurando o aparelho de "
                        + "fábrica. O aparelho não pode ter contas (Google, Samsung etc.) nem outros usuários."
                        + "\n\nSe o Kiosk já for o Device Owner, ele só é atualizado e aberto.")
                .setPositiveButton("Preparar", (d, w) -> run(apk, version, targets))
                .setNegativeButton("Cancelar", (d, w) -> a.setStatus(MainActivity.Status.INFO, "Preparar Kiosk cancelado."))
                .show();
    }

    private void run(final File apk, String version, final List<Devices.Device> targets) {
        String line = "Preparar Kiosk " + version;
        if (targets != null) {
            a.start(DevicesPanel.label(targets) + " " + line);
            final Map<String, Adb.Progress> progressOf = new ProgressBoard(a, "Enviando o Kiosk").items(targets);
            a.runner.run(targets, "Kiosk preparado", d -> {
                Adb.Shell shell = Adb.openRemote(a, d.host, d.port, a.devices.portChangedFor(d.host));
                try {
                    return setup(shell, apk, progressOf.get(d.address()));
                } finally {
                    shell.close();
                }
            });
            return;
        }
        a.start(line);
        final Adb.Progress progress = new ProgressBoard(a, "Enviando o Kiosk").item("este aparelho");
        a.background(() -> {
            try {
                String result = setup(Adb.openLocal(a), apk, progress);
                a.finish(MainActivity.Status.SUCCESS, "Kiosk " + result + ".");
            } catch (AdbPairingRequiredException e) {
                a.pairingRequired(null);
            } catch (Exception e) {
                a.finish(MainActivity.Status.ERROR, "Falha ao preparar o Kiosk: " + DeviceRunner.errorText(e));
            }
        });
    }
}
