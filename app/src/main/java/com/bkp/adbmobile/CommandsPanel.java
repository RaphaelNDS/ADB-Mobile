package com.bkp.adbmobile;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import io.github.muntashirakon.adb.AdbPairingRequiredException;

import static com.bkp.adbmobile.MainActivity.Status;

/** Cartão "Comando": lista de comandos prontos e salvos, campo de texto, Executar e Ler valor. */
final class CommandsPanel {

    /** Tag das views de título de grupo na lista de comandos. */
    private static final String HEADER = "header";

    private final MainActivity a;
    private final Spinner presetSpinner;
    private final EditText commandField;
    private final Button deleteCommandButton;
    private final Button readButton;
    private final SavedCommands savedCommands;
    /** Itens da lista de comandos: "Meus comandos" (se houver) e depois os prontos. */
    private final List<AdbCommands.Preset> presets = new ArrayList<>();
    private final ArrayAdapter<AdbCommands.Preset> presetAdapter;
    /** Posição já mostrada no campo; o Spinner avisa seleções que não devem trocar o texto. */
    private int shownPreset;

    CommandsPanel(MainActivity activity, Button read) {
        a = activity;
        readButton = read;
        presetSpinner = a.findViewById(R.id.preset);
        commandField = a.findViewById(R.id.command);
        savedCommands = new SavedCommands(a);
        deleteCommandButton = a.findViewById(R.id.deleteCommand);
        a.findViewById(R.id.saveCommand).setOnClickListener(v -> askSaveCommand());
        deleteCommandButton.setOnClickListener(v -> confirmDeleteCommand());
        loadPresets();
        presetAdapter = new ArrayAdapter<AdbCommands.Preset>(a, android.R.layout.simple_spinner_item, presets) {
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
                title.setTextColor(a.color(R.color.accent));
                title.setBackgroundColor(a.color(R.color.primary_tint));
                title.setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(6));
                return title;
            }
        };
        presetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(presetAdapter);

        final int saved = savedPresetPosition();
        // O Spinner avisa a seleção inicial ao desenhar; ela não deve sobrescrever o texto salvo.
        shownPreset = saved;
        presetSpinner.setSelection(saved, false);
        // Texto não editado volta como o comando pronto atual (que pode ter mudado numa versão nova).
        commandField.setText(AdbCommands.restoredCommand(a.prefs.getString("command", null),
                a.prefs.getString("commandBase", null), fill(presets.get(saved).command)));
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
                a.prefs.edit().putString("presetLabel", presets.get(position).key()).apply();
                commandField.setText(fill(presets.get(position).command));
                if (presets.get(position).command.isEmpty()) commandField.requestFocus();
                updatePresetButtons();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    // ---------- Lista ----------

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
        a.prefs.edit().putString("presetLabel", key).apply();
        int position = savedPresetPosition();
        shownPreset = position;
        presetSpinner.setSelection(position, false);
        updatePresetButtons();
    }

    /** Posição do comando escolhido da última vez (ver {@link AdbCommands#positionOf}). */
    private int savedPresetPosition() {
        return AdbCommands.positionOf(presets, a.prefs.getString("presetLabel", null), a.prefs.getInt("preset", 0));
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

    /** Troca {pacote} pelo pacote informado no Log automático. */
    private String fill(String command) {
        String pkg = a.monitor.packageName();
        return pkg.isEmpty() ? command : command.replace(AdbCommands.PACKAGE, pkg);
    }

    // ---------- Meus comandos ----------

    /** Pede um nome e salva o texto do campo em "Meus comandos". */
    private void askSaveCommand() {
        final String cmd = commandField.getText().toString().trim();
        if (cmd.isEmpty()) {
            a.setStatus(Status.ERROR, "Digite o comando antes de salvar.");
            return;
        }
        final AdbCommands.Preset current = selectedPreset();
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(24), a.dp(8), a.dp(24), 0);
        final EditText nameField = new EditText(a);
        nameField.setHint("Nome do comando");
        nameField.setSingleLine(true);
        // Ao editar um salvo, sugere o mesmo nome para atualizar em vez de duplicar.
        if (current.userSaved) {
            nameField.setText(current.label);
            nameField.setSelection(current.label.length());
        }
        box.addView(nameField);
        final CheckBox confirmBox = new CheckBox(a);
        confirmBox.setText("Pedir confirmação antes de executar");
        confirmBox.setChecked(current.confirm);
        box.addView(confirmBox);
        TextView preview = new TextView(a);
        preview.setText("adb shell " + cmd);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextSize(12);
        preview.setTextColor(a.color(R.color.text_primary));
        preview.setPadding(0, a.dp(8), 0, 0);
        box.addView(preview);

        final AlertDialog dialog = new AlertDialog.Builder(a)
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
                new AlertDialog.Builder(a)
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
        a.prefs.edit().putString("command", cmd).putString("commandBase", cmd).apply();
        reloadPresets(AdbCommands.saved(name, cmd, confirm).key());
        String message = (replaced ? "Comando atualizado: " : "Comando salvo: ") + name;
        a.append(message);
        a.setStatus(Status.SUCCESS, message);
    }

    private void confirmDeleteCommand() {
        final AdbCommands.Preset p = selectedPreset();
        if (!p.userSaved) return;
        new AlertDialog.Builder(a)
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
                    a.prefs.edit().putString("presetLabel", now.key()).putString("command", text)
                            .putString("commandBase", text).apply();
                    String message = "Comando excluído: " + p.label;
                    a.append(message);
                    a.setStatus(Status.INFO, message);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------- Execução ----------

    /** Lança a falha indicada pela saída do comando (o shell não devolve o código de saída). */
    private static void checkOutput(String out) throws IOException {
        String failure = AdbCommands.failure(out);
        if (failure != null) throw new IOException(failure);
    }

    void confirmAndRun() {
        final String cmd = commandField.getText().toString().trim();
        if (cmd.isEmpty()) {
            a.setStatus(Status.ERROR, "Escolha ou digite um comando.");
            return;
        }
        if (cmd.contains(AdbCommands.PACKAGE)) {
            a.setStatus(Status.ERROR, "Informe o pacote do app (em \"Log automático\") ou edite o comando.");
            return;
        }
        final AdbCommands.Preset preset = selectedPreset();
        // A base (o comando pronto naquele momento) diz, ao reabrir, se o texto foi editado.
        a.prefs.edit().putString("command", cmd).putString("commandBase", fill(preset.command)).apply();
        if (!preset.confirm) {
            runCommand(cmd, preset);
            return;
        }
        String where = "no aparelho alvo";
        if (a.isRemote()) {
            where = a.devices.whereSelected();
            if (where == null) return;
        }
        String message = "Executar " + where + "?\n\nadb shell " + cmd;
        if (preset.warning != null) message += "\n\nAtenção: " + preset.warning;
        new AlertDialog.Builder(a)
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
        final String read = AdbCommands.readAfter(preset, fill(preset.command), cmd);
        final String name = preset.readName;
        if (a.isRemote()) {
            final List<Devices.Device> targets = a.devices.selected();
            if (targets == null) return;
            a.start(DevicesPanel.label(targets) + " " + AdbCommands.logLine(preset, fill(preset.command), cmd));
            a.log("Conectando... se o alvo solicitar, autorize a depuração na tela dele.");
            a.runner.run(targets, read == null ? "Executado" : "Aplicado", d -> {
                Adb.PortListener onPort = a.devices.portChangedFor(d.host);
                String[] out = read == null
                        ? Adb.remoteShell(a, d.host, d.port, onPort, cmd)
                        : Adb.remoteShell(a, d.host, d.port, onPort, cmd, read);
                checkOutput(out[0]);
                a.logOutput("[" + d.label() + "] ", out[0]);
                return read == null ? "" : name + " = " + out[1];
            });
            return;
        }

        a.start(AdbCommands.logLine(preset, fill(preset.command), cmd));
        a.background(() -> {
            // 1) Root, se existir (testado antes para não executar o comando duas vezes)
            boolean root;
            try {
                Adb.su("true");
                root = true;
            } catch (Exception e) {
                root = false;
                a.log("Root indisponível; usando ADB local.");
            }
            try {
                if (root) {
                    String out = Adb.su(cmd);
                    checkOutput(out);
                    a.logOutput("", out);
                    a.finish(Status.SUCCESS, read == null ? "Executado via root."
                            : "Aplicado via root. " + name + " = " + Adb.su(read));
                    return;
                }
                // 2) ADB pela Depuração sem fio
                String out = Adb.shell(a, cmd);
                checkOutput(out);
                a.logOutput("", out);
                String value = read == null ? null : Adb.shell(a, read);
                String warning = Adb.grantSelfSecureSettings(a);
                if (warning != null) a.log(warning);
                a.finish(Status.SUCCESS, read == null ? "Executado neste aparelho."
                        : "Aplicado neste aparelho. " + name + " = " + value);
            } catch (AdbPairingRequiredException e) {
                a.pairingRequired(null);
            } catch (Exception e) {
                a.finish(Status.ERROR, "Falha: " + DeviceRunner.errorText(e));
            }
        });
    }

    /** Recarrega "Meus comandos" (ex.: depois de importar) mantendo o comando escolhido e o texto. */
    void reloadSaved() {
        reloadPresets(selectedPreset().key());
    }

    // ---------- Vários comandos (lote) ----------

    private static final String BATCH_KEYS = "batchKeys";

    /** Escolhe os comandos do lote (a última escolha vem marcada). */
    void chooseBatch() {
        final List<AdbCommands.Preset> items = new ArrayList<>();
        for (AdbCommands.Preset p : presets) if (!p.isHeader() && !p.command.isEmpty()) items.add(p);
        List<String> last = new ArrayList<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(a.prefs.getString(BATCH_KEYS, "[]"));
            for (int i = 0; i < arr.length(); i++) last.add(arr.getString(i));
        } catch (org.json.JSONException ignored) {
            // Escolha anterior ilegível: começa sem nada marcado.
        }
        final String[] labels = new String[items.size()];
        final boolean[] checked = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            AdbCommands.Preset p = items.get(i);
            labels[i] = (p.userSaved ? "★ " : "") + p.label;
            checked[i] = last.contains(p.key());
        }
        new AlertDialog.Builder(a)
                .setTitle("Vários comandos (rodam na ordem da lista)")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Continuar", (d, w) -> {
                    List<String> keys = new ArrayList<>();
                    for (int i = 0; i < items.size(); i++) if (checked[i]) keys.add(items.get(i).key());
                    a.prefs.edit().putString(BATCH_KEYS, new org.json.JSONArray(keys).toString()).apply();
                    confirmBatch(AdbCommands.batchSteps(items, keys));
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmBatch(final List<AdbCommands.Preset> steps) {
        if (steps.isEmpty()) {
            a.setStatus(Status.ERROR, "Marque pelo menos um comando.");
            return;
        }
        final List<String> commands = new ArrayList<>();
        for (AdbCommands.Preset p : steps) {
            String cmd = fill(p.command);
            if (cmd.contains(AdbCommands.PACKAGE)) {
                a.setStatus(Status.ERROR, "\"" + p.label + "\" usa o pacote do app: informe-o em \"Log automático\".");
                return;
            }
            commands.add(cmd);
        }
        String where = "no aparelho alvo";
        if (a.isRemote()) {
            where = a.devices.whereSelected();
            if (where == null) return;
        }
        StringBuilder msg = new StringBuilder("Executar " + where + ", nesta ordem?\n");
        List<String> sensitive = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            msg.append("\n").append(i + 1).append(". ").append(steps.get(i).label);
            if (steps.get(i).confirm) sensitive.add(steps.get(i).label);
        }
        msg.append("\n\nSe um comando falhar, os seguintes não rodam naquele aparelho.");
        if (!sensitive.isEmpty()) {
            msg.append("\n\nAtenção: inclui comandos que normalmente pedem confirmação: ")
                    .append(android.text.TextUtils.join(", ", sensitive)).append(".");
        }
        new AlertDialog.Builder(a)
                .setTitle("Executar " + steps.size() + " comando(s)")
                .setMessage(msg.toString())
                .setPositiveButton("Executar", (d, w) -> runBatch(steps, commands))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Roda os passos em sequência numa sessão só; para no primeiro erro (por aparelho). */
    private static String runSteps(MainActivity a, Adb.Shell shell, String prefix,
                                   List<AdbCommands.Preset> steps, List<String> commands) throws Exception {
        int total = steps.size();
        for (int i = 0; i < total; i++) {
            String line = AdbCommands.batchStepLine(i, total, steps.get(i));
            try {
                String out = shell.run(commands.get(i));
                checkOutput(out);
                a.log(prefix + line + ": OK");
                a.logOutput(prefix, out);
            } catch (AdbPairingRequiredException e) {
                throw e;
            } catch (Exception e) {
                a.log(prefix + line + ": ERRO");
                throw new IOException("parou em " + line + ": " + DeviceRunner.errorText(e)
                        + (i + 1 < total ? " (" + (total - i - 1) + " não executado(s))" : ""));
            }
        }
        return total + " comando(s) OK";
    }

    private void runBatch(final List<AdbCommands.Preset> steps, final List<String> commands) {
        final String title = "Vários comandos (" + steps.size() + ")";
        if (a.isRemote()) {
            final List<Devices.Device> targets = a.devices.selected();
            if (targets == null) return;
            a.start(DevicesPanel.label(targets) + " " + title);
            a.log("Conectando... se o alvo solicitar, autorize a depuração na tela dele.");
            a.runner.run(targets, "Executado", d -> {
                Adb.Shell shell = Adb.openRemote(a, d.host, d.port, a.devices.portChangedFor(d.host));
                try {
                    return runSteps(a, shell, "[" + d.label() + "] ", steps, commands);
                } finally {
                    shell.close();
                }
            });
            return;
        }
        a.start(title);
        a.background(() -> {
            Adb.Shell shell = Adb.openLocal(a);
            try {
                a.finish(Status.SUCCESS, "Vários comandos: " + runSteps(a, shell, "", steps, commands) + ".");
            } catch (AdbPairingRequiredException e) {
                a.pairingRequired(null);
            } catch (Exception e) {
                a.finish(Status.ERROR, "Vários comandos: " + Adb.describe(e));
            } finally {
                shell.close();
            }
        });
    }

    void readValue() {
        final AdbCommands.Preset preset = selectedPreset();
        final String read = preset.read;
        if (read == null) return;
        final String name = preset.readName;
        if (a.isRemote()) {
            final List<Devices.Device> targets = a.devices.selected();
            if (targets == null) return;
            a.start(DevicesPanel.label(targets) + " Ler valor atual: " + name);
            a.runner.run(targets, "Lido", d -> name + " = "
                    + Adb.remoteShell(a, d.host, d.port, a.devices.portChangedFor(d.host), read)[0]);
            return;
        }
        a.start("Ler valor atual: " + name);
        a.background(() -> {
            try {
                a.finish(Status.INFO, "Valor atual: " + name + " = " + Adb.shell(a, read));
            } catch (AdbPairingRequiredException e) {
                a.pairingRequired(null);
            } catch (Exception e) {
                try {
                    a.finish(Status.INFO, "Valor atual (root): " + name + " = " + Adb.su(read));
                } catch (Exception e2) {
                    a.finish(Status.ERROR, "Não foi possível ler: " + Adb.describe(e));
                }
            }
        });
    }
}
