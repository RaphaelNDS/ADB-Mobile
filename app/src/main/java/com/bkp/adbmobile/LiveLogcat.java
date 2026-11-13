package com.bkp.adbmobile;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.muntashirakon.adb.AdbPairingRequiredException;

/**
 * "Logcat ao vivo": janela com o logcat do aparelho alvo chegando em tempo real, a partir de
 * agora (sem o histórico, que já existe em "Gerar log"). Filtro por texto (aplicado na tela, sem
 * reconectar) e opção de mostrar só o app do Log automático (logcat --pid, decidido ao abrir).
 */
final class LiveLogcat {

    /** Linhas guardadas na memória; as mais antigas saem. */
    static final int MAX_LINES = 1000;
    /** Linhas desenhadas na tela (as últimas que passam no filtro): desenhar todas travava o tablet. */
    static final int SHOWN_LINES = 200;
    /** Intervalo entre atualizações da tela (o logcat pode mandar centenas de linhas por segundo). */
    private static final long REFRESH_MS = 500;

    private final MainActivity a;

    LiveLogcat(MainActivity activity) {
        a = activity;
    }

    /** A linha passa no filtro: contém o texto, sem diferenciar maiúsculas (filtro vazio = todas). */
    static boolean matches(String line, String filter) {
        if (filter == null || filter.trim().isEmpty()) return true;
        return line.toLowerCase(Locale.ROOT).contains(filter.trim().toLowerCase(Locale.ROOT));
    }

    /** As últimas {@code max} linhas que passam no filtro, na ordem em que chegaram. */
    static List<String> visible(Iterable<String> lines, String filter, int max) {
        ArrayDeque<String> out = new ArrayDeque<>();
        for (String l : lines) {
            if (!matches(l, filter)) continue;
            out.addLast(l);
            if (out.size() > max) out.removeFirst();
        }
        return new ArrayList<>(out);
    }

    /** Comando do logcat: só o que chegar a partir de agora; com pacote, só o processo dele. */
    static String command(String pkg) {
        String base = "logcat -v time -T 1";
        if (pkg == null || pkg.isEmpty()) return base;
        // Sem o processo rodando, avisa em vez de mostrar tudo (--pid vazio seria erro do logcat).
        return "p=$(pidof -s " + pkg + "); if [ -z \"$p\" ]; then echo \"" + pkg
                + " não está rodando. Abra o app e toque em Logcat ao vivo de novo.\"; else " + base
                + " --pid=$p; fi";
    }

