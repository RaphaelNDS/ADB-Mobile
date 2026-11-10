package com.bkp.adbmobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Navegação nas pastas do aparelho e captura de tela, pelo shell do ADB. */
public final class RemoteFiles {

    /** Item de uma pasta. {@code size} é -1 quando o aparelho não informa. */
    public static final class Entry {
        public final String name;
        public final boolean dir;
        public final long size;

        Entry(String name, boolean dir, long size) {
            this.name = name;
            this.dir = dir;
            this.size = size;
        }

        @Override
        public String toString() {
            return dir ? "📁  " + name : "📄  " + name + (size >= 0 ? "   (" + formatSize(size) + ")" : "");
        }
    }

    /** Pastas primeiro, depois arquivos, cada grupo por nome. Lança erro se a pasta não abrir. */
    public static List<Entry> list(Adb.Shell shell, String dir) throws Exception {
        String q = Adb.quote(dir);
        // "stat -L" segue links (ex.: /sdcard). Aparelhos sem stat (Android 5) usam o laço com "test".
        String out = shell.run("if cd " + q + " 2>/dev/null; then echo __OK__; "
                + "if stat -c %s / >/dev/null 2>&1; then stat -L -c '%F|%s|%n' * .* 2>/dev/null; "
                + "else for f in * .*; do if [ -d \"$f\" ]; then echo \"directory|0|$f\"; "
                + "elif [ -e \"$f\" ]; then echo \"file|-1|$f\"; fi; done; fi; else echo __ERR__; fi");
        if (!out.contains("__OK__")) throw new IOException("Não foi possível abrir a pasta " + dir);
        Map<String, Entry> byName = new LinkedHashMap<>();
        for (String line : out.split("\n")) {
            String[] p = line.split("\\|", 3);
            if (p.length < 3) continue;
            String name = p[2].replace("\r", "");
            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/")) continue;
            long size;
            try {
                size = Long.parseLong(p[1].trim());
            } catch (NumberFormatException e) {
                size = -1;
            }
            boolean isDir = p[0].trim().equals("directory");
            byName.put(name, new Entry(name, isDir, isDir ? -1 : size));
        }
        List<Entry> list = new ArrayList<>(byName.values());
        Collections.sort(list, (a, b) -> {
            if (a.dir != b.dir) return a.dir ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return list;
    }

    /** Junta pasta e nome ("/" + "sdcard" = "/sdcard"). */
    public static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    /** Pasta acima ("/sdcard/Download" -> "/sdcard"); "/" fica "/". */
    public static String parent(String dir) {
        int i = dir.lastIndexOf('/');
        return i <= 0 ? "/" : dir.substring(0, i);
    }

    private static final String SHOT = "/data/local/tmp/adbmobile-tela.png";

    /** Captura a tela do aparelho e devolve o PNG. */
    public static byte[] screenshot(Adb.Shell shell) throws Exception {
        String out = shell.run("screencap -p " + SHOT + " 2>&1 && echo __OK__");
        if (!out.contains("__OK__")) {
            throw new IOException("O aparelho não capturou a tela" + (out.trim().isEmpty() ? "." : ": " + out.trim()));
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            shell.pull(SHOT, png, null);
        } finally {
            try {
                shell.run("rm -f " + SHOT);
            } catch (Exception ignored) {
            }
        }
        return png.toByteArray();
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.getDefault(), "%.2f GB", bytes / 1073741824.0);
    }

    private RemoteFiles() {
    }
}
