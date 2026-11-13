package com.bkp.adbmobile;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import io.github.muntashirakon.adb.AdbPairingRequiredException;

/**
 * "Inventário dos aparelhos": um comando só em cada aparelho (marcados, ou este) junta modelo,
 * Android, bateria, espaço, Kiosk e Device Owner. Mostra uma tabela, uma linha por aparelho, e
 * salva em CSV (abre no Excel).
 */
final class Inventory {

    /** Colunas, na ordem da tabela e do CSV; a chave é a mesma impressa pelo {@link #COMMAND}. */
    static final String[][] COLUMNS = {
            {"aparelho", "Aparelho"},
            {"modelo", "Modelo"},
            {"android", "Android"},
            {"bateria", "Bateria"},
            {"livre", "Espaço livre"},
            {"kiosk", "Kiosk"},
            {"owner", "Device Owner"},
            {"frente", "Em primeiro plano"},
            {"erro", "Erro"},
    };

    /** Imprime "chave=valor" por linha (o parse ignora o resto). */
    static final String COMMAND = "echo \"modelo=$(getprop ro.product.manufacturer) $(getprop ro.product.model)\";"
            + " echo \"android=$(getprop ro.build.version.release) (API $(getprop ro.build.version.sdk))\";"
            + " b=$(dumpsys battery 2>/dev/null); l=$(echo \"$b\" | grep -m1 ' level:' | tr -dc 0-9);"
            + " c=$(echo \"$b\" | grep -cE '(AC|USB|Wireless) powered: true');"
            + " echo \"bateria=$l% $([ \"$c\" -gt 0 ] && echo '(carregando)')\";"
            + " echo \"livre_kb=$(df -k /data 2>/dev/null | tail -n 1 | awk '{print $4}')\";"
            + " echo \"kiosk=$(dumpsys package " + AdbCommands.KIOSK_PACKAGE + " 2>/dev/null | grep -m1 versionName= | cut -d= -f2)\";"
            + " " + AdbCommands.DEVICE_OWNER_READ.replace("echo ${o:-nenhum}", "echo \"owner=${o:-nenhum}\"") + ";"
            + " echo \"frente=$(" + Adb.FOREGROUND_COMMAND + " | grep -oE 'u[0-9]+ [^/ ]+' | head -n 1 | cut -d' ' -f2)\"";

    private final MainActivity a;
    /** Linhas da última consulta (para o "Salvar CSV"). */
    private List<Map<String, String>> last = Collections.emptyList();

    Inventory(MainActivity activity) {
        a = activity;
    }

