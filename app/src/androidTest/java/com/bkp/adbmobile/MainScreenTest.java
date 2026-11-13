package com.bkp.adbmobile;

import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Fluxos da tela que não dependem de ADB (rodam no emulador sem pareamento). Use só no
 * emulador: o teste reinstala o app (debug) no aparelho em que roda.
 */
@RunWith(AndroidJUnit4.class)
public class MainScreenTest {

    @Test
    public void outroAparelho_trocaOsBotoesDePareamento() {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.targetLocal)).perform(scrollTo(), click());
            s.onActivity(a -> {
                assertEquals(View.VISIBLE, a.findViewById(R.id.pairNotification).getVisibility());
                assertEquals(View.GONE, a.findViewById(R.id.pairQr).getVisibility());
            });
            onView(withId(R.id.targetRemote)).perform(scrollTo(), click());
            s.onActivity(a -> {
                assertEquals(View.GONE, a.findViewById(R.id.pairNotification).getVisibility());
                assertEquals(View.VISIBLE, a.findViewById(R.id.pairQr).getVisibility());
                assertEquals(View.VISIBLE, a.findViewById(R.id.remoteBox).getVisibility());
            });
            onView(withId(R.id.targetLocal)).perform(scrollTo(), click());
        }
    }

    @Test
    public void qrCode_abreECancela() {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.targetRemote)).perform(scrollTo(), click());
            onView(withId(R.id.pairQr)).perform(scrollTo(), click());
            onView(withContentDescription("QR code de pareamento")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click());
            // O cancelamento termina no thread de fundo (a busca mDNS confere a cada 0,5 s).
            String status = "";
            for (int i = 0; i < 50 && !status.endsWith("cancelado."); i++) {
                SystemClock.sleep(100);
                final String[] now = new String[1];
                s.onActivity(a -> now[0] = ((TextView) a.findViewById(R.id.status)).getText().toString());
                status = now[0];
            }
            assertEquals("Pareamento por QR code cancelado.", status);
            s.onActivity(a -> {
                // A tela volta a aceitar comandos.
                assertTrue(a.findViewById(R.id.run).isEnabled());
            });
            onView(withId(R.id.targetLocal)).perform(scrollTo(), click());
        }
    }

    @Test
    public void comandoPronto_preencheOCampo() {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            s.onActivity(a -> {
                Spinner preset = a.findViewById(R.id.preset);
                int bateria = -1;
                for (int i = 0; i < preset.getCount(); i++) {
                    Object item = preset.getItemAtPosition(i);
                    if (item instanceof AdbCommands.Preset && "Bateria".equals(((AdbCommands.Preset) item).label)) {
                        bateria = i;
                    }
                }
                assertTrue("Bateria não está na lista", bateria >= 0);
                preset.setSelection(bateria);
            });
            s.onActivity(a -> assertEquals("dumpsys battery",
                    ((EditText) a.findViewById(R.id.command)).getText().toString()));
        }
    }

    @Test
    public void android15_cabecalhoAbaixoDaBarraDeStatus() {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            s.onActivity(a -> {
                ViewGroup root = (ViewGroup) ((ViewGroup) a.findViewById(android.R.id.content)).getChildAt(0);
                View header = root.getChildAt(0);
                int padding14dp = Math.round(14 * a.getResources().getDisplayMetrics().density);
                if (Build.VERSION.SDK_INT >= 35) {
                    // Edge-to-edge: o cabeçalho ganha a altura da barra de status além dos 14 dp.
                    assertTrue("paddingTop=" + header.getPaddingTop(), header.getPaddingTop() > padding14dp);
                } else {
                    assertTrue("paddingTop=" + header.getPaddingTop(), Math.abs(header.getPaddingTop() - padding14dp) <= 1);
                }
            });
        }
    }

    @Test
    public void botoesNovos_estaoNaTela() {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.kioskSetup)).perform(scrollTo()).check(matches(withText("Preparar Kiosk (instalar + Device Owner)")));
            onView(withId(R.id.checkUpdate)).perform(scrollTo()).check(matches(isDisplayed()));
        }
    }
}
