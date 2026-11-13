package com.bkp.adbmobile;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Grava o logcat de um aparelho como .txt (com cabeçalho de identificação). O log é escrito no
 * arquivo conforme chega: em alguns aparelhos ele passa de dezenas de MB e não cabe na memória.
 */
final class DeviceLog {

    private DeviceLog() {
    }

    /** O que foi gravado e o que foi encontrado no log. */
    static final class Result {
        long lines;
        int crashes;
        int anrs;
        /** Data/hora da última linha do log ("MM-dd HH:mm:ss.SSS"), ou null. */
        String lastTime;

        /** "1 crash e 1 ANR", ou null se não houve nenhum. */
        String problems() {
            List<String> parts = new ArrayList<>();
            if (crashes > 0) parts.add(crashes + (crashes == 1 ? " crash" : " crashes"));
            if (anrs > 0) parts.add(anrs + (anrs == 1 ? " ANR" : " ANRs"));
            if (parts.isEmpty()) return null;
            return parts.size() == 1 ? parts.get(0) : parts.get(0) + " e " + parts.get(1);
        }
    }

    /** Log manual: grava tudo, sem procurar crashes. */
    static Result save(Adb.Shell shell, ContentResolver resolver, Uri uri, String target, String extra)
            throws Exception {
        return save(shell, resolver, uri, target, extra, null, null);
    }

    /**
     * @param target   descrição do alvo para o cabeçalho (ex.: "172.16.47.62:36965")
     * @param extra    linha extra no cabeçalho (ex.: app que disparou a captura), ou null
     * @param watchPkg pacote cujos crashes/ANRs devem ser contados, ou null para não procurar
     * @param since    só conta crashes de linhas posteriores a esta data/hora (a última linha do
     *                 log anterior), para não acusar de novo um crash já registrado; null = todos
     */
    static Result save(Adb.Shell shell, ContentResolver resolver, Uri uri, String target, String extra,
                       String watchPkg, String since) throws Exception {
        String info = shell.run(Adb.DEVICE_INFO_COMMAND);
        String header = "ADB Mobile - log do aparelho\n"
                + "Gerado em: " + new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                .format(new Date()) + "\n"
                + "Alvo: " + target + "\n"
                + (extra != null ? extra + "\n" : "")
                + info + "\n"
                + "Comando: " + Adb.LOG_COMMAND + "\n"
                + "----------------------------------------\n";

        OutputStream file = resolver.openOutputStream(uri, "wt");
        if (file == null) throw new IOException("Não foi possível abrir o arquivo.");
        LineScanner body = new LineScanner(new BufferedOutputStream(file, 65536), watchPkg, since);
        try {
            body.writeRaw(header.getBytes("UTF-8")); // o cabeçalho não entra na contagem nem na busca
            shell.stream(Adb.LOG_COMMAND, body);
            body.flushLine();
        } finally {
            body.close();
        }
        return body.result;
    }

    /** Repassa os bytes ao arquivo e, linha a linha, conta linhas e procura crashes/ANRs. */
    private static final class LineScanner extends FilterOutputStream {
        private static final int MAX_LINE = 2048; // o resto de uma linha muito longa é ignorado na busca
        final Result result = new Result();
        private final String crash;   // Java: "AndroidRuntime: Process: <pkg>, PID: ..."
        private final String nativeCrash; // nativo: "pid: N, tid: N, name: ...  >>> <pkg> <<<"
        private final String anr;     // "ANR in <pkg> (...)"
        private final String since;
        private final byte[] line = new byte[MAX_LINE];
        private int len;

        LineScanner(OutputStream out, String pkg, String since) {
            super(out);
            this.crash = pkg == null ? null : "AndroidRuntime: Process: " + pkg + ",";
            this.nativeCrash = pkg == null ? null : ">>> " + pkg + " <<<";
            this.anr = pkg == null ? null : "ANR in " + pkg;
            this.since = since;
        }

