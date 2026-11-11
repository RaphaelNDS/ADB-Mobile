package com.bkp.adbmobile;

import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.bkp.adbmobile.MainActivity.Status;

/** "Escolher app" e "Informações do app": lista, detalhes e ações nos apps do aparelho alvo. */
final class AppScreens {

    private final MainActivity a;

    AppScreens(MainActivity activity) {
        a = activity;
    }

    /** Nome do app neste aparelho, ou null (no remoto o ADB só informa o pacote). */
    private String localLabel(String pkg) {
        try {
            PackageManager pm = a.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Busca os apps instalados no aparelho alvo e abre a lista para escolher um. */
    void chooseApp() {
        final boolean local = !a.isRemote();
        a.withTargetShell("Escolher app", "listar apps instalados", "Não foi possível listar os apps: ", shell -> {
            List<AppInfo.App> apps = AppInfo.list(shell);
            if (local) {
                for (AppInfo.App app : apps) {
                    String label = localLabel(app.pkg);
                    if (label != null) app.label = label;
                }
                AppInfo.sort(apps);
            }
            int user = 0;
            for (AppInfo.App app : apps) if (!app.system) user++;
            a.finish(Status.INFO, apps.size() + " apps encontrados (" + user + " instalados pelo usuário).");
            a.runOnUiThread(() -> showAppPicker(apps));
        });
    }

    private void showAppPicker(final List<AppInfo.App> apps) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(20), a.dp(8), a.dp(20), 0);

        final EditText search = new EditText(a);
        search.setSingleLine(true);
        search.setHint("Buscar por nome ou pacote");
        box.addView(search);

        final CheckBox showSystem = new CheckBox(a);
        showSystem.setText("Mostrar apps do sistema");
        box.addView(showSystem);

        final ListView listView = new ListView(a);
        final List<AppInfo.App> shown = new ArrayList<>();
        final ArrayAdapter<AppInfo.App> adapter = new ArrayAdapter<>(a, android.R.layout.simple_list_item_1, shown);
        listView.setAdapter(adapter);
        box.addView(listView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (a.getResources().getDisplayMetrics().heightPixels * 0.5)));

        final Runnable filter = () -> {
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (AppInfo.App app : apps) {
                if (app.system && !showSystem.isChecked() && !app.foreground) continue;
                if (!q.isEmpty() && !app.pkg.toLowerCase(Locale.ROOT).contains(q)
                        && (app.label == null || !app.label.toLowerCase(Locale.ROOT).contains(q))) continue;
                shown.add(app);
            }
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter.run();
            }
        });
        showSystem.setOnCheckedChangeListener((b, checked) -> filter.run());
        filter.run();

        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("Escolher app")
                .setView(box)
                .setNegativeButton("Fechar", null)
                .show();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            showAppInfo(shown.get(position).pkg);
        });
    }

    /** Lê e mostra as informações do pacote no aparelho alvo. */
    void showAppInfo(final String pkg) {
        if (pkg.isEmpty()) {
            a.setStatus(Status.ERROR, "Informe o pacote ou toque em \"Escolher app\".");
            return;
        }
        if (!AppInfo.validPackage(pkg)) {
            a.setStatus(Status.ERROR, "Pacote inválido: " + pkg);
            return;
        }
        final boolean local = !a.isRemote();
        a.withTargetShell("Informações do app", "informações de " + pkg, "Não foi possível ler o app: ", shell -> {
            AppInfo.Details d = AppInfo.details(shell, pkg);
            String label = local ? localLabel(pkg) : null;
            final String title = label != null ? label : pkg;
            a.finish(Status.INFO, "Informações de " + pkg + " carregadas.");
            a.runOnUiThread(() -> showAppDetails(title, d));
        });
    }

    private void showAppDetails(String title, final AppInfo.Details d) {
        TextView text = new TextView(a);
        text.setText(d.text);
        text.setTextIsSelectable(true);
        text.setTextSize(13);
        text.setTextColor(a.color(R.color.text_primary));
        text.setLineSpacing(2 * a.getResources().getDisplayMetrics().density, 1);
        text.setPadding(a.dp(20), a.dp(8), a.dp(20), a.dp(8));
        ScrollView scroll = new ScrollView(a);
        scroll.addView(text);

        boolean current = d.pkg.equals(a.monitor.packageName());
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(scroll)
                .setNeutralButton("Ações", (dlg, w) -> showAppActions(title, d))
                .setNegativeButton("Fechar", null);
        if (!current) b.setPositiveButton("Usar este pacote", (dlg, w) -> a.monitor.usePackage(d.pkg));
        b.show();
    }

    /** Menu de ações do app (as que fazem sentido no estado atual) e a saída completa do dumpsys. */
    private void showAppActions(final String title, final AppInfo.Details d) {
        final List<AppInfo.Action> list = AppInfo.actions(d);
        String[] labels = new String[list.size() + 1];
        for (int i = 0; i < list.size(); i++) labels[i] = list.get(i).label;
        labels[list.size()] = "Ver saída completa (dumpsys)";
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setItems(labels, (dlg, which) -> {
                    if (which == list.size()) {
                        a.append("dumpsys package " + d.pkg + ":");
                        a.logOutput("", d.dumpsys);
                        a.setStatus(Status.INFO, "Saída completa do dumpsys no registro abaixo.");
                        return;
                    }
                    AppInfo.Action action = list.get(which);
                    if (!action.confirm) {
                        runAppAction(title, d, action);
                        return;
                    }
                    String warning = action == AppInfo.Action.CLEAR
                            ? "Apaga todos os dados do app (configurações, login, banco de dados). Não dá para desfazer."
                            : action == AppInfo.Action.UNINSTALL ? "Remove o app e todos os dados dele do aparelho."
                            : "O app some da tela e para de abrir até ser ativado de novo.";
                    new AlertDialog.Builder(a)
                            .setTitle(action.label + "?")
                            .setMessage(title + "\n" + d.pkg + "\n\n" + warning)
                            .setPositiveButton(action.label, (x, y) -> runAppAction(title, d, action))
                            .setNegativeButton("Cancelar", (x, y) -> showAppDetails(title, d))
                            .show();
                })
                .setNegativeButton("Voltar", (dlg, w) -> showAppDetails(title, d))
                .show();
    }

    /** Executa a ação no aparelho alvo e reabre as informações já atualizadas. */
    private void runAppAction(final String title, final AppInfo.Details d, final AppInfo.Action action) {
        a.withTargetShell("Ações do app", action.label.toLowerCase(Locale.ROOT) + " " + d.pkg,
                action.label + " falhou: ", shell -> {
                    String message = AppInfo.run(shell, d, action);
                    if (action == AppInfo.Action.UNINSTALL) {
                        a.finish(Status.SUCCESS, message);
                        return;
                    }
                    // "am start" e "force-stop" voltam antes do app terminar de abrir/fechar.
                    if (action == AppInfo.Action.OPEN || action == AppInfo.Action.STOP) Thread.sleep(1500);
                    AppInfo.Details updated = AppInfo.details(shell, d.pkg);
                    a.finish(Status.SUCCESS, message);
                    a.runOnUiThread(() -> showAppDetails(title, updated));
                });
    }
}
