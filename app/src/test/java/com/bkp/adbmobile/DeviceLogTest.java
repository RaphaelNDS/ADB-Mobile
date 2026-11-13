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
}
