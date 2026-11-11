package com.bkp.adbmobile;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import io.github.muntashirakon.adb.AdbPairingRequiredException;

import static com.bkp.adbmobile.MainActivity.Status;

/** "Baixar arquivo": navegador de pastas do aparelho alvo e download para o celular. */
final class RemoteBrowser {

    // java.util.function só existe a partir do Android 7; o app roda desde o 5.
    private interface FolderOpener {
        void accept(String path);
    }

    private interface FolderShower {
        void accept(String path, List<RemoteFiles.Entry> entries);
    }

    private final MainActivity a;
    /** Alvo do download escolhido no navegador (null = este aparelho) e o caminho do arquivo. */
    private Devices.Device pullTarget;
    private String pullPath;
    private boolean pullPending;

    RemoteBrowser(MainActivity activity) {
        a = activity;
    }

    /** Abre o navegador de pastas do aparelho alvo para escolher o arquivo a baixar. */
    void browse() {
        final Devices.Device[] t = new Devices.Device[1];
        if (!a.oneTarget("Baixar arquivo", t)) return;
        final Devices.Device target = t[0];
        final String startDir = a.prefs.getString("pullDir", "/sdcard/Download");
        a.start(MainActivity.prefix(target) + "procurar arquivo em " + startDir);
        a.background(() -> {
            Adb.Shell shell = null;
            try {
                shell = a.openTarget(target);
                String dir = startDir;
                List<RemoteFiles.Entry> entries;
                try {
                    entries = RemoteFiles.list(shell, dir);
                } catch (IOException e) {
                    dir = "/sdcard";
                    entries = RemoteFiles.list(shell, dir);
                }
                final Adb.Shell open = shell;
                final String firstDir = dir;
                final List<RemoteFiles.Entry> first = entries;
                a.finish(Status.INFO, "Escolha o arquivo a baixar.");
                a.runOnUiThread(() -> show(open, target, firstDir, first));
            } catch (AdbPairingRequiredException e) {
                if (shell != null) shell.close();
                a.pairingRequired(target != null ? target.address() : null);
            } catch (Exception e) {
                if (shell != null) shell.close();
                a.finish(Status.ERROR, "Não foi possível abrir as pastas: " + Adb.describe(e));
            }
        });
    }

    /** Lista da pasta; tocar numa pasta entra nela, tocar num arquivo escolhe onde salvar. */
    private void show(final Adb.Shell shell, final Devices.Device target, String startDir,
                      List<RemoteFiles.Entry> startEntries) {
        final String[] dir = {startDir};
        final List<Object> items = new ArrayList<>();
        final ArrayAdapter<Object> adapter = new ArrayAdapter<>(a, android.R.layout.simple_list_item_1, items);
        final android.widget.ListView listView = new android.widget.ListView(a);
        listView.setAdapter(adapter);
        final TextView empty = new TextView(a);
        empty.setPadding(a.dp(24), a.dp(12), a.dp(24), 0);
        empty.setText("Pasta vazia ou sem permissão de leitura.");
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(empty);
        box.addView(listView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (a.getResources().getDisplayMetrics().heightPixels * 0.55)));

        final String up = "⬆  .. (pasta acima)";
        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(startDir)
                .setView(box)
                .setNeutralButton("Ir para...", null)
                .setNegativeButton("Cancelar", null)
                .create();
        final FolderShower showFolder = (path, entries) -> {
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
            a.setStatus(Status.INFO, "Abrindo " + path + "...");
            a.background(() -> {
                try {
                    List<RemoteFiles.Entry> entries = RemoteFiles.list(shell, path);
                    a.runOnUiThread(() -> {
                        showFolder.accept(path, entries);
                        a.setStatus(Status.INFO, "Escolha o arquivo a baixar.");
                    });
                } catch (Exception e) {
                    a.runOnUiThread(() -> a.setStatus(Status.ERROR, Adb.describe(e)));
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
            a.prefs.edit().putString("pullDir", dir[0]).apply();
            pullTarget = target;
            pullPath = path;
            pullPending = true;
            dialog.dismiss();
            chooseWhereToSave(e.name);
        });
        // Fecha a conexão quando o navegador some (escolheu arquivo, cancelou ou tocou fora).
        dialog.setOnDismissListener(x -> a.background(shell::close));
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            final EditText field = new EditText(a);
            field.setSingleLine(true);
            field.setText(dir[0]);
            LinearLayout pad = new LinearLayout(a);
            pad.setPadding(a.dp(20), 0, a.dp(20), 0);
            pad.addView(field, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            new AlertDialog.Builder(a)
                    .setTitle("Ir para a pasta")
                    .setView(pad)
                    .setPositiveButton("Abrir", (d2, w) -> {
                        String path = field.getText().toString().trim();
                        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
                        if (!path.startsWith("/")) {
                            a.setStatus(Status.ERROR, "Informe o caminho completo (ex.: /sdcard/Download).");
                            return;
                        }
                        open.accept(path);
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        }));
        showFolder.accept(startDir, startEntries);
        dialog.show();
    }

    private void chooseWhereToSave(String name) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        if (!a.openPicker(intent, MainActivity.REQUEST_PULL_FILE)) pullPending = false;
    }

    /** Baixa o arquivo escolhido no navegador para o local escolhido no celular. */
    void pullFile(final Uri uri) {
        if (uri == null) {
            pullPending = false;
            a.setStatus(Status.INFO, "Download cancelado.");
            return;
        }
        if (!pullPending) {
            // A tela foi recriada enquanto o seletor estava aberto e perdeu o arquivo escolhido.
            DeviceLog.delete(a.getContentResolver(), uri);
            a.setStatus(Status.ERROR, "Escolha o arquivo de novo em \"Baixar arquivo\".");
            return;
        }
        pullPending = false;
        final Devices.Device target = pullTarget;
        final String path = pullPath;
        final String name = a.fileName(uri);
        a.start(MainActivity.prefix(target) + "pull " + path);
        final Adb.Progress progress = new ProgressBoard(a, "Baixando").item(name);
        a.background(() -> {
            try {
                Adb.Shell shell = a.openTarget(target);
                long size;
                try (OutputStream out = a.getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("não foi possível gravar em " + name);
                    size = shell.pull(path, out, progress);
                } finally {
                    shell.close();
                }
                a.finish(Status.SUCCESS, "Baixado: " + name + " (" + RemoteFiles.formatSize(size) + ")");
            } catch (AdbPairingRequiredException e) {
                DeviceLog.delete(a.getContentResolver(), uri);
                a.finish(Status.ERROR, "Conclua o pareamento (etapa 1).");
            } catch (Exception e) {
                DeviceLog.delete(a.getContentResolver(), uri);
                a.finish(Status.ERROR, "Falha no download: " + Adb.describe(e));
            }
        });
    }
}
