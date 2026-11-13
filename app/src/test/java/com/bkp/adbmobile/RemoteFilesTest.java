package com.bkp.adbmobile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Pastas do aparelho: leitura da listagem, caminhos e tamanhos. */
public class RemoteFilesTest {

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

    @Test
    public void list_pastasPrimeiro_cadaGrupoPorNome() throws Exception {
        FakeShell shell = new FakeShell("__OK__\r\n"
                + "regular file|2048|b.txt\r\n"
                + "directory|4096|Download\r\n"
                + "regular file|10|A.png\r\n"
                + "directory|4096|.\r\n"
                + "directory|4096|..\r\n"
                + "directory|4096|documents\r\n"
                + "linha sem formato\r\n");
        List<RemoteFiles.Entry> list = RemoteFiles.list(shell, "/sdcard");
        assertEquals(4, list.size());
        assertEquals("documents", list.get(0).name);
        assertTrue(list.get(0).dir);
        assertEquals(-1, list.get(0).size); // pasta não tem tamanho
        assertEquals("Download", list.get(1).name);
        assertEquals("A.png", list.get(2).name);
        assertEquals(10, list.get(2).size);
        assertEquals("b.txt", list.get(3).name);
        assertFalse(list.get(3).dir);
    }

    @Test
    public void list_androidSemStat_tamanhoDesconhecido() throws Exception {
        List<RemoteFiles.Entry> list = RemoteFiles.list(new FakeShell("__OK__\nfile|-1|a.log\nfile|x|b.log\n"), "/");
        assertEquals(-1, list.get(0).size);
        assertEquals(-1, list.get(1).size);
        assertEquals("📄  a.log", list.get(0).toString());
    }

    @Test
    public void list_nomeComBarraOuBarraVertical() throws Exception {
        // O nome pode ter "|" (o split só corta as duas primeiras); com "/" é lixo do "* .*".
        List<RemoteFiles.Entry> list = RemoteFiles.list(new FakeShell("__OK__\nregular file|5|a|b.txt\nfile|1|x/y\n"), "/");
        assertEquals(1, list.size());
        assertEquals("a|b.txt", list.get(0).name);
    }

    @Test
    public void list_pastaQueNaoAbre_lancaErro() {
        try {
            RemoteFiles.list(new FakeShell("__ERR__\n"), "/data/nao");
            fail();
        } catch (Exception e) {
            assertTrue(e instanceof IOException);
            assertTrue(e.getMessage(), e.getMessage().contains("/data/nao"));
        }
    }

    @Test
    public void list_caminhoComAspasVaiProtegido() throws Exception {
        FakeShell shell = new FakeShell("__OK__\n");
        RemoteFiles.list(shell, "/sdcard/it's");
        assertTrue(shell.commands.get(0), shell.commands.get(0).startsWith("if cd '/sdcard/it'\\''s' "));
    }

    @Test
    public void joinEParent() {
        assertEquals("/sdcard", RemoteFiles.join("/", "sdcard"));
        assertEquals("/sdcard/Download", RemoteFiles.join("/sdcard", "Download"));
        assertEquals("/sdcard/Download", RemoteFiles.join("/sdcard/", "Download"));
        assertEquals("/sdcard", RemoteFiles.parent("/sdcard/Download"));
        assertEquals("/", RemoteFiles.parent("/sdcard"));
        assertEquals("/", RemoteFiles.parent("/"));
    }

    @Test
    public void formatSize() {
        assertEquals("0 B", RemoteFiles.formatSize(0));
        assertEquals("1023 B", RemoteFiles.formatSize(1023));
        assertEquals("1.0 KB", RemoteFiles.formatSize(1024));
        assertEquals("1.5 MB", RemoteFiles.formatSize(1024 * 1024 * 3 / 2));
        assertEquals("2.00 GB", RemoteFiles.formatSize(2L * 1024 * 1024 * 1024));
    }

    @Test
    public void screenshot_falhaMostraOMotivo() {
        try {
            RemoteFiles.screenshot(new FakeShell("Error: permission denied\n"));
            fail();
        } catch (Exception e) {
            assertEquals("O aparelho não capturou a tela: Error: permission denied", e.getMessage());
        }
    }
}
