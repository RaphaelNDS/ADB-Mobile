package com.bkp.adbmobile;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Inventário: leitura da saída do comando e o CSV para o Excel. */
public class InventoryTest {

    @Test
    public void aparelhoComKiosk() {
        Map<String, String> r = Inventory.parse("modelo=samsung SM-T225\r\nandroid=14 (API 34)\r\n"
                + "bateria=87% (carregando)\r\nlivre_kb=20971520\r\nkiosk=5.2.3\r\n"
                + "owner=br.com.backupautomacao.kiosk/br.com.backupautomacao.kiosk.admin.AdminReceiver\r\n"
                + "frente=br.com.backupautomacao.kiosk\r\n");
        assertEquals("samsung SM-T225", r.get("modelo"));
        assertEquals("14 (API 34)", r.get("android"));
        assertEquals("87% (carregando)", r.get("bateria"));
        assertEquals("20 GB", r.get("livre"));
        assertEquals("5.2.3", r.get("kiosk"));
        assertEquals("Kiosk", r.get("owner"));
        assertEquals("br.com.backupautomacao.kiosk", r.get("frente"));
    }

    @Test
    public void semKioskSemOwner_eCamposVazios() {
        Map<String, String> r = Inventory.parse("modelo=Zebra MC3300x\nbateria=%\nlivre_kb=1572864\nkiosk=\nowner=nenhum\nfrente=\n");
        assertEquals("não instalado", r.get("kiosk"));
        assertEquals("nenhum", r.get("owner"));
        assertEquals("", r.get("bateria")); // dumpsys battery sem nível
        assertEquals("1.5 GB", r.get("livre"));
        assertEquals("", r.get("android"));
    }

    @Test
    public void outroDeviceOwner_mostraOComponente() {
        assertEquals("com.outro/.Admin", Inventory.parse("owner=com.outro/.Admin\n").get("owner"));
    }

    @Test
    public void csv_cabecalhoSeparadorEAspas() {
        List<Map<String, String>> rows = new ArrayList<>();
        Map<String, String> a = new LinkedHashMap<>();
        a.put("aparelho", "Caixa 1");
        a.put("modelo", "Zebra; MC3300x");
        a.put("kiosk", "5.2.3");
        rows.add(a);
        Map<String, String> b = new LinkedHashMap<>();
        b.put("aparelho", "10.0.0.9");
        b.put("erro", "Tempo esgotado \"conexão\"");
        rows.add(b);
        String csv = Inventory.csv(rows);
        String[] lines = csv.substring(1).split("\r\n");
        assertEquals(0xFEFF, csv.charAt(0)); // BOM para o Excel
        assertEquals("Aparelho;Modelo;Android;Bateria;Espaço livre;Kiosk;Device Owner;Em primeiro plano;Erro", lines[0]);
        assertEquals("Caixa 1;\"Zebra; MC3300x\";;;;5.2.3;;;", lines[1]);
        assertEquals("10.0.0.9;;;;;;;;\"Tempo esgotado \"\"conexão\"\"\"", lines[2]);
    }
}