    /** Valores de uma saída do {@link #COMMAND}, já no formato da tabela. */
    static Map<String, String> parse(String out) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (String line : out.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) raw.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        Map<String, String> row = new LinkedHashMap<>();
        row.put("modelo", raw.containsKey("modelo") ? raw.get("modelo") : "");
        row.put("android", raw.containsKey("android") ? raw.get("android") : "");
        String battery = raw.containsKey("bateria") ? raw.get("bateria") : "";
        row.put("bateria", battery.startsWith("%") ? "" : battery);
        row.put("livre", freeSpace(raw.get("livre_kb")));
        String kiosk = raw.containsKey("kiosk") ? raw.get("kiosk") : "";
        row.put("kiosk", kiosk.isEmpty() ? "não instalado" : kiosk);
        String owner = raw.containsKey("owner") ? raw.get("owner") : "nenhum";
        row.put("owner", owner.startsWith(AdbCommands.KIOSK_PACKAGE + "/") ? "Kiosk"
                : owner.equals("nenhum") || owner.isEmpty() ? "nenhum" : owner);
        row.put("frente", raw.containsKey("frente") ? raw.get("frente") : "");
        return row;
    }

    private static String freeSpace(String kb) {
        if (kb == null || !kb.matches("\\d+")) return "";
        double gb = Long.parseLong(kb) / 1048576.0;
        return String.format(Locale.US, gb >= 10 ? "%.0f GB" : "%.1f GB", gb);
    }

    /** CSV com cabeçalho; separador ";" e BOM, como o Excel em português espera. */
    static String csv(List<Map<String, String>> rows) {
        StringBuilder b = new StringBuilder().append((char) 0xFEFF); // BOM: o Excel reconhece o UTF-8
        for (int i = 0; i < COLUMNS.length; i++) b.append(i > 0 ? ";" : "").append(cell(COLUMNS[i][1]));
        b.append("\r\n");
        for (Map<String, String> r : rows) {
            for (int i = 0; i < COLUMNS.length; i++) {
                String v = r.get(COLUMNS[i][0]);
                b.append(i > 0 ? ";" : "").append(cell(v == null ? "" : v));
            }
            b.append("\r\n");
        }
        return b.toString();
    }

    private static String cell(String v) {
        if (v.contains(";") || v.contains("\"") || v.contains("\n")) return "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    // ---------- Tela ----------

    void run() {
        final List<Devices.Device> targets;
        if (a.isRemote()) {
            targets = a.devices.selected();
            if (targets == null) return;
        } else {
            targets = new ArrayList<>();
            targets.add(null);
        }
        a.start("Inventário " + (targets.get(0) == null ? "deste aparelho" : DevicesPanel.label(targets)));
        final List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) rows.add(null);
        final CountDownLatch done = new CountDownLatch(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            final int index = i;
            final Devices.Device d = targets.get(i);
            a.background(() -> {
                Map<String, String> row;
                try {
                    Adb.Shell shell = a.openTarget(d);
                    try {
                        row = parse(shell.run(COMMAND));
                    } finally {
                        shell.close();
                    }
                } catch (AdbPairingRequiredException e) {
                    row = new LinkedHashMap<>();
                    row.put("erro", MainActivity.NEEDS_PAIRING);
                } catch (Exception e) {
                    row = new LinkedHashMap<>();
                    row.put("erro", DeviceRunner.errorText(e));
                }
                row.put("aparelho", d == null ? "este aparelho" : d.label() + (d.name == null || d.name.isEmpty()
                        ? "" : " (" + d.host + ")"));
                synchronized (rows) {
                    rows.set(index, row);
                }
                done.countDown();
            });
        }
        a.background(() -> {
            try {
                done.await();
            } catch (InterruptedException e) {
                return;
            }
            int failed = 0;
            for (Map<String, String> r : rows) if (r.containsKey("erro")) failed++;
            a.finish(failed == 0 ? MainActivity.Status.SUCCESS : MainActivity.Status.ERROR,
                    "Inventário: " + (rows.size() - failed) + " de " + rows.size() + " aparelho(s) responderam.");
            a.runOnUiThread(() -> show(rows));
        });
    }

    private void show(List<Map<String, String>> rows) {
        if (a.isFinishing() || a.isDestroyed()) return;
        last = rows;
        TableLayout table = new TableLayout(a);
        table.setPadding(a.dp(12), a.dp(4), a.dp(12), a.dp(4));
        boolean anyError = false;
        for (Map<String, String> r : rows) anyError |= r.containsKey("erro");
        table.addView(row(null, anyError));
        for (Map<String, String> r : rows) table.addView(row(r, anyError));
        HorizontalScrollView h = new HorizontalScrollView(a);
        h.addView(table);
        ScrollView v = new ScrollView(a);
        v.addView(h);
        new AlertDialog.Builder(a)
                .setTitle("Inventário (" + rows.size() + ")")
                .setView(v)
                .setPositiveButton("Fechar", null)
                .setNeutralButton("Salvar CSV", (d, w) -> chooseCsvFile())
                .show();
    }

    private TableRow row(Map<String, String> r, boolean withError) {
        TableRow tr = new TableRow(a);
        for (String[] col : COLUMNS) {
            if (col[0].equals("erro") && !withError) continue;
            TextView t = new TextView(a);
            t.setPadding(a.dp(6), a.dp(6), a.dp(10), a.dp(6));
            t.setGravity(Gravity.START);
            t.setTextSize(13);
            if (r == null) {
                t.setText(col[1]);
                t.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                String value = r.get(col[0]);
                t.setText(value == null ? "" : value);
                t.setTextIsSelectable(true);
                if (col[0].equals("erro")) t.setTextColor(a.color(R.color.error));
                // Destaques: sem Kiosk ou Kiosk sem Device Owner pedem atenção.
                if (col[0].equals("owner") && !"Kiosk".equals(value) && !r.containsKey("erro"))
                    t.setTextColor(a.color(R.color.error));
            }
            tr.addView(t);
        }
        return tr;
    }

    private void chooseCsvFile() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "inventario_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
                .format(new Date()) + ".csv");
        a.openPicker(intent, MainActivity.REQUEST_SAVE_INVENTORY);
    }

    void onCsvFileChosen(Uri uri) {
        if (uri == null) {
            a.setStatus(MainActivity.Status.INFO, "CSV não salvo.");
            return;
        }
        try (OutputStream out = a.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new java.io.IOException("não foi possível abrir o arquivo");
            out.write(csv(last).getBytes(StandardCharsets.UTF_8));
            a.append("Inventário salvo: " + a.fileName(uri) + " (" + last.size() + " aparelho(s))");
            a.setStatus(MainActivity.Status.SUCCESS, "Inventário salvo em " + a.fileName(uri) + ".");
        } catch (Exception e) {
            a.setStatus(MainActivity.Status.ERROR, "Não foi possível salvar o CSV: " + Adb.describe(e));
        }
    }
}
