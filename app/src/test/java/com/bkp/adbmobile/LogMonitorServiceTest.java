package com.bkp.adbmobile;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Texto do Registro quando o log automático vai ser salvo. */
public class LogMonitorServiceTest {

    @Test
    public void appParadoEAberto_aberto() {
        // Testado no tablet: Kiosk parado e aberto aparecia como "reiniciou (PID - → 12434)".
        assertEquals("aberto", LogMonitorService.captureReason(true, "", "12434"));
    }

    @Test
    public void appJaRodandoEmSegundoPlanoVemParaAFrente_aberto() {
        assertEquals("aberto", LogMonitorService.captureReason(true, "12434", "12434"));
    }

    @Test
    public void pidMudouComOAppRodando_reiniciou() {
        assertEquals("reiniciou (PID 12434 → 12683)", LogMonitorService.captureReason(false, "12434", "12683"));
        // Mesmo que tenha vindo para a frente junto, o que importa é a troca de PID.
        assertEquals("reiniciou (PID 12434 → 12683)", LogMonitorService.captureReason(true, "12434", "12683"));
    }

    @Test
    public void processoVoltouSemMudarATela_iniciouDeNovo() {
        // Travou e reabriu sozinho, com um ciclo sem processo no meio e sem sair da tela.
        assertEquals("iniciou de novo (PID 12683)", LogMonitorService.captureReason(false, "", "12683"));
    }
}
