package com.bkp.adbmobile;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Aparelhos salvos: porta, migração das versões antigas, gravação e atualização da porta. */
public class DevicesTest {

    @Test
    public void parsePort_aceitaSo1a65535() {
        assertEquals(5555, Devices.parsePort("5555"));
        assertEquals(40209, Devices.parsePort("  40209 "));
        assertEquals(1, Devices.parsePort("1"));
        assertEquals(65535, Devices.parsePort("65535"));
        assertEquals(-1, Devices.parsePort("0"));
        assertEquals(-1, Devices.parsePort("65536"));
        assertEquals(-1, Devices.parsePort("-5"));
        assertEquals(-1, Devices.parsePort(""));
        assertEquals(-1, Devices.parsePort("abc"));
        assertEquals(-1, Devices.parsePort("55 55"));
    }

    @Test
    public void primeiraVez_transformaOIpAntigoNoPrimeiroAparelho() {
        MemoryPrefs prefs = new MemoryPrefs();
        prefs.edit().putString("host", " 172.16.47.62 ").putString("port", "37021").apply();
        List<Devices.Device> list = Devices.load(prefs);
        assertEquals(1, list.size());
        assertEquals("172.16.47.62", list.get(0).host);
        assertEquals(37021, list.get(0).port);
        assertTrue(list.get(0).selected);
        // Já fica salvo no formato novo.
        assertTrue(prefs.getString("devices", "").contains("172.16.47.62"));
    }

    @Test
    public void primeiraVez_semIpAntigoOuComPortaInvalida_listaVazia() {
        assertTrue(Devices.load(new MemoryPrefs()).isEmpty());
        MemoryPrefs prefs = new MemoryPrefs();
        prefs.edit().putString("host", "10.0.0.1").putString("port", "x").apply();
        assertTrue(Devices.load(prefs).isEmpty());
    }

    @Test
    public void salvaELe_iguais() {
        MemoryPrefs prefs = new MemoryPrefs();
        Devices.save(prefs, Arrays.asList(
                new Devices.Device("Zebra \"MC3300x\"", "172.16.47.62", 37021, true),
                new Devices.Device("", "10.0.0.2", 5555, false)));
        List<Devices.Device> list = Devices.load(prefs);
        assertEquals(2, list.size());
        assertEquals("Zebra \"MC3300x\"", list.get(0).name);
        assertEquals("172.16.47.62:37021", list.get(0).address());
        assertTrue(list.get(0).selected);
        assertEquals("10.0.0.2", list.get(1).host);
        assertFalse(list.get(1).selected);
    }

    @Test
    public void listaCorrompida_comecaVazia() {
        MemoryPrefs prefs = new MemoryPrefs();
        prefs.edit().putString("devices", "[{quebrado").apply();
        assertTrue(Devices.load(prefs).isEmpty());
    }

    @Test
    public void updatePort_trocaSoODaqueleIp() {
        MemoryPrefs prefs = new MemoryPrefs();
        Devices.save(prefs, Arrays.asList(
                new Devices.Device("A", "10.0.0.1", 1111, true),
                new Devices.Device("B", "10.0.0.2", 2222, true)));
        Devices.updatePort(prefs, "10.0.0.2", 3333);
        Devices.updatePort(prefs, "10.9.9.9", 4444); // não salvo: nada muda
        List<Devices.Device> list = Devices.load(prefs);
        assertEquals(1111, list.get(0).port);
        assertEquals(3333, list.get(1).port);
        assertEquals(2, list.size());
    }

    @Test
    public void find_eSelected() {
        Devices.Device a = new Devices.Device("A", "10.0.0.1", 1, true);
        Devices.Device b = new Devices.Device("B", "10.0.0.2", 2, false);
        List<Devices.Device> list = Arrays.asList(a, b);
        assertSame(b, Devices.find(list, "10.0.0.2"));
        assertNull(Devices.find(list, "10.0.0.3"));
        assertEquals(Arrays.asList(a), Devices.selected(list));
    }

    @Test
    public void label_semNomeUsaOIp() {
        assertEquals("Tablet", new Devices.Device("Tablet", "10.0.0.1", 1, false).label());
        assertEquals("10.0.0.1", new Devices.Device("", "10.0.0.1", 1, false).label());
        assertEquals("10.0.0.1", new Devices.Device(null, "10.0.0.1", 1, false).label());
    }

    @Test
    public void rotuloDoRegistro_umOuVariosAparelhos() {
        Devices.Device a = new Devices.Device("Tablet", "10.0.0.1", 1, true);
        Devices.Device b = new Devices.Device("", "10.0.0.2", 2, true);
        assertEquals("[Tablet]", DevicesPanel.label(Arrays.asList(a)));
        assertEquals("[2 aparelhos]", DevicesPanel.label(Arrays.asList(a, b)));
    }
}
