package com.bkp.adbmobile;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Ver tela: gesto na imagem vira toque ou arraste no aparelho. */
public class ScreenMirrorTest {

    @Test
    public void quaseParado_ehToque() {
        assertEquals("input tap 540 1200", ScreenMirror.gesture(new int[]{540, 1200}, new int[]{550, 1210}, 80));
    }

    @Test
    public void mexeu_ehArraste() {
        assertEquals("input swipe 540 1800 540 400 300",
                ScreenMirror.gesture(new int[]{540, 1800}, new int[]{540, 400}, 300));
    }

    @Test
    public void duracaoDoArraste_limitada() {
        assertEquals("input swipe 0 0 500 0 100", ScreenMirror.gesture(new int[]{0, 0}, new int[]{500, 0}, 10));
        assertEquals("input swipe 0 0 500 0 2000", ScreenMirror.gesture(new int[]{0, 0}, new int[]{500, 0}, 9000));
    }
}
