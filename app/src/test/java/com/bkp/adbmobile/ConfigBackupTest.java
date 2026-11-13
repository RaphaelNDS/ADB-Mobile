package com.bkp.adbmobile;

import org.json.JSONException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Exportar/importar configuração. */
public class ConfigBackupTest {

    @Test
    public void exportaEImporta_mesmoConteudo() throws JSONException {
        String json = ConfigBackup.toJson(
                Collections.singletonList(AdbCommands.saved("Bateria", "dumpsys battery", true)),
                Collections.singletonList(new Devices.Device("Zebra", "172.16.47.62", 37021, true)),
                "br.com.app");
        ConfigBackup.Data d = ConfigBackup.fromJson(json);
        assertEquals(1, d.commands.size());
        assertEquals("Bateria", d.commands.get(0).label);
        assertEquals("dumpsys battery", d.commands.get(0).command);
        assertTrue(d.commands.get(0).confirm);
        assertTrue(d.commands.get(0).userSaved);
        assertEquals("172.16.47.62", d.devices.get(0).host);
        assertEquals(37021, d.devices.get(0).port);
        assertEquals("Zebra", d.devices.get(0).name);
        assertFalse(d.devices.get(0).selected); // importados entram desmarcados
        assertEquals("br.com.app", d.monitorPackage);
    }

    @Test
    public void arquivoDeOutroApp_erroExplicado() {
        try {
            ConfigBackup.fromJson("{\"nome\":\"x\"}");
            fail();
        } catch (JSONException e) {
            assertTrue(e.getMessage().contains("não é uma configuração"));
        }
    }

    @Test
    public void itensInvalidos_saoIgnorados() throws JSONException {
        ConfigBackup.Data d = ConfigBackup.fromJson("{\"formato\":\"adb-mobile-config\",\"versao\":1,"
                + "\"comandos\":[{\"nome\":\"\",\"comando\":\"ls\"},{\"nome\":\"ok\",\"comando\":\"ls\"}],"
                + "\"aparelhos\":[{\"ip\":\"1.2.3.4\",\"porta\":0},{\"ip\":\"1.2.3.5\",\"porta\":5555}]}");
        assertEquals(1, d.commands.size());
        assertEquals(1, d.devices.size());
        assertEquals("", d.monitorPackage);
    }

    @Test
    public void juntarAparelhos_novosEAtualizados() {
        List<Devices.Device> current = new ArrayList<>(Arrays.asList(
                new Devices.Device("Tablet", "172.16.238.171", 40209, true),
                new Devices.Device("", "172.16.1.1", 5555, false)));
        List<Devices.Device> incoming = Arrays.asList(
                new Devices.Device("", "172.16.238.171", 40209, false),       // igual: nada muda
                new Devices.Device("Quiosque", "172.16.1.1", 37000, false),  // porta e nome novos
                new Devices.Device("Zebra", "172.16.47.62", 37021, false));  // novo
        assertArrayEquals(new int[]{1, 1}, ConfigBackup.mergeDevices(current, incoming));
        assertEquals(3, current.size());
        assertEquals("Tablet", current.get(0).name);
        assertTrue(current.get(0).selected);
        assertEquals(37000, current.get(1).port);
        assertEquals("Quiosque", current.get(1).name);
        assertEquals("Zebra", current.get(2).name);
    }
}
