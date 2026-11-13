package com.bkp.adbmobile;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Atualização: comparação de versões e leitura do último release do GitHub. */
public class UpdaterTest {

    @Test
    public void compara_numeroANumero() {
        assertTrue(Updater.compare("2.35", "2.4") > 0);   // 35 > 4, não ordem alfabética
        assertTrue(Updater.compare("2.4", "2.35") < 0);
        assertTrue(Updater.compare("3.0", "2.99") > 0);
        assertEquals(0, Updater.compare("2.35", "2.35"));
        assertEquals(0, Updater.compare("2.35", "2.35.0"));
        assertTrue(Updater.compare("2.35.1", "2.35") > 0);
        assertTrue(Updater.compare("2.36-beta", "2.35") > 0); // sufixo ignorado
    }

    private static final String RELEASE = "{\"tag_name\":\"v2.36\",\"body\":\"## Novidades\\n- algo\","
            + "\"assets\":[{\"name\":\"Manual-do-ADB-Mobile.pdf\",\"browser_download_url\":\"https://x/manual.pdf\",\"size\":1},"
            + "{\"name\":\"ADB-Mobile-2.36.apk\",\"browser_download_url\":\"https://x/ADB-Mobile-2.36.apk\",\"size\":2500000}]}";

    @Test
    public void le_oReleaseEOApk() throws Exception {
        Updater.Release r = Updater.parse(RELEASE);
        assertEquals("2.36", r.version);
        assertEquals("https://x/ADB-Mobile-2.36.apk", r.apkUrl);
        assertEquals(2500000, r.apkSize);
        assertTrue(r.notes.startsWith("## Novidades"));
    }

    @Test
    public void releaseSemApk_lancaErro() {
        try {
            Updater.parse("{\"tag_name\":\"v2.36\",\"assets\":[]}");
            fail();
        } catch (Exception e) {
            assertEquals("O release v2.36 não tem APK.", e.getMessage());
        }
    }
}
