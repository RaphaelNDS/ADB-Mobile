package com.bkp.adbmobile;

import android.app.AlertDialog;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** "Outro aparelho": lista de aparelhos salvos e o formulário de nome, IP e porta. */
final class DevicesPanel {

    private final MainActivity a;
    private final EditText hostField;
    private final EditText connectPortField;
    private final EditText deviceNameField;
    private final LinearLayout devicesList;
    private final TextView devicesEmpty;
    private final TextView devicesSummary;
    private final TextView selectAll;
    private List<Devices.Device> devices;

    DevicesPanel(MainActivity activity) {
        a = activity;
        hostField = a.findViewById(R.id.host);
        connectPortField = a.findViewById(R.id.connectPort);
        deviceNameField = a.findViewById(R.id.deviceName);
        devicesList = a.findViewById(R.id.devicesList);
        devicesEmpty = a.findViewById(R.id.devicesEmpty);
        devicesSummary = a.findViewById(R.id.devicesSummary);
        selectAll = a.findViewById(R.id.selectAll);

        hostField.setText(a.prefs.getString("host", ""));
        connectPortField.setText(a.prefs.getString("port", "5555"));
        devices = Devices.load(a.prefs);
        a.findViewById(R.id.saveDevice).setOnClickListener(v -> saveDevice());
        selectAll.setOnClickListener(v -> toggleAll());
        render();
    }

    /** Recarrega a lista salva (o monitoramento pode ter atualizado uma porta). */
    void reload() {
        devices = Devices.load(a.prefs);
        render();
    }

