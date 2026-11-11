package com.bkp.adbmobile;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SavedCommandsTest {

    private MemoryPrefs prefs;
    private SavedCommands saved;

    @Before
    public void setUp() {
        prefs = new MemoryPrefs();
        saved = new SavedCommands(prefs);
    }

    @Test
    public void semNadaSalvo_listaVazia() {
        assertTrue(saved.load().isEmpty());
        assertFalse(saved.contains("x"));
    }

    @Test
    public void salva_naOrdemEmQueForamCriados() {
        assertFalse(saved.put("Modelo", "getprop ro.product.model", false));
        assertFalse(saved.put("Reiniciar", "reboot", true));

        List<AdbCommands.Preset> list = saved.load();
        assertEquals(2, list.size());
        assertEquals("Modelo", list.get(0).label);
        assertEquals("getprop ro.product.model", list.get(0).command);
        assertFalse(list.get(0).confirm);
        assertEquals("Reiniciar", list.get(1).label);
        assertTrue(list.get(1).confirm);
        for (AdbCommands.Preset p : list) {
            assertTrue(p.userSaved);
            assertFalse(p.isHeader());
            assertEquals("meu:" + p.label, p.key());
        }
    }

    @Test
    public void mesmoNome_substituiNoMesmoLugar() {
        saved.put("A", "um", false);
        saved.put("B", "dois", false);
        assertTrue(saved.put("A", "três", true));

        List<AdbCommands.Preset> list = saved.load();
        assertEquals(2, list.size());
        assertEquals("A", list.get(0).label);
        assertEquals("três", list.get(0).command);
        assertTrue(list.get(0).confirm);
        assertEquals("B", list.get(1).label);
    }

    @Test
    public void exclui() {
        saved.put("A", "um", false);
        saved.put("B", "dois", false);
        saved.remove("A");

        List<AdbCommands.Preset> list = saved.load();
        assertEquals(1, list.size());
        assertEquals("B", list.get(0).label);
        assertFalse(saved.contains("A"));
    }

    @Test
    public void excluirQuemNaoExiste_naoMudaNada() {
        saved.put("A", "um", false);
        saved.remove("Z");
        assertEquals(1, saved.load().size());
    }

    @Test
    public void textoComAspasEAcentos_voltaIgual() {
        String cmd = "echo \"Versão: $(getprop ro.build.version.release)\"; echo 'ok'";
        saved.put("Versão do Android", cmd, false);
        assertEquals(cmd, saved.load().get(0).command);
        // Outra instância lendo as mesmas preferências (como ao reabrir o app).
        assertEquals("Versão do Android", new SavedCommands(prefs).load().get(0).label);
    }

    @Test
    public void listaCorrompida_comecaVaziaSemQuebrar() {
        prefs.edit().putString("lista", "{isto não é json").apply();
        assertTrue(saved.load().isEmpty());
    }

    @Test
    public void itemAntigoSemConfirmar_ehFalse() {
        prefs.edit().putString("lista", "[{\"nome\":\"A\",\"comando\":\"date\"}]").apply();
        assertFalse(saved.load().get(0).confirm);
    }
}
