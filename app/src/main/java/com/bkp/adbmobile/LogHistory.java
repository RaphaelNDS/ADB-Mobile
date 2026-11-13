package com.bkp.adbmobile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Histórico do Registro de atividades entre aberturas do app: o texto fica num arquivo interno
 * do app (registro.txt), com as linhas mais recentes até {@link #MAX_CHARS}.
 */
final class LogHistory {

    /** Tamanho guardado (~200 KB); o começo (mais antigo) é cortado numa quebra de linha. */
    static final int MAX_CHARS = 100_000;

    private final File file;

    LogHistory(File dir) {
        file = new File(dir, "registro.txt");
    }

    /** O histórico salvo, ou "" se não há (ou não deu para ler: o Registro só começa vazio). */
    String load() {
        if (!file.exists()) return "";
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Grava o texto (já cortado); false se falhar. Uma gravação por vez (todas as telas usam o
     * mesmo arquivo temporário).
     */
    boolean save(String text) {
        synchronized (LogHistory.class) {
            return write(text);
        }
    }

    private boolean write(String text) {
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(trim(text, MAX_CHARS).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        // Troca de uma vez: o app fechar no meio não deixa o histórico pela metade.
        return tmp.renameTo(file) || (file.delete() && tmp.renameTo(file));
    }

    void clear() {
        // Espera uma gravação em andamento, senão ela recriaria o arquivo depois do "Limpar".
        synchronized (LogHistory.class) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /** As últimas {@code max} letras de {@code text}, começando numa linha inteira. */
    static String trim(String text, int max) {
        if (text.length() <= max) return text;
        int start = text.length() - max;
        int nl = text.indexOf('\n', start);
        return nl < 0 ? text.substring(start) : text.substring(nl + 1);
    }

    /** Linhas que contêm {@code query} (sem diferenciar maiúsculas), na ordem. */
    static List<String> search(String text, String query) {
        List<String> found = new ArrayList<>();
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return found;
        for (String line : text.split("\n")) {
            if (line.toLowerCase(Locale.ROOT).contains(q)) found.add(line);
        }
        return found;
    }
}
