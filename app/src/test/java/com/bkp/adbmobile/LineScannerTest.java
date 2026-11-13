package com.bkp.adbmobile;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Contagem de linhas, crashes e ANRs enquanto o log é gravado (Gerar log e Log automático). */
public class LineScannerTest {

    private static final String CRASH = "11-13 14:00:00.100  123  123 E AndroidRuntime: Process: com.x, PID: 123\n";
    private static final String NATIVE = "11-13 14:00:01.200  456  456 F DEBUG   : pid: 456, tid: 456, name: x  >>> com.x <<<\n";
    private static final String ANR = "11-13 14:00:02.300  789  800 E ActivityManager: ANR in com.x (com.x/.Main)\n";

    private static DeviceLog.Result scan(String pkg, String since, String text, int chunk) throws Exception {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DeviceLog.LineScanner s = new DeviceLog.LineScanner(file, pkg, since);
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        // Chega em pedaços, como pelo ADB: a linha pode vir partida.
        for (int i = 0; i < b.length; i += chunk) s.write(b, i, Math.min(chunk, b.length - i));
        s.flushLine();
        s.close();
        assertEquals(text, file.toString("UTF-8")); // o arquivo recebe tudo, sem mudar nada
        return s.result;
    }

    @Test
    public void contaCrashJavaNativoEAnr() throws Exception {
        DeviceLog.Result r = scan("com.x", null, "--------- beginning of main\n" + CRASH + NATIVE + ANR, 7);
        assertEquals(4, r.lines);
        assertEquals(2, r.crashes);
        assertEquals(1, r.anrs);
        assertEquals("11-13 14:00:02.300", r.lastTime);
        assertEquals("2 crashes e 1 ANR", r.problems());
    }

    @Test
    public void outroPacoteComMesmoComeco_naoConta() throws Exception {
        String text = "11-13 14:00:00.100  1  1 E AndroidRuntime: Process: com.xy, PID: 1\n"
                + "11-13 14:00:00.200  1  1 E ActivityManager: ANR in com.x.debug (com.x.debug/.A)\n"
                + "11-13 14:00:00.300  1  1 F DEBUG: >>> com.x.debug <<<\n";
        DeviceLog.Result r = scan("com.x", null, text, 1000);
        assertEquals(0, r.crashes);
        assertEquals(0, r.anrs);
        assertNull(r.problems());
    }

    @Test
    public void anrNoFimDaLinha_conta() throws Exception {
        assertEquals(1, scan("com.x", null, "11-13 14:00:00.100  1  1 E ActivityManager: ANR in com.x\n", 3).anrs);
    }

    @Test
    public void since_ignoraOQueJaFoiContado() throws Exception {
        // Log automático: só conta o que veio depois do último log salvo.
        String old = "11-13 13:59:59.999  1  1 E AndroidRuntime: Process: com.x, PID: 1\n";
        String sameTime = "11-13 14:00:00.100  1  1 E AndroidRuntime: Process: com.x, PID: 1\n";
        String noTime = "AndroidRuntime: Process: com.x, PID: 1\n";
        DeviceLog.Result r = scan("com.x", "11-13 14:00:00.100", old + sameTime + noTime + ANR, 5);
        assertEquals(0, r.crashes);
        assertEquals(1, r.anrs);
    }

    @Test
    public void semPacote_soContaLinhas() throws Exception {
        DeviceLog.Result r = scan(null, null, CRASH + ANR + "ultima sem quebra", 4);
        assertEquals(2, r.lines); // a última linha sem \n não conta como linha
        assertEquals(0, r.crashes);
        assertEquals("11-13 14:00:02.300", r.lastTime);
    }

    @Test
    public void linhaMuitoLonga_naoQuebra() throws Exception {
        StringBuilder big = new StringBuilder("11-13 14:00:00.100  1  1 I x: ");
        for (int i = 0; i < 5000; i++) big.append('a');
        DeviceLog.Result r = scan("com.x", null, big + "\n" + ANR, 512);
        assertEquals(2, r.lines);
        assertEquals(1, r.anrs);
    }

    @Test
    public void cabecalhoNaoEntraNaContagem() throws Exception {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DeviceLog.LineScanner s = new DeviceLog.LineScanner(file, "com.x", null);
        s.writeRaw(("Alvo: com.x\n" + CRASH).getBytes(StandardCharsets.UTF_8));
        s.flushLine();
        assertEquals(0, s.result.lines);
        assertEquals(0, s.result.crashes);
        assertTrue(file.toString("UTF-8").startsWith("Alvo: com.x\n"));
    }

    @Test
    public void flagEnomes() {
        assertTrue(DeviceLog.isFlagged("log_com.x_20261113_140000_CRASH.txt"));
        assertTrue(DeviceLog.isFlagged("log_com.x_20261113_140000_ANR.txt"));
        assertEquals(false, DeviceLog.isFlagged("log_com.x_20261113_140000.txt"));
        String name = DeviceLog.fileName("172.16.238.171:40209");
        assertTrue(name, name.matches("log_172\\.16\\.238\\.171_40209_\\d{8}_\\d{6}\\.txt"));
    }
}
