package com.bkp.adbmobile;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Atualização do app pelos releases do GitHub: compara a versão instalada com a do último
 * release e, se houver uma mais nova, baixa o APK e instala pelo PackageInstaller do Android
 * (que mostra a confirmação e só aceita o APK assinado com a mesma chave).
 */
final class Updater {

    static final String LATEST_URL = "https://api.github.com/repos/RaphaelNDS/ADB-Mobile/releases/latest";
    /** Verificação automática ao abrir o app: no máximo uma vez por dia. */
    private static final long AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final String KEY_CHECKED_AT = "updateCheckedAt";

    /** Último release: versão (sem o "v"), link do APK e notas. */
    static final class Release {
        final String version;
        final String apkUrl;
        final long apkSize;
        final String notes;

        Release(String version, String apkUrl, long apkSize, String notes) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.apkSize = apkSize;
            this.notes = notes;
        }
    }

    private final MainActivity a;

    Updater(MainActivity activity) {
        a = activity;
        a.findViewById(R.id.checkUpdate).setOnClickListener(v -> check(true));
    }

    /**
     * Compara versões "2.35" e "2.4" número a número (2.35 é mais nova que 2.4).
     * Positivo se {@code a} for mais nova, negativo se {@code b} for, 0 se iguais.
     */
    static int compare(String a, String b) {
        String[] x = a.trim().split("\\.");
        String[] y = b.trim().split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0;
            int q = i < y.length ? number(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Lê a resposta do GitHub; lança erro se não houver APK no release. */
    static Release parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        String tag = o.getString("tag_name");
        String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (asset.getString("name").toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                    return new Release(version, asset.getString("browser_download_url"),
                            asset.optLong("size", -1), o.optString("body", ""));
                }
            }
        }
        throw new IOException("O release " + tag + " não tem APK.");
    }

    /** Verificação ao abrir o app: silenciosa, no máximo uma vez por dia. */
    void checkOnStart() {
        long last = a.prefs.getLong(KEY_CHECKED_AT, 0);
        if (System.currentTimeMillis() - last < AUTO_INTERVAL_MS) return;
        check(false);
    }

    /** {@code manual}: tocou no botão (mostra também "já está atualizado" e os erros). */
    private void check(final boolean manual) {
        if (manual) a.setStatus(MainActivity.Status.INFO, "Verificando atualização...");
        a.background(() -> {
            try {
                Release r = parse(new String(get(LATEST_URL), "UTF-8"));
                a.prefs.edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply();
                final String current = a.versionName();
                if (compare(r.version, current) > 0) {
                    a.log("Atualização disponível: versão " + r.version + " (instalada: " + current + ").");
                    a.runOnUiThread(() -> offer(r, current));
                } else if (manual) {
                    a.runOnUiThread(() -> a.setStatus(MainActivity.Status.SUCCESS,
                            "Você já tem a versão mais nova (" + current + ")."));
                }
            } catch (Exception e) {
                // Sem internet ao abrir o app não é problema: só avisa quando a pessoa pediu.
                if (manual) a.runOnUiThread(() -> a.setStatus(MainActivity.Status.ERROR,
                        "Não foi possível verificar a atualização: " + Adb.describe(e)));
            }
        });
    }

    private void offer(final Release r, String current) {
        if (a.isFinishing() || a.isDestroyed()) return;
        String notes = r.notes.trim();
        if (notes.length() > 1500) notes = notes.substring(0, 1500) + "...";
        new AlertDialog.Builder(a)
                .setTitle("Versão " + r.version + " disponível")
                .setMessage("Instalada: " + current + "\n\n" + (notes.isEmpty() ? "" : notes + "\n\n")
                        + "O Android pede confirmação antes de instalar. O pareamento e os comandos salvos continuam.")
                .setPositiveButton("Baixar e instalar", (d, w) -> download(r))
                .setNegativeButton("Depois", null)
                .show();
    }

    private void download(final Release r) {
        a.start("Baixando a versão " + r.version + "...");
        a.background(() -> {
            try {
                File dir = new File(a.getCacheDir(), "update");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                File apk = new File(dir, "ADB-Mobile-" + r.version + ".apk");
                download(r.apkUrl, apk, r.apkSize);
                // Confere antes de instalar: é mesmo o ADB Mobile, e mais novo.
                PackageInfo info = a.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
                if (info == null || !a.getPackageName().equals(info.packageName)) {
                    throw new IOException("O arquivo baixado não é o ADB Mobile.");
                }
                install(apk);
                a.finish(MainActivity.Status.INFO, "Versão " + r.version + " baixada. Confirme a instalação na tela do Android.");
            } catch (Exception e) {
                a.finish(MainActivity.Status.ERROR, "Falha ao atualizar: " + Adb.describe(e));
            }
        });
    }

    private void download(String url, File out, long size) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
            long total = size > 0 ? size : c.getContentLength();
            byte[] buf = new byte[65536];
            long done = 0;
            int lastPercent = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    final int percent = (int) (done * 100 / total);
                    if (percent / 10 != lastPercent / 10) {
                        lastPercent = percent;
                        a.runOnUiThread(() -> a.setStatus(MainActivity.Status.INFO, "Baixando... " + percent + "%"));
                    }
                }
            }
        } finally {
            c.disconnect();
        }
    }

    /** Entrega o APK ao PackageInstaller; o resultado chega no UpdateReceiver. */
    private void install(File apk) throws IOException {
        PackageInstaller installer = a.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(a.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (OutputStream out = session.openWrite("adb-mobile.apk", 0, apk.length());
                 InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent result = PendingIntent.getBroadcast(a, id,
                    new Intent(a, UpdateReceiver.class).setAction(UpdateReceiver.ACTION), flags);
            session.commit(result.getIntentSender());
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "ADB-Mobile");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException(code == 404 ? "nenhum release publicado" : "resposta " + code + " do GitHub");
        }
        return c;
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
