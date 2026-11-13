package com.bkp.adbmobile;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Logcat ao vivo: filtro, comando e quebra em linhas. */
public class LiveLogcatTest {

    @Test
    public void filtro_vazioMostraTudo_eIgnoraMaiusculas() {
        assertTrue(LiveLogcat.matches("E/AndroidRuntime: FATAL", ""));
        assertTrue(LiveLogcat.matches("E/AndroidRuntime: FATAL", "fatal"));
        assertFalse(LiveLogcat.matches("I/ActivityManager: Start", "fatal"));
    }

    @Test
    public void comando_semPacote_logcatAPartirDeAgora() {
        assertEquals("logcat -v time -T 1", LiveLogcat.command(""));
    }

    @Test
    public void comando_comPacote_filtraPeloProcesso() {
        String cmd = LiveLogcat.command("br.com.app");
        assertTrue(cmd.contains("pidof -s br.com.app"));
        assertTrue(cmd.contains("logcat -v time -T 1 --pid=$p"));
    }

    @Test
    public void linhas_juntaPedacosETiraOCR() {
        assertEquals(Arrays.asList("linha 1", "ação 2"), LiveLogcat.LineSplitter.split("linha 1\r\nação 2\nincompleta"));
    }

    @Test
    public void visiveis_ultimasQuePassamNoFiltro() {
        assertEquals(Arrays.asList("E b", "E c"),
                LiveLogcat.visible(Arrays.asList("E a", "I x", "E b", "I y", "E c"), "e ", 2));
        assertEquals(Arrays.asList("I y", "E c"),
                LiveLogcat.visible(Arrays.asList("E a", "I x", "E b", "I y", "E c"), "", 2));
    }
}