    private void render() {
        devicesList.removeAllViews();
        for (final Devices.Device d : devices) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(a.dp(48));

            CheckBox check = new CheckBox(a);
            check.setChecked(d.selected);
            check.setOnCheckedChangeListener((b, checked) -> {
                d.selected = checked;
                Devices.save(a.prefs, devices);
                updateSummary();
            });

            TextView text = new TextView(a);
            text.setText(d.label() + (d.label().equals(d.host) ? "" : "\n" + d.host) + ":" + d.port);
            text.setTextColor(a.color(R.color.text_primary));
            text.setTextSize(14);
            text.setPadding(a.dp(4), a.dp(6), a.dp(4), a.dp(6));
            text.setBackgroundResource(selectableBackground());
            // Tocar no nome preenche o formulário, para editar ou parear esse aparelho.
            text.setOnClickListener(v -> {
                deviceNameField.setText(d.name);
                hostField.setText(d.host);
                connectPortField.setText(String.valueOf(d.port));
            });
            row.addView(check);
            row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView remove = new TextView(a);
            remove.setText("Remover");
            remove.setTextColor(a.color(R.color.error));
            remove.setTextSize(13);
            remove.setPadding(a.dp(10), a.dp(10), a.dp(4), a.dp(10));
            remove.setBackgroundResource(selectableBackground());
            remove.setOnClickListener(v -> new AlertDialog.Builder(a)
                    .setTitle("Remover aparelho")
                    .setMessage("Remover " + d.label() + " (" + d.address() + ") da lista?")
                    .setPositiveButton("Remover", (dlg, w) -> {
                        devices.remove(d);
                        Devices.save(a.prefs, devices);
                        render();
                    })
                    .setNegativeButton("Cancelar", null)
                    .show());
            row.addView(remove);
            devicesList.addView(row);
        }
        devicesEmpty.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
        updateSummary();
    }

    private int selectableBackground() {
        android.util.TypedValue value = new android.util.TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return value.resourceId;
    }

    private void updateSummary() {
        int n = Devices.selected(devices).size();
        devicesSummary.setVisibility(devices.isEmpty() ? View.GONE : View.VISIBLE);
        devicesSummary.setText(n == 0 ? "Nenhum aparelho marcado."
                : n == 1 ? "1 aparelho marcado." : n + " aparelhos marcados: os comandos rodam em todos ao mesmo tempo.");
        selectAll.setText(!devices.isEmpty() && n == devices.size() ? "Desmarcar todos" : "Marcar todos");
    }

    private void toggleAll() {
        boolean all = !devices.isEmpty() && Devices.selected(devices).size() == devices.size();
        for (Devices.Device d : devices) d.selected = !all;
        Devices.save(a.prefs, devices);
        render();
    }

    /**
     * IP e porta digitados no formulário (usados no pareamento e ao salvar), sem nome;
     * null (com aviso) se inválidos.
     */
    Devices.Device formTarget() {
        String host = hostField.getText().toString().trim();
        String portText = connectPortField.getText().toString().trim();
        int port = Devices.parsePort(portText);
        if (host.isEmpty() || port < 0) {
            a.setStatus(MainActivity.Status.ERROR, "Informe o IP e a porta de conexão do aparelho alvo.");
            return null;
        }
        a.prefs.edit().putString("host", host).putString("port", portText).apply();
        return new Devices.Device("", host, port, false);
    }

    /** Adiciona o aparelho do formulário ou, se o IP já está na lista, atualiza nome e porta. */
    private void saveDevice() {
        Devices.Device t = formTarget();
        if (t == null) return;
        String name = deviceNameField.getText().toString().trim();
        Devices.Device d = Devices.find(devices, t.host);
        if (d == null) {
            devices.add(new Devices.Device(name, t.host, t.port, true));
            a.append("Aparelho salvo: " + (name.isEmpty() ? t.host : name) + " (" + t.address() + ")");
        } else {
            d.name = name;
            d.port = t.port;
            d.selected = true;
            a.append("Aparelho atualizado: " + d.label() + " (" + d.address() + ")");
        }
        Devices.save(a.prefs, devices);
        render();
        deviceNameField.setText("");
        a.setStatus(MainActivity.Status.SUCCESS, "Aparelho salvo e marcado.");
    }

    /** Aparelhos marcados; null (com aviso) se nenhum. */
    List<Devices.Device> selected() {
        List<Devices.Device> list = Devices.selected(devices);
        if (list.isEmpty()) {
            a.setStatus(MainActivity.Status.ERROR, devices.isEmpty()
                    ? "Salve ao menos um aparelho em \"Outro aparelho\"."
                    : "Marque ao menos um aparelho na lista.");
            return null;
        }
        return list;
    }

    /** Para log, monitoramento e outras operações de um aparelho só: exige exatamente um marcado. */
    Devices.Device singleTarget(String what) {
        List<Devices.Device> list = selected();
        if (list == null) return null;
        if (list.size() > 1) {
            a.setStatus(MainActivity.Status.ERROR, what + " funciona com um aparelho por vez. Deixe só um marcado.");
            return null;
        }
        return list.get(0);
    }

    /** Avisa quando a porta de um aparelho mudou e a busca na rede achou a atual. */
    Adb.PortListener portChangedFor(final String host) {
        return newPort -> {
            Devices.updatePort(a.prefs, host, newPort);
            a.runOnUiThread(() -> {
                reload();
                if (host.equals(hostField.getText().toString().trim())) {
                    connectPortField.setText(String.valueOf(newPort));
                }
                a.append("Porta de " + host + " mudou; usando " + newPort + " (encontrada na rede).");
            });
        };
    }

    /** "em Quiosque 1" ou "em 3 aparelhos" para as confirmações; null (com aviso) se nenhum marcado. */
    String whereSelected() {
        List<Devices.Device> targets = selected();
        if (targets == null) return null;
        return targets.size() > 1 ? "em " + targets.size() + " aparelhos" : "em " + targets.get(0).label();
    }

    /** "[Quiosque 1]" para um aparelho, "[3 aparelhos]" para vários. */
    static String label(List<Devices.Device> targets) {
        return targets.size() == 1 ? "[" + targets.get(0).label() + "]" : "[" + targets.size() + " aparelhos]";
    }
}
