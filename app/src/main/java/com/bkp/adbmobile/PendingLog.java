package com.bkp.adbmobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Linhas do Registro geradas com a tela fechada (ex.: comando agendado). Ficam num arquivo e
 * entram no Registro quando a tela abre; com a tela aberta, entram na hora.
 */
final class PendingLog {

    /** A tela aberta: recebe o aviso de que há linhas novas (no thread principal). */
    static volatile Runnable listener;

    private PendingLog() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "registro-pendente.txt");
    }

    static void add(Context c, String line) {
        synchronized (PendingLog.class) {
            try (OutputStream out = new FileOutputStream(file(c), true)) {
                out.write((line.replace("\n", "\n    ") + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Sem espaço/arquivo: a notificação do agendamento ainda mostra o resultado.
            }
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            Runnable l = listener;
            if (l != null) l.run();
        });
    }

    /** As linhas guardadas (e apaga o arquivo). */
    static List<String> drain(Context c) {
        synchronized (PendingLog.class) {
            File f = file(c);
            List<String> lines = new ArrayList<>();
            if (!f.exists()) return lines;
            try (InputStream in = new FileInputStream(f)) {
                byte[] all = new byte[(int) f.length()];
                int off = 0, n;
                while (off < all.length && (n = in.read(all, off, all.length - off)) > 0) off += n;
                for (String line : new String(all, 0, off, StandardCharsets.UTF_8).split("\n")) {
                    if (line.isEmpty()) continue;
                    // Continuação (saída de várias linhas) volta para a entrada anterior.
                    if (line.startsWith("    ") && !lines.isEmpty()) {
                        lines.set(lines.size() - 1, lines.get(lines.size() - 1) + "\n" + line.substring(4));
                    } else {
                        lines.add(line);
                    }
                }
            } catch (IOException ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return lines;
        }
    }
}
