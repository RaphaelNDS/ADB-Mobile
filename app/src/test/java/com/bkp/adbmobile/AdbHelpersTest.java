package com.bkp.adbmobile;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Partes do Adb que não precisam de aparelho: aspas, acentos, erros de instalação e primeiro plano. */
public class AdbHelpersTest {

    @Test
    public void quote_protegeEspacosEAspas() {
        assertEquals("'/sdcard/Meus arquivos'", Adb.quote("/sdcard/Meus arquivos"));
        assertEquals("'/sdcard/it'\\''s'", Adb.quote("/sdcard/it's"));
        assertEquals("'$(reboot)'", Adb.quote("$(reboot)"));
    }

    @Test
    public void ascii_semAcento_ficaIgual() {
        String cmd = "settings get system eth_device_conn";
        assertSame(cmd, Adb.ascii(cmd));
    }

    @Test
    public void ascii_comAcento_viraPrintfEmOctal() {
        // ç = C3 A7, ã = C3 A3 em UTF-8.
        assertEquals("eval \"$(printf 'echo \\303\\247\\303\\243o')\"", Adb.ascii("echo ção"));
    }

    @Test
    public void ascii_escapaPorcentoBarraEAspas() {
        assertEquals("eval \"$(printf 'echo \\047100%%\\047 \\\\n \\303\\251')\"", Adb.ascii("echo '100%' \\n é"));
    }

    @Test
    public void installResult_sucesso() throws Exception {
        assertEquals("Success\n", Adb.installResult("Success\n"));
    }

    @Test
    public void installResult_assinaturaDiferente_explica() {
        try {
            Adb.installResult("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.bkp.adbmobile signatures do not match]");
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package"));
            assertTrue(e.getMessage(), e.getMessage().contains("desinstale o app no aparelho antes"));
        }
    }

    @Test
    public void installHint_outrosMotivos() {
        assertTrue(Adb.installHint("INSTALL_FAILED_VERSION_DOWNGRADE").contains("versão mais nova"));
        assertTrue(Adb.installHint("INSTALL_FAILED_INSUFFICIENT_STORAGE").contains("sem espaço"));
        assertTrue(Adb.installHint("INSTALL_FAILED_OLDER_SDK").contains("antigo demais"));
        assertTrue(Adb.installHint("INSTALL_FAILED_USER_RESTRICTED").contains("Instalar via USB"));
        assertEquals("INSTALL_FAILED_X", Adb.installHint("INSTALL_FAILED_X"));
        assertEquals("Instalação falhou sem mensagem do aparelho.", Adb.installHint(""));
    }

    @Test
    public void installResult_semColchetes_usaASaida() {
        try {
            Adb.installResult("  Error: java.lang.SecurityException  \n");
            fail();
        } catch (IOException e) {
            assertEquals("Error: java.lang.SecurityException", e.getMessage());
        }
    }

    @Test
    public void remoteSize() throws Exception {
        assertEquals(1234L, Adb.remoteSize(" 1234\r\n", "/sdcard/a"));
        try {
            Adb.remoteSize("X\n", "/sdcard/nao");
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage().endsWith("/sdcard/nao"));
        }
    }

    @Test
    public void foregroundAndPid() throws Exception {
        FakeShell shell = new FakeShell("  topResumedActivity=ActivityRecord{9 u0 com.x/.Main t46}\nPID:4321\n");
        assertArrayEquals(new String[]{"com.x", "4321"}, Adb.foregroundAndPid(shell, "com.x"));
        assertTrue(shell.commands.get(0).endsWith("echo \"PID:$(pidof -s com.x)\""));
        // App fechado e nada reconhecível em primeiro plano.
        assertArrayEquals(new String[]{"", ""}, Adb.foregroundAndPid(new FakeShell("PID:\n"), "com.x"));
    }

    @Test
    public void foregroundAndPid_pacoteInvalido_naoRoda() {
        FakeShell shell = new FakeShell();
        try {
            Adb.foregroundAndPid(shell, "com.x)\"; reboot; echo \"");
            fail();
        } catch (Exception e) {
            assertTrue(e.getMessage().startsWith("Pacote inválido"));
        }
        assertTrue(shell.commands.isEmpty());
    }

    @Test
    public void foregroundPackage() throws Exception {
        assertEquals("br.com.backupautomacao.kiosk", Adb.foregroundPackage(new FakeShell(
                "  ResumedActivity: ActivityRecord{a u0 br.com.backupautomacao.kiosk/.MainActivity t3}")));
        assertEquals("", Adb.foregroundPackage(new FakeShell("")));
    }

    @Test
    public void describe_semMensagemUsaONomeDaExcecao() {
        assertEquals("IOException", Adb.describe(new IOException()));
        assertEquals("IOException", Adb.describe(new IOException("")));
        assertEquals("Connection refused", Adb.describe(new IOException("Connection refused")));
    }

    @Test
    public void errorText_traduzOErroConhecido() {
        Exception known = new Exception("Exception occurred while executing 'set-device-owner':\n"
                + "java.lang.IllegalStateException: Not allowed to set the device owner because there are "
                + "already some accounts on the device");
        assertTrue(DeviceRunner.errorText(known), DeviceRunner.errorText(known).contains("Contas"));
        assertEquals("timeout", DeviceRunner.errorText(new Exception("timeout")));
    }
}
