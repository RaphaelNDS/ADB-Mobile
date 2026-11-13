package com.bkp.adbmobile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Lista de apps e informações de um pacote, a partir das saídas do pm/dumpsys. */
public class AppInfoTest {

    private static final String SEP = "__ADB_MOBILE_SEP__";

    private Locale before;

    @Before
    public void setUp() {
        before = Locale.getDefault();
        Locale.setDefault(Locale.US);
    }

    @After
    public void tearDown() {
        Locale.setDefault(before);
    }

    // ---------- list() ----------

    @Test
    public void list_primeiroPlanoDepoisUsuarioDepoisSistema() throws Exception {
        FakeShell shell = new FakeShell("package:com.b\r\npackage:com.a\n" + SEP
                + "\npackage:android\npackage:com.a\n" + SEP
                + "\n  topResumedActivity=ActivityRecord{1 u0 com.b/.Main t4}\n");
        List<AppInfo.App> apps = AppInfo.list(shell);
        assertEquals(3, apps.size()); // com.a aparece nas duas listas: fica só como do usuário
        assertEquals("com.b", apps.get(0).pkg);
        assertTrue(apps.get(0).foreground);
        assertEquals("com.a", apps.get(1).pkg);
        assertFalse(apps.get(1).system);
        assertEquals("android", apps.get(2).pkg);
        assertTrue(apps.get(2).system);
        assertEquals("▶ com.b", apps.get(0).toString());
        assertEquals("android  (sistema)", apps.get(2).toString());
    }

    @Test
    public void list_respostaEstranha_lancaErro() {
        try {
            AppInfo.list(new FakeShell("sh: pm: not found"));
            fail();
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Resposta inesperada"));
        }
    }

    @Test
    public void sort_usaONomeQuandoTem() {
        AppInfo.App a = new AppInfo.App("com.z", false);
        a.label = "Agenda";
        AppInfo.App b = new AppInfo.App("com.a", false);
        b.label = "Zebra";
        List<AppInfo.App> apps = Arrays.asList(b, a);
        AppInfo.sort(apps);
        assertEquals("com.z", apps.get(0).pkg);
        assertEquals("Agenda\ncom.z", apps.get(0).toString());
    }

    // ---------- details() ----------

    private static String dump(String pkg, String flags, String user0, String installer) {
        return "Packages:\n"
                + "  Package [" + pkg + "] (4f2a1b):\n"
                + "    userId=10123\n"
                + "    versionCode=33 minSdk=21 targetSdk=34\n"
                + "    versionName=2.31\n"
                + "    dataDir=/data/user/0/" + pkg + "\n"
                + "    pkgFlags=[ " + flags + " ]\n"
                + "    firstInstallTime=2026-11-13 14:00:00\n"
                + "    lastUpdateTime=2026-11-13 14:30:00\n"
                + "    installerPackageName=" + installer + "\n"
                + "    install permissions:\n"
                + "      android.permission.INTERNET: granted=true\n"
                + "    User 0: ceDataInode=1 installed=true " + user0 + "\n"
                + "      runtime permissions:\n"
                + "        android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SET ]\n"
                + "        com.x.permission.PROPRIA: granted=true\n"
                + "\n"
                + "Queries:\n"
                + "  Package [outro.app] (1):\n"
                + "      android.permission.CAMERA: granted=true\n";
    }

    private static String reply(String paths, String pid, String launcher, String kb, String dump) {
        return paths + "\n" + SEP + "\n" + pid + "\n" + SEP + "\n" + launcher + "\n" + SEP + "\n" + kb + "\n"
                + SEP + "\n" + dump;
    }