    void open() {
        final Devices.Device[] t = new Devices.Device[1];
        if (!a.oneTarget("o logcat ao vivo", t)) return;
        final Devices.Device target = t[0];
        final String pkg = a.monitor.packageName();

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(16), a.dp(8), a.dp(16), 0);
        final EditText filter = new EditText(a);
        filter.setHint("Filtrar (ex.: Exception, nome do app)");
        filter.setSingleLine(true);
        box.addView(filter);
        final CheckBox onlyApp = new CheckBox(a);
        onlyApp.setText(pkg.isEmpty() ? "Só o app do Log automático (informe o pacote lá)"
                : "Só o app " + pkg + " (vale ao reabrir)");
        onlyApp.setEnabled(!pkg.isEmpty());
        onlyApp.setChecked(!pkg.isEmpty() && a.prefs.getBoolean("liveOnlyApp", false));
        box.addView(onlyApp);
        final TextView info = new TextView(a);
        info.setTextSize(12);
        info.setTextColor(a.color(R.color.text_secondary));
        box.addView(info);
        final ScrollView scroll = new ScrollView(a);
        final TextView text = new TextView(a);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(10);
        // Selecionável só com a tela pausada: texto selecionável que muda o tempo todo pesa demais.
        text.setTextIsSelectable(false);
        text.setTextColor(a.color(R.color.console_text));
        text.setBackgroundResource(R.drawable.bg_console);
        text.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
        scroll.addView(text);
        box.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (a.getResources().getDisplayMetrics().heightPixels * 0.5)));

        final ArrayDeque<String> lines = new ArrayDeque<>();
        final Adb.Stopper stopper = new Adb.Stopper();
        final boolean[] paused = {false};
        final boolean[] dirty = {true};
        // Acompanha as linhas novas (desce sozinho) até a pessoa rolar para cima para ler.
        final boolean[] follow = {true};
        scroll.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_MOVE) follow[0] = !scroll.canScrollVertically(1);
            return false;
        });
        final String[] state = {"Conectando..."};
        final Handler main = new Handler(Looper.getMainLooper());

        // Desenha a tela (só no thread da tela). Separado do ciclo abaixo: digitar no filtro
        // redesenha na hora sem criar outro ciclo.
        final Runnable render = () -> {
            if (paused[0]) return;
            String f = filter.getText().toString();
            List<String> shown;
            int total;
            synchronized (lines) {
                shown = visible(lines, f, SHOWN_LINES);
                total = lines.size();
            }
            // Volta a acompanhar quando a pessoa rola de novo até o fim.
            if (!follow[0] && !scroll.canScrollVertically(1)) follow[0] = true;
            text.setText(android.text.TextUtils.join("\n", shown));
            // scrollTo, não fullScroll: o fullScroll move o foco e tirava o cursor do filtro.
            if (follow[0]) scroll.post(() -> scroll.scrollTo(0, Math.max(0, text.getHeight() - scroll.getHeight())));
            info.setText(state[0] + "  •  " + (f.trim().isEmpty() ? "últimas " + shown.size() + " linhas"
                    : shown.size() + " linha(s) com o filtro, entre as " + total + " guardadas"));
            dirty[0] = false;
        };
        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                if (dirty[0]) render.run();
                if (!stopper.isStopped()) main.postDelayed(this, REFRESH_MS);
            }
        };
        filter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                dirty[0] = true;
                render.run();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        onlyApp.setOnCheckedChangeListener((b, checked) -> a.prefs.edit().putBoolean("liveOnlyApp", checked).apply());

        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("Logcat ao vivo" + (target != null ? " - " + target.label() : ""))
                .setView(box)
                .setPositiveButton("Fechar", null)
                .setNeutralButton("Pausar", null)
                .setNegativeButton("Limpar", null)
                .create();
        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                paused[0] = !paused[0];
                ((TextView) v).setText(paused[0] ? "Continuar" : "Pausar");
                info.setText(paused[0] ? "Pausado: dá para selecionar e copiar (as linhas novas continuam chegando)"
                        : state[0]);
                text.setTextIsSelectable(paused[0]);
                dirty[0] = true;
                render.run();
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                synchronized (lines) {
                    lines.clear();
                }
                dirty[0] = true;
                render.run();
            });
        });
        dialog.setOnDismissListener(x -> {
            main.removeCallbacks(refresh);
            // Parar encerra o logcat no aparelho (um comando pelo ADB): fora do thread da tela.
            a.background(stopper::stop);
        });
        dialog.show();
        main.post(refresh);

        final String cmd = command(onlyApp.isChecked() ? pkg : null);
        a.append(MainActivity.prefix(target) + "Logcat ao vivo aberto" + (onlyApp.isChecked() ? " (só " + pkg + ")" : ""));
        a.background(() -> {
            String end;
            try {
                Adb.Shell shell = a.openTarget(target);
                try {
                    state[0] = "Recebendo";
                    dirty[0] = true;
                    shell.follow(cmd, new LineSplitter(line -> {
                        synchronized (lines) {
                            lines.addLast(line);
                            while (lines.size() > MAX_LINES) lines.removeFirst();
                        }
                        dirty[0] = true;
                    }), stopper);
                } finally {
                    shell.close();
                }
                end = stopper.isStopped() ? null : "O logcat terminou.";
            } catch (AdbPairingRequiredException e) {
                end = MainActivity.NEEDS_PAIRING;
            } catch (Exception e) {
                end = "Falha: " + Adb.describe(e);
            }
            if (end != null) {
                final String message = end;
                a.log(MainActivity.prefix(target) + "Logcat ao vivo: " + message);
                main.post(() -> {
                    state[0] = message;
                    dirty[0] = true;
                    render.run();
                    info.setText(message);
                });
            }
        });
    }

    /** Recebe a saída em bytes e entrega linha a linha (texto UTF-8, sem o \r\n). */
    static final class LineSplitter extends OutputStream {
        interface Lines {
            void line(String line);
        }

        private final Lines out;
        private final ByteArrayOutputStream current = new ByteArrayOutputStream();

        LineSplitter(Lines out) {
            this.out = out;
        }

        @Override
        public void write(int b) {
            if (b == '\n') {
                byte[] bytes = current.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') len--;
                out.line(new String(bytes, 0, len, StandardCharsets.UTF_8));
                current.reset();
            } else {
                current.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            for (int i = off; i < off + len; i++) write(b[i]);
        }

        /** Para os testes: as linhas de um texto. */
        static List<String> split(String text) {
            final List<String> list = new ArrayList<>();
            LineSplitter s = new LineSplitter(list::add);
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            s.write(bytes, 0, bytes.length);
            return list;
        }
    }
}
