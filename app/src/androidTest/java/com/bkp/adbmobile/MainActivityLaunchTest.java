package com.bkp.adbmobile;

import android.widget.Spinner;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Abre a tela principal de verdade no aparelho. Pega o tipo de falha que compilar não pega,
 * como a 2.10, que fechava ao abrir.
 */
@RunWith(AndroidJUnit4.class)
public class MainActivityLaunchTest {

    @Test
    public void abreSemFecharEComALista() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());
            scenario.onActivity(activity -> {
                TextView version = activity.findViewById(R.id.version);
                assertTrue(version.getText().toString(), version.getText().toString().startsWith("v2."));
                Spinner preset = activity.findViewById(R.id.preset);
                assertTrue(preset.getCount() >= AdbCommands.PRESETS.length);
                // A seleção nunca pode cair num título de grupo.
                Object selected = preset.getSelectedItem();
                assertTrue(selected instanceof AdbCommands.Preset);
                assertTrue(!((AdbCommands.Preset) selected).isHeader());
            });
        }
    }
}
