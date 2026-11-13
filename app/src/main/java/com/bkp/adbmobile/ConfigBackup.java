package com.bkp.adbmobile;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.bkp.adbmobile.MainActivity.Status;

/**
 * Cartão "Configuração": exporta e importa, num arquivo .json, os comandos salvos ("Meus
 * comandos"), os aparelhos salvos e o pacote do Log automático. Não leva o pareamento (a chave
 * do ADB é de cada aparelho) nem a pasta dos logs (a permissão de acesso não passa de um
 * aparelho para outro).
 */
final class ConfigBackup {

    static final String FORMAT = "adb-mobile-config";
    static final int VERSION = 1;

    /** O que veio no arquivo. */
    static final class Data {
        final List<AdbCommands.Preset> commands = new ArrayList<>();
        final List<Devices.Device> devices = new ArrayList<>();
        String monitorPackage = "";
    }

    private final MainActivity a;
    private final SavedCommands savedCommands;

    ConfigBackup(MainActivity activity) {
        a = activity;
        savedCommands = new SavedCommands(activity);
        a.findViewById(R.id.exportConfig).setOnClickListener(v -> chooseExportFile());
        a.findViewById(R.id.importConfig).setOnClickListener(v -> chooseImportFile());
    }

    // ---------- Formato (sem Android, para os testes) ----------

    static String toJson(List<AdbCommands.Preset> commands, List<Devices.Device> devices, String pkg)
            throws JSONException {
        JSONArray cmds = new JSONArray();
        for (AdbCommands.Preset p : commands) {
            cmds.put(new JSONObject().put("nome", p.label).put("comando", p.command).put("confirmar", p.confirm));
        }
        JSONArray devs = new JSONArray();
        for (Devices.Device d : devices) {
            devs.put(new JSONObject().put("nome", d.name).put("ip", d.host).put("porta", d.port));
        }
        return new JSONObject()
                .put("formato", FORMAT)
                .put("versao", VERSION)
                .put("comandos", cmds)
                .put("aparelhos", devs)
                .put("pacoteLogAutomatico", pkg == null ? "" : pkg)
                .toString(2);
    }

    /** Lê o arquivo; lança JSONException com explicação se não for uma configuração do ADB Mobile. */
    static Data fromJson(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (!FORMAT.equals(o.optString("formato"))) {
            throw new JSONException("o arquivo não é uma configuração exportada pelo ADB Mobile");
        }
        if (o.optInt("versao", 0) > VERSION) {
            throw new JSONException("o arquivo é de uma versão mais nova do ADB Mobile; atualize o app");
        }
        Data data = new Data();
        JSONArray cmds = o.optJSONArray("comandos");
        for (int i = 0; cmds != null && i < cmds.length(); i++) {
            JSONObject c = cmds.getJSONObject(i);
            String name = c.optString("nome").trim();
            String command = c.optString("comando").trim();
            if (name.isEmpty() || command.isEmpty()) continue;
            data.commands.add(AdbCommands.saved(name, command, c.optBoolean("confirmar", false)));
        }
        JSONArray devs = o.optJSONArray("aparelhos");
        for (int i = 0; devs != null && i < devs.length(); i++) {
            JSONObject d = devs.getJSONObject(i);
            String host = d.optString("ip").trim();
            int port = d.optInt("porta", -1);
            if (host.isEmpty() || port <= 0 || port > 65535) continue;
            data.devices.add(new Devices.Device(d.optString("nome").trim(), host, port, false));
        }
        data.monitorPackage = o.optString("pacoteLogAutomatico", "").trim();
        return data;
    }

    /**
     * Junta os aparelhos importados à lista atual (mesmo IP = mesmo aparelho: atualiza a porta e,
     * se vier, o nome; os novos entram desmarcados). Devolve {novos, atualizados}.
     */
    static int[] mergeDevices(List<Devices.Device> current, List<Devices.Device> incoming) {
        int added = 0;
        int updated = 0;
        for (Devices.Device in : incoming) {
            Devices.Device same = null;
            for (Devices.Device d : current) {
                if (d.host.equals(in.host)) same = d;
            }
            if (same == null) {
                current.add(new Devices.Device(in.name, in.host, in.port, false));
                added++;
            } else if (same.port != in.port || (!in.name.isEmpty() && !in.name.equals(same.name))) {
                same.port = in.port;
                if (!in.name.isEmpty()) same.name = in.name;
                updated++;
            }
        }
        return new int[]{added, updated};
    }

