package com.bkp.adbmobile;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Quais logs automáticos saem da pasta principal (vão para "antigos"). */
public class DeviceLogTest {

    private static final String KIOSK = DeviceLog.filePrefix("com.bkp.kiosk");

    private static List<String> logs(int count, String prefix) {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= count; i++) names.add(prefix + String.format("20261105_1300%02d.txt", i));
        return names;
    }

    @Test
    public void ateOLimite_nadaSai() {
        assertTrue(DeviceLog.toArchive(logs(10, KIOSK), KIOSK, 10).isEmpty());
    }

    @Test
    public void acimaDoLimite_saemOsMaisAntigos() {
        List<String> names = logs(12, KIOSK);
        Collections.reverse(names); // a ordem do provedor não importa
        assertEquals(Arrays.asList(KIOSK + "20261105_130001.txt", KIOSK + "20261105_130002.txt"),
                DeviceLog.toArchive(names, KIOSK, 10));
    }

    @Test
    public void crashEAnr_nuncaSaemENaoContam() {
        List<String> names = logs(10, KIOSK);
        names.add(KIOSK + "20261101_080000_CRASH.txt");
        names.add(KIOSK + "20261101_090000_ANR.txt");
        assertTrue(DeviceLog.toArchive(names, KIOSK, 10).isEmpty());

        names.add(KIOSK + "20261106_100000.txt");
        assertEquals(Collections.singletonList(KIOSK + "20261105_130001.txt"),
                DeviceLog.toArchive(names, KIOSK, 10));
    }

    @Test
    public void logsDeOutroAppOuManuais_naoSaoMexidos() {
        List<String> names = logs(10, KIOSK);
        names.addAll(logs(5, DeviceLog.filePrefix("172.16.209.67"))); // "Gerar log" manual
        names.addAll(logs(5, DeviceLog.filePrefix("com.outro.app")));
        names.add("anotacoes.txt");
        names.add(DeviceLog.ARCHIVE_FOLDER);
        assertTrue(DeviceLog.toArchive(names, KIOSK, 10).isEmpty());
    }

    @Test
    public void resumo_nadaAMover_vazio() {
        assertEquals("", new DeviceLog.Archive().summary());
    }

    @Test
    public void resumo_movidos() {
        DeviceLog.Archive a = new DeviceLog.Archive();
        a.moved = 2;
        assertEquals("2 antigo(s) movido(s) para a pasta \"antigos\"", a.summary());
    }

    @Test
    public void resumo_naoMovidos_mostraOMotivo() {
        DeviceLog.Archive a = new DeviceLog.Archive();
        a.moved = 1;
        a.notMoved = 2;
        a.problem = "a pasta não permitiu mover";
        assertEquals("1 antigo(s) movido(s) para a pasta \"antigos\"; 2 antigo(s) NÃO movido(s) para "
                + "\"antigos\": a pasta não permitiu mover (continuam na pasta)", a.summary());
    }

    @Test
    public void resumo_falhaAoListar_avisa() {
        DeviceLog.Archive a = new DeviceLog.Archive();
        a.problem = "não foi possível listar a pasta dos logs";
        assertEquals("não deu para conferir os logs antigos: não foi possível listar a pasta dos logs", a.summary());
    }
}
