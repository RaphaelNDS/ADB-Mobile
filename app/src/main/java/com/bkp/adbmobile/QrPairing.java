package com.bkp.adbmobile;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * "Parear por QR code" (Outro aparelho, Android 11+): o ADB Mobile mostra um QR com um nome e
 * uma senha; o aparelho alvo lê o QR em "Parear o dispositivo com código QR", anuncia o serviço
 * de pareamento com esse nome, e o ADB Mobile pareia com a senha. Igual ao Android Studio.
 */
final class QrPairing {

    /** Tempo para a pessoa abrir a tela do QR no outro aparelho e apontar a câmera. */
    private static final long TIMEOUT_MS = 3 * 60 * 1000;
    /** Sem letras/números parecidos (0/O, 1/l/I), caso alguém precise ler o código. */
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final MainActivity a;

    QrPairing(MainActivity activity) {
        a = activity;
    }

    /** Texto do QR no formato que a "Depuração sem fio" entende. */
    static String qrText(String name, String password) {
        return "WIFI:T:ADB;S:" + name + ";P:" + password + ";;";
    }

    static String random(Random r, int length) {
        StringBuilder b = new StringBuilder(length);
        for (int i = 0; i < length; i++) b.append(CHARS.charAt(r.nextInt(CHARS.length())));
        return b.toString();
    }

    /** Matriz do QR (quadrado de {@code size} pontos, com margem pequena). */
    static BitMatrix encode(String text, int size) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 2);
        return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
    }

    private static Bitmap toBitmap(BitMatrix m) {
        int w = m.getWidth(), h = m.getHeight();
        int[] pixels = new int[w * h];
        // Preto no branco sempre (também no tema escuro): a câmera lê melhor.
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) pixels[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
        return bmp;
    }

    void open() {
        SecureRandom r = new SecureRandom();
        final String name = "adbmobile-" + random(r, 8);
        final String password = random(r, 10);

        int size = Math.min(a.dp(260), (int) (a.getResources().getDisplayMetrics().widthPixels * 0.7));
        Bitmap qr;
        try {
            qr = toBitmap(encode(qrText(name, password), size));
        } catch (WriterException e) {
            a.setStatus(MainActivity.Status.ERROR, "Não foi possível gerar o QR code: " + Adb.describe(e));
            return;
        }

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(a.dp(20), a.dp(8), a.dp(20), 0);
        TextView help = new TextView(a);
        help.setText("No aparelho alvo: Opções do desenvolvedor > Depuração sem fio (Samsung: Depuração por "
                + "Wi-Fi) > \"Parear o dispositivo com código QR\", e aponte a câmera para o código. Os dois "
                + "aparelhos precisam estar na mesma rede Wi-Fi. Android 11 ou mais novo.");
        help.setTextColor(a.color(R.color.text_secondary));
        box.addView(help);
        ImageView image = new ImageView(a);
        image.setImageBitmap(qr);
        image.setContentDescription("QR code de pareamento");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.topMargin = a.dp(12);
        box.addView(image, lp);
        final TextView state = new TextView(a);
        state.setText("Aguardando o aparelho ler o código...");
        state.setPadding(0, a.dp(10), 0, a.dp(4));
        box.addView(state);

        final Adb.Stopper stopper = new Adb.Stopper();
        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("Parear por QR code")
                .setView(box)
                .setNegativeButton("Cancelar", null)
                .create();
        dialog.setOnDismissListener(d -> stopper.stop());
        dialog.show();

        final Handler main = new Handler(Looper.getMainLooper());
        a.start("Pareamento por QR code: aguardando o aparelho alvo ler o código.");
        a.background(() -> {
            try {
                InetSocketAddress at = Adb.waitPairingService(a, name, TIMEOUT_MS, stopper);
                if (at == null) {
                    a.finish(MainActivity.Status.ERROR, stopper.isStopped() ? "Pareamento por QR code cancelado."
                            : "Nenhum aparelho leu o QR code em 3 minutos.");
                    main.post(dialog::dismiss);
                    return;
                }
                final String host = at.getAddress().getHostAddress();
                main.post(() -> state.setText("Código lido por " + host + ". Pareando..."));
                Adb.pairRemote(a, host, at.getPort(), password);
                // A porta de conexão é outra (a do pareamento fecha); a Depuração sem fio a anuncia.
                List<Integer> ports = Adb.discoverConnectPorts(a, host, 6000);
                final int port = ports.isEmpty() ? -1 : ports.get(0);
                main.post(() -> {
                    a.devices.fillForm(host, port);
                    dialog.dismiss();
                });
                a.finish(MainActivity.Status.SUCCESS, "Pareado com " + host
                        + (port > 0 ? " (porta de conexão " + port + "). Dê um nome e toque em \"Salvar aparelho\"."
                        : ". Informe a porta de conexão mostrada na Depuração sem fio do aparelho alvo."));
            } catch (Exception e) {
                main.post(dialog::dismiss);
                a.finish(MainActivity.Status.ERROR, "Falha no pareamento por QR code: " + Adb.describe(e));
            }
        });
    }
}
