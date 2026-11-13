package com.bkp.adbmobile;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** QR do pareamento: formato que a Depuração sem fio entende e leitura de volta do código gerado. */
public class QrPairingTest {

    @Test
    public void textoNoFormatoDaDepuracaoSemFio() {
        assertEquals("WIFI:T:ADB;S:adbmobile-AbC23456;P:xyz7890123;;",
                QrPairing.qrText("adbmobile-AbC23456", "xyz7890123"));
    }

    @Test
    public void aleatorio_tamanhoECaracteresSeguros() {
        Random r = new Random(1);
        String s = QrPairing.random(r, 200);
        assertEquals(200, s.length());
        // Sem caracteres que quebrariam o formato (";", ":") nem parecidos (0, O, 1, l, I).
        assertTrue(s, s.matches("[A-HJ-NP-Za-km-np-z2-9]+"));
        assertNotEquals(QrPairing.random(new Random(1), 10), QrPairing.random(new Random(2), 10));
    }

    @Test
    public void qrGerado_lidoDeVolta() throws Exception {
        String text = QrPairing.qrText("adbmobile-Qw3rTy89", "Pa55w0rdXy");
        BitMatrix m = QrPairing.encode(text, 300);
        int w = m.getWidth(), h = m.getHeight();
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) pixels[y * w + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
        LuminanceSource source = new RGBLuminanceSource(w, h, pixels);
        String read = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
        assertEquals(text, read);
    }
}