        /** Grava direto no arquivo, sem contar nem procurar. */
        void writeRaw(byte[] b) throws IOException {
            out.write(b);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            take((byte) b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            for (int i = off; i < off + len; i++) take(b[i]);
        }

        private void take(byte b) {
            if (b == '\n') {
                result.lines++;
                flushLine();
            } else if (len < MAX_LINE) {
                line[len++] = b;
            }
        }

        void flushLine() {
            if (len == 0) return;
            // Pacote e marcadores são ASCII: ISO-8859-1 decodifica 1 byte = 1 caractere, sem custo extra.
            String text = new String(line, 0, len, StandardCharsets.ISO_8859_1);
            len = 0;
            String time = timeOf(text);
            if (time != null) result.lastTime = time;
            if (crash == null || (since != null && (time == null || time.compareTo(since) <= 0))) return;
            if (text.contains(crash) || text.contains(nativeCrash)) {
                result.crashes++;
            } else {
                int at = text.indexOf(anr);
                int after = at + anr.length();
                // "ANR in pkg (" ou fim de linha; evita pegar "pkg.debug".
                if (at >= 0 && (after == text.length() || text.charAt(after) == ' ' || text.charAt(after) == '(')) {
                    result.anrs++;
                }
            }
        }

        /** "11-05 13:06:26.481  8431 ..." → "11-05 13:06:26.481"; null se a linha não começa com data. */
        private static String timeOf(String text) {
            if (text.length() < 18 || text.charAt(2) != '-' || text.charAt(5) != ' '
                    || text.charAt(8) != ':' || text.charAt(14) != '.') return null;
            return text.substring(0, 18);
        }
    }

