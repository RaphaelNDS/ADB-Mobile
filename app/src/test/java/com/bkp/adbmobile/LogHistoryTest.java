package com.bkp.adbmobile;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Histórico do Registro: corte do tamanho e busca. */
public class LogHistoryTest {

    @Test
    public void trim_textoCurto_ficaIgual() {
        assertEquals("a\nb", LogHistory.trim("a\nb", 10));
    }

    @Test
    public void trim_cortaOComecoNumaLinhaInteira() {
        // Últimas 8 letras = "ha2\nlinha3" cortado; começa na linha seguinte inteira.
        assertEquals("linha3", LogHistory.trim("linha1\nlinha2\nlinha3", 8));
    }

    @Test
    public void search_semDiferenciarMaiusculas() {
        String log = "10:00:00  OK: Executado\n10:00:01  ERRO: Falha\n10:00:02  erro de novo";
        assertEquals(Arrays.asList("10:00:01  ERRO: Falha", "10:00:02  erro de novo"), LogHistory.search(log, "Erro"));
    }

    @Test
    public void search_vazio_nadaEncontrado() {
        assertTrue(LogHistory.search("a\nb", "  ").isEmpty());
    }
}
