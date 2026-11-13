package com.bkp.adbmobile;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** "Preparar Kiosk": instalar, definir o Device Owner só quando precisa, e abrir. */
public class KioskSetupTest {

    private static final File APK = new File("kiosk.apk");
    private static final String KIOSK = "br.com.backupautomacao.kiosk/br.com.backupautomacao.kiosk.admin.AdminReceiver";

    private static boolean ran(FakeShell s, String prefix) {
        for (String c : s.commands) if (c.startsWith(prefix)) return true;
        return false;
    }

    @Test
    public void aparelhoNovo_instalaDefineEAbre() throws Exception {
        FakeShell s = new FakeShell("nenhum\n", "Success: Device owner set to package br.com.backupautomacao.kiosk\n",
                KIOSK + "\n", "");
        assertEquals("instalado, Device Owner definido e aberto", KioskSetup.setup(s, APK, null));
        assertEquals("install kiosk.apk", s.commands.get(0));
        assertTrue(ran(s, "dpm set-device-owner " + KIOSK));
        assertTrue(ran(s, "monkey -p br.com.backupautomacao.kiosk"));
    }

    @Test
    public void jaEraDeviceOwner_soAtualizaEAbre() throws Exception {
        FakeShell s = new FakeShell(KIOSK + "\n", "");
        assertEquals("instalado (já era Device Owner) e aberto", KioskSetup.setup(s, APK, null));
        assertFalse(ran(s, "dpm"));
        assertTrue(ran(s, "monkey"));
    }

    @Test
    public void outroDeviceOwner_paraSemMexer() {
        FakeShell s = new FakeShell("com.outro/.Admin\n");
        try {
            KioskSetup.setup(s, APK, null);
            fail();
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("com.outro/.Admin"));
        }
        assertFalse(ran(s, "dpm"));
        assertFalse(ran(s, "monkey"));
    }

    @Test
    public void aparelhoComContas_explicaENaoAbre() {
        FakeShell s = new FakeShell("nenhum\n", "Exception occurred while executing 'set-device-owner':\n"
                + "java.lang.IllegalStateException: Not allowed to set the device owner because there are "
                + "already some accounts on the device\n");
        try {
            KioskSetup.setup(s, APK, null);
            fail();
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("contas"));
        }
        assertFalse(ran(s, "monkey"));
    }

    @Test
    public void dpmSemErroMasNadaMudou_avisa() {
        FakeShell s = new FakeShell("nenhum\n", "\n", "nenhum\n");
        try {
            KioskSetup.setup(s, APK, null);
            fail();
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("O Device Owner não foi definido"));
        }
    }
}