    @Test
    public void details_appDoUsuarioRodando() throws Exception {
        String out = reply("package:/data/app/~~x/com.x-1/base.apk", "1234 99", "com.x/.Main", "2048",
                dump("com.x", "HAS_CODE ALLOW_CLEAR_USER_DATA",
                        "hidden=false suspended=false stopped=false enabled=0", "null"));
        AppInfo.Details d = AppInfo.details(new FakeShell(out), "com.x");
        String t = d.text;
        assertTrue(t, t.contains("Versão: 2.31 (código 33)\n"));
        assertTrue(t, t.contains("Android mínimo: Android 5.0 (API 21)\n"));
        assertTrue(t, t.contains("Android alvo: Android 14 (API 34)\n"));
        assertTrue(t, t.contains("Tipo: Instalado pelo usuário\n"));
        assertTrue(t, t.contains("Situação: ativado\n"));
        assertTrue(t, t.contains("Em execução: sim (PID 1234)\n"));
        assertTrue(t, t.contains("Tela inicial: com.x/.Main\n"));
        assertTrue(t, t.contains("Instalado por: ADB / desconhecido\n"));
        assertTrue(t, t.contains("UID: 10123\n"));
        assertTrue(t, t.contains("Tamanho do APK: 2.0 MB\n"));
        assertTrue(t, t.contains("APK: /data/app/~~x/com.x-1/base.apk\n"));
        assertTrue(t, t.contains("Permissões concedidas (2)\n  • INTERNET\n  • com.x.permission.PROPRIA"));
        assertTrue(t, t.contains("Permissões negadas (1)\n  • POST_NOTIFICATIONS"));
        // A permissão de outro pacote do dumpsys não entra.
        assertFalse(t, t.contains("CAMERA"));
        assertFalse(d.system);
        assertTrue(d.enabled);
        assertTrue(d.running);
        assertEquals("com.x/.Main", d.launcher);
        assertEquals(Arrays.asList(AppInfo.Action.OPEN, AppInfo.Action.STOP, AppInfo.Action.DISABLE,
                AppInfo.Action.CLEAR, AppInfo.Action.UNINSTALL), AppInfo.actions(d));
    }

    @Test
    public void details_appDoSistemaDesativadoEParado() throws Exception {
        String out = reply("package:/system/app/X/X.apk", "", "No activity found", "0",
                dump("com.sys", "SYSTEM HAS_CODE UPDATED_SYSTEM_APP",
                        "hidden=true suspended=false stopped=true enabled=3", "com.android.vending"));
        AppInfo.Details d = AppInfo.details(new FakeShell(out), "com.sys");
        String t = d.text;
        assertTrue(t, t.contains("Tipo: App do sistema (atualizado)\n"));
        assertTrue(t, t.contains("Situação: desativado pelo usuário, parado à força, oculto\n"));
        assertTrue(t, t.contains("Em execução: não\n"));
        assertTrue(t, t.contains("Tela inicial: nenhuma (sem ícone)\n"));
        assertTrue(t, t.contains("Instalado por: com.android.vending\n"));
        assertFalse(t, t.contains("Tamanho do APK")); // 0 KB = desconhecido
        assertTrue(d.system);
        assertFalse(d.enabled);
        assertFalse(d.running);
        assertNull(d.launcher);
        // Desativado: não abre; do sistema: não desinstala.
        assertEquals(Arrays.asList(AppInfo.Action.ENABLE, AppInfo.Action.CLEAR), AppInfo.actions(d));
    }

    @Test
    public void details_naoInstalado() {
        try {
            AppInfo.details(new FakeShell("\n"), "com.nao.existe");
            fail();
        } catch (Exception e) {
            assertEquals("com.nao.existe não está instalado neste aparelho.", e.getMessage());
        }
    }

    @Test
    public void details_pacoteInvalido_naoRodaNada() {
        FakeShell shell = new FakeShell();
        try {
            AppInfo.details(shell, "com.x; reboot");
            fail();
        } catch (Exception e) {
            assertTrue(e.getMessage().startsWith("Pacote inválido"));
        }
        assertTrue(shell.commands.isEmpty());
    }

    // ---------- run() ----------

    private static AppInfo.Details app(String pkg) {
        AppInfo.Details d = new AppInfo.Details(pkg, "", "");
        d.launcher = pkg + "/.Main";
        return d;
    }

    @Test
    public void run_sucessoEFalha() throws Exception {
        FakeShell ok = new FakeShell("Package com.x new state: disabled-user");
        assertEquals("Desativado: com.x", AppInfo.run(ok, app("com.x"), AppInfo.Action.DISABLE));
        assertEquals("pm disable-user --user 0 com.x 2>&1", ok.commands.get(0));
        try {
            AppInfo.run(new FakeShell("Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]\n"), app("com.x"),
                    AppInfo.Action.UNINSTALL);
            fail();
        } catch (Exception e) {
            assertEquals("Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]", e.getMessage());
        }
    }

    @Test
    public void run_abrirComErro() {
        try {
            AppInfo.run(new FakeShell("Starting: Intent\nError: Activity class does not exist."), app("com.x"),
                    AppInfo.Action.OPEN);
            fail();
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Activity class does not exist"));
        }
    }

    @Test
    public void validPackage() {
        assertTrue(AppInfo.validPackage("br.com.backupautomacao.kiosk"));
        assertTrue(AppInfo.validPackage("com.a_b.C1"));
        assertFalse(AppInfo.validPackage("com.x; rm -rf /"));
        assertFalse(AppInfo.validPackage("com.x$(id)"));
        assertFalse(AppInfo.validPackage(""));
    }
}
