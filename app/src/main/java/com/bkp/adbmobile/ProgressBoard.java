package com.bkp.adbmobile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mostra no status a porcentagem de envios/downloads ("Enviando: Quiosque 1 45% · Quiosque 2 80%"),
 * sem redesenhar a tela a cada bloco. Criar no thread da tela, depois de start(); o
 * {@link MainActivity#finish} encerra o painel para não sobrescrever a mensagem final.
 */
final class ProgressBoard {

    private final MainActivity a;
    private final String verb;
    private final Map<String, long[]> items = new LinkedHashMap<>();
    private long lastPost;

    ProgressBoard(MainActivity activity, String verb) {
        a = activity;
        this.verb = verb;
        a.activeBoard = this;
    }

    Adb.Progress item(final String name) {
        synchronized (this) {
            items.put(name, new long[]{0, 0});
        }
        return (done, total) -> update(name, done, total);
    }

    /** Registra todos os aparelhos antes de começar (para a lista sair completa); chave = endereço. */
    Map<String, Adb.Progress> items(List<Devices.Device> targets) {
        Map<String, Adb.Progress> map = new LinkedHashMap<>();
        for (Devices.Device d : targets) map.put(d.address(), item(d.label()));
        return map;
    }

    private void update(String name, long done, long total) {
        final String text;
        synchronized (this) {
            long[] v = items.get(name);
            if (v == null) return;
            v[0] = done;
            v[1] = total;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastPost < 300 && done < total) return;
            lastPost = now;
            text = render();
        }
        a.runOnUiThread(() -> {
            if (a.activeBoard == this) a.setStatus(MainActivity.Status.INFO, text);
        });
    }

    private String render() {
        if (items.size() == 1) {
            long[] v = items.values().iterator().next();
            return verb + "... " + percent(v) + "% (" + RemoteFiles.formatSize(v[0]) + " de "
                    + RemoteFiles.formatSize(v[1]) + ")";
        }
        if (items.size() > 4) {
            int sum = 0, done = 0;
            for (long[] v : items.values()) {
                sum += percent(v);
                if (v[1] > 0 && v[0] >= v[1]) done++;
            }
            return verb + " para " + items.size() + " aparelhos: " + (sum / items.size()) + "% ("
                    + done + " concluídos)";
        }
        StringBuilder b = new StringBuilder(verb).append(":");
        String sep = " ";
        for (Map.Entry<String, long[]> e : items.entrySet()) {
            b.append(sep).append(e.getKey()).append(' ').append(percent(e.getValue())).append('%');
            sep = " · ";
        }
        return b.toString();
    }

    private static int percent(long[] v) {
        return v[1] <= 0 ? 0 : (int) (v[0] * 100 / v[1]);
    }
}
