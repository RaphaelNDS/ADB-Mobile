package com.bkp.adbmobile;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** follow(): a primeira linha (PID do shell) sai da saída e serve para encerrar o comando. */
public class AdbFollowTest {

    private static String feed(Adb.PidFilter f, String... parts) throws Exception {
        for (String p : parts) {
            byte[] b = p.getBytes(StandardCharsets.UTF_8);
            f.write(b, 0, b.length);
        }
        return null;
    }

    @Test
    public void pidSeparadoDaSaida_mesmoChegandoEmPedacos() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Adb.PidFilter f = new Adb.PidFilter(out);
        feed(f, "__ADBM_", "PID__4321\n--------- beginning", " of main\nlinha\n");
        assertEquals("4321", f.pid);
        assertEquals("--------- beginning of main\nlinha\n", out.toString("UTF-8"));
    }

    @Test
    public void semLinhaDoPid_naoPerdeASaida() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Adb.PidFilter f = new Adb.PidFilter(out);
        feed(f, "erro: algo\noutra\n");
        assertNull(f.pid);
        assertEquals("erro: algo\noutra\n", out.toString("UTF-8"));
    }

    @Test
    public void comandos() {
        assertEquals("echo __ADBM_PID__$$; logcat", Adb.followCommand("logcat"));
        assertEquals("pkill -TERM -P 12 2>/dev/null; kill -TERM 12 2>/dev/null; true", Adb.killCommand("12"));
    }
}