    // ---------- Exportar ----------

    private void chooseExportFile() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "adb-mobile-config_"
                + new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date()) + ".json");
        a.openPicker(intent, MainActivity.REQUEST_EXPORT_CONFIG);
    }

    void onExportFileChosen(Uri uri) {
        if (uri == null) {
            a.setStatus(Status.INFO, "Exportação cancelada.");
            return;
        }
        List<AdbCommands.Preset> commands = savedCommands.load();
        List<Devices.Device> devices = Devices.load(a.prefs);
        String pkg = a.monitor.packageName();
        String name = a.fileName(uri);
        try (OutputStream out = a.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("não foi possível gravar em " + name);
            out.write(toJson(commands, devices, pkg).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            a.setStatus(Status.ERROR, "Falha ao exportar: " + Adb.describe(e));
            return;
        }
        String message = "Configuração exportada (" + commands.size() + " comando(s), " + devices.size()
                + " aparelho(s)" + (pkg.isEmpty() ? "" : ", pacote " + pkg) + "): " + name;
        a.append(message);
        a.setStatus(Status.SUCCESS, message);
    }

    // ---------- Importar ----------

    private void chooseImportFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // alguns gerenciadores não marcam .json como application/json
        a.openPicker(intent, MainActivity.REQUEST_IMPORT_CONFIG);
    }

    void onImportFileChosen(Uri uri) {
        if (uri == null) {
            a.setStatus(Status.INFO, "Importação cancelada.");
            return;
        }
        String name = a.fileName(uri);
        final Data data;
        try (InputStream in = a.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("não foi possível abrir " + name);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bytes.write(buf, 0, n);
                if (bytes.size() > 2_000_000) throw new IOException("arquivo grande demais para uma configuração");
            }
            data = fromJson(bytes.toString("UTF-8"));
        } catch (Exception e) {
            a.setStatus(Status.ERROR, "Não foi possível importar " + name + ": " + Adb.describe(e));
            return;
        }

        int replaced = 0;
        for (AdbCommands.Preset p : data.commands) if (savedCommands.contains(p.label)) replaced++;
        List<Devices.Device> preview = Devices.load(a.prefs);
        int[] dev = mergeDevices(preview, data.devices);
        String currentPkg = a.monitor.packageName();
        boolean setPkg = !data.monitorPackage.isEmpty() && currentPkg.isEmpty();

        StringBuilder msg = new StringBuilder("Arquivo: " + name + "\n\n");
        msg.append("Comandos salvos: ").append(data.commands.size());
        if (replaced > 0) msg.append(" (").append(replaced).append(" substituem os de mesmo nome)");
        msg.append("\nAparelhos: ").append(dev[0]).append(" novo(s), ").append(dev[1]).append(" atualizado(s)");
        if (setPkg) msg.append("\nPacote do Log automático: ").append(data.monitorPackage);
        else if (!data.monitorPackage.isEmpty() && !data.monitorPackage.equals(currentPkg)) {
            msg.append("\nPacote do Log automático: mantido ").append(currentPkg)
                    .append(" (o arquivo traz ").append(data.monitorPackage).append(")");
        }
        msg.append("\n\nNada é apagado: o que já existe e não está no arquivo continua.");
        final int replacedCount = replaced;
        new AlertDialog.Builder(a)
                .setTitle("Importar configuração")
                .setMessage(msg.toString())
                .setPositiveButton("Importar", (d, w) -> apply(data, setPkg, replacedCount, name))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void apply(Data data, boolean setPkg, int replaced, String name) {
        for (AdbCommands.Preset p : data.commands) savedCommands.put(p.label, p.command, p.confirm);
        List<Devices.Device> devices = Devices.load(a.prefs);
        int[] dev = mergeDevices(devices, data.devices);
        Devices.save(a.prefs, devices);
        if (setPkg) a.monitor.usePackage(data.monitorPackage);
        a.commands.reloadSaved();
        a.devices.reload();
        String message = "Configuração importada de " + name + ": " + data.commands.size() + " comando(s)"
                + (replaced > 0 ? " (" + replaced + " substituído(s))" : "") + ", " + dev[0]
                + " aparelho(s) novo(s), " + dev[1] + " atualizado(s)"
                + (setPkg ? ", pacote " + data.monitorPackage : "") + ".";
        a.append(message);
        a.setStatus(Status.SUCCESS, message);
    }
}