    /** Renomeia um arquivo criado na pasta dos logs; false se o provedor não permitir. */
    static boolean rename(ContentResolver resolver, Uri uri, String name) {
        try {
            return DocumentsContract.renameDocument(resolver, uri, name) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Cria um novo .txt dentro de uma pasta escolhida com ACTION_OPEN_DOCUMENT_TREE. */
    static Uri createInFolder(ContentResolver resolver, Uri tree, String name) throws IOException {
        Uri folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri file;
        try {
            file = DocumentsContract.createDocument(resolver, folder, "text/plain", name);
        } catch (Exception e) {
            throw new IOException("Sem acesso à pasta dos logs. Escolha a pasta novamente.", e);
        }
        if (file == null) throw new IOException("Não foi possível criar " + name + " na pasta dos logs.");
        return file;
    }

    /** Subpasta (dentro da pasta dos logs) para onde vão os logs automáticos mais antigos. */
    static final String ARCHIVE_FOLDER = "antigos";

    /** Log marcado com crash ou ANR no nome: nunca sai da pasta principal sozinho. */
    static boolean isFlagged(String name) {
        return name.endsWith("_CRASH.txt") || name.endsWith("_ANR.txt");
    }

    /**
     * Quais logs de {@code prefix} saem da pasta principal para manter só os {@code keep} mais
     * recentes. Os marcados com CRASH/ANR não contam nem saem. O nome tem data/hora, então a
     * ordem alfabética é a cronológica. Devolve os nomes, do mais antigo para o mais novo.
     */
    static List<String> toArchive(List<String> names, String prefix, int keep) {
        List<String> logs = new ArrayList<>();
        for (String name : names) {
            if (name != null && name.startsWith(prefix) && name.endsWith(".txt") && !isFlagged(name)) {
                logs.add(name);
            }
        }
        Collections.sort(logs);
        if (logs.size() <= keep) return new ArrayList<>();
        return new ArrayList<>(logs.subList(0, logs.size() - keep));
    }

    /** Resultado da arrumação: quantos foram movidos e, se algum ficou, por quê. */
    static final class Archive {
        int moved;
        /** Logs que deveriam ir para "antigos" mas continuam na pasta principal. */
        int notMoved;
        /** Motivo de não ter movido (o primeiro que aconteceu), ou null. */
        String problem;

        /** Trecho para a mensagem do log salvo; "" se não houve nada a mover. */
        String summary() {
            if (moved == 0 && notMoved == 0) {
                return problem != null ? "não deu para conferir os logs antigos: " + problem : "";
            }
            String text = "";
            if (moved > 0) text = moved + " antigo(s) movido(s) para a pasta \"" + ARCHIVE_FOLDER + "\"";
            if (notMoved > 0) {
                if (!text.isEmpty()) text += "; ";
                text += notMoved + " antigo(s) NÃO movido(s) para \"" + ARCHIVE_FOLDER + "\""
                        + (problem != null ? ": " + problem : "") + " (continuam na pasta)";
            }
            return text;
        }

        private Archive fail(int count, String why) {
            notMoved += count;
            if (problem == null) problem = why;
            return this;
        }
    }

    /**
     * Mantém na pasta só os {@code keep} logs mais recentes de {@code prefix} (sem contar os de
     * CRASH/ANR) e MOVE os mais antigos para a subpasta {@link #ARCHIVE_FOLDER}. Nada é apagado:
     * se não der para mover (Android 6 ou anterior, ou pasta que não permite), o arquivo fica onde
     * está e o motivo volta em {@link Archive#problem}.
     */
    static Archive archiveOld(ContentResolver resolver, Uri tree, String prefix, int keep) {
        Archive result = new Archive();
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
        List<String[]> children = listChildren(resolver, tree, rootId); // {nome, documentId, mime}
        if (children == null) return result.fail(0, "não foi possível listar a pasta dos logs");

        List<String> names = new ArrayList<>();
        for (String[] child : children) names.add(child[0]);
        List<String> old = toArchive(names, prefix, keep);
        if (old.isEmpty()) return result;
        // moveDocument não existe antes do Android 7.
        if (Build.VERSION.SDK_INT < 24) return result.fail(old.size(), "o Android deste aparelho não permite mover arquivos");

        Uri archive = null;
        for (String[] child : children) {
            if (ARCHIVE_FOLDER.equals(child[0]) && DocumentsContract.Document.MIME_TYPE_DIR.equals(child[2])) {
                archive = DocumentsContract.buildDocumentUriUsingTree(tree, child[1]);
            }
        }
        try {
            if (archive == null) {
                archive = DocumentsContract.createDocument(resolver, root,
                        DocumentsContract.Document.MIME_TYPE_DIR, ARCHIVE_FOLDER);
            }
        } catch (Exception e) {
            return result.fail(old.size(), "não foi possível criar a subpasta (" + Adb.describe(e) + ")");
        }
        if (archive == null) return result.fail(old.size(), "não foi possível criar a subpasta");

        for (String[] child : children) {
            if (!old.contains(child[0])) continue;
            try {
                if (DocumentsContract.moveDocument(resolver,
                        DocumentsContract.buildDocumentUriUsingTree(tree, child[1]), root, archive) != null) {
                    result.moved++;
                } else {
                    result.fail(1, "a pasta não permitiu mover");
                }
            } catch (Exception e) {
                // Fica na pasta principal; melhor sobrar arquivo do que perder log.
                result.fail(1, Adb.describe(e));
            }
        }
        return result;
    }

    /** Arquivos e pastas dentro de {@code parentId}: {nome, documentId, mime}; null se falhar. */
    private static List<String[]> listChildren(ContentResolver resolver, Uri tree, String parentId) {
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        List<String[]> list = new ArrayList<>();
        Cursor c = null;
        try {
            c = resolver.query(uri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            while (c != null && c.moveToNext()) {
                list.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.close();
        }
        return list;
    }

    /** Prefixo dos arquivos de log de {@code part} (ver fileName). */
    static String filePrefix(String part) {
        return "log_" + part.replaceAll("[^A-Za-z0-9.-]", "_") + "_";
    }

    /** Remove um arquivo que ficou incompleto. */
    static void delete(ContentResolver resolver, Uri uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri);
        } catch (Exception ignored) {
        }
    }

    /** Nome de arquivo seguro: log_<parte>_<data>.txt */
    static String fileName(String part) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return filePrefix(part) + stamp + ".txt";
    }
}
