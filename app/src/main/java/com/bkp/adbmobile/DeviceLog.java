package com.bkp.adbmobile;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Grava o logcat de um aparelho como .txt (com cabeçalho de identificação). O log é escrito no
 * arquivo conforme chega: em alguns aparelhos ele passa de dezenas de MB e não cabe na memória.
 */
final class DeviceLog {

    private DeviceLog() {
    }

    /**
     * @param target descrição do alvo para o cabeçalho (ex.: "172.16.47.62:36965")
     * @param extra  linha extra no cabeçalho (ex.: app que disparou a captura), ou null
     * @return número de linhas do logcat gravadas
     */
    static long save(Adb.Shell shell, ContentResolver resolver, Uri uri, String target, String extra)
            throws Exception {
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
        LineCounter body = new LineCounter(new BufferedOutputStream(file, 65536));
        try {
            body.write(header.getBytes("UTF-8"));
            body.lines = 0; // conta só as linhas do logcat
            shell.stream(Adb.LOG_COMMAND, body);
        } finally {
            body.close();
        }
        return body.lines;
    }

    /** Conta as quebras de linha que passam por ele. */
    private static final class LineCounter extends FilterOutputStream {
        long lines;

        LineCounter(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            if (b == '\n') lines++;
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            for (int i = off; i < off + len; i++) if (b[i] == '\n') lines++;
            out.write(b, off, len);
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
        return "log_" + part.replaceAll("[^A-Za-z0-9.-]", "_") + "_" + stamp + ".txt";
    }
}
