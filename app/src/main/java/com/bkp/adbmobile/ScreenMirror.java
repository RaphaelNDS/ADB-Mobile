package com.bkp.adbmobile;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

import io.github.muntashirakon.adb.AdbPairingRequiredException;

/**
 * "Ver tela do aparelho": a tela do aparelho alvo, atualizada sem parar (uma captura atrás da
 * outra, ~1 por segundo pela rede), com toque e arraste na imagem viram "input tap/swipe" no
 * aparelho, e os botões Voltar e Início. Para suporte à distância sem ir até o aparelho.
 */
final class ScreenMirror {

    /** Movimento (em pixels da tela do aparelho) abaixo do qual o gesto conta como toque. */
    static final int TAP_SLOP = 24;

    private final MainActivity a;

    ScreenMirror(MainActivity activity) {
        a = activity;
    }

    /**
     * Converte um ponto da imagem na tela (x, y da view) para o pixel da tela do aparelho, ou
     * null se caiu fora da imagem. {@code inverse} é o inverso da matriz da ImageView; {@code scale}
     * é quantos pixels do aparelho cabem em cada pixel do bitmap mostrado.
     */
    static int[] toDevice(Matrix inverse, float x, float y, int bitmapW, int bitmapH, float scale) {
        float[] p = {x, y};
        inverse.mapPoints(p);
        if (p[0] < 0 || p[1] < 0 || p[0] >= bitmapW || p[1] >= bitmapH) return null;
        return new int[]{Math.round(p[0] * scale), Math.round(p[1] * scale)};
    }

    /** Comando de entrada para um gesto: toque se quase não mexeu, senão arraste. */
    static String gesture(int[] from, int[] to, long millis) {
        if (Math.abs(to[0] - from[0]) < TAP_SLOP && Math.abs(to[1] - from[1]) < TAP_SLOP) {
            return "input tap " + from[0] + " " + from[1];
        }
        return String.format(Locale.US, "input swipe %d %d %d %d %d", from[0], from[1], to[0], to[1],
                Math.max(100, Math.min(millis, 2000)));
    }

    @SuppressLint("ClickableViewAccessibility") // o toque vira gesto no aparelho; não há clique local
    void open() {
        final Devices.Device[] t = new Devices.Device[1];
        if (!a.oneTarget("a tela", t)) return;
        final Devices.Device target = t[0];

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(12), a.dp(4), a.dp(12), 0);
        final TextView info = new TextView(a);
        info.setTextColor(a.color(R.color.text_secondary));
        info.setText("Conectando...");
        box.addView(info);
        final ImageView image = new ImageView(a);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.BLACK);
        image.setContentDescription("Tela do aparelho alvo");
        box.addView(image, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (a.getResources().getDisplayMetrics().heightPixels * 0.62)));

        // Pedidos de toque/tecla: o mesmo thread das capturas executa entre uma e outra, numa
        // conexão só.
        final LinkedBlockingQueue<String> inputs = new LinkedBlockingQueue<>();
        final Adb.Stopper stopper = new Adb.Stopper();
        // Tamanho do bitmap mostrado e quantos pixels do aparelho cada um representa.
        final int[] shown = {0, 0};
        final float[] scale = {1};
        final int[][] down = {null};
        final long[] downAt = {0};
        image.setOnTouchListener((v, e) -> {
            if (shown[0] == 0) return true;
            Matrix inverse = new Matrix();
            if (!image.getImageMatrix().invert(inverse)) return true;
            int[] p = toDevice(inverse, e.getX(), e.getY(), shown[0], shown[1], scale[0]);
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                down[0] = p;
                downAt[0] = e.getEventTime();
            } else if (e.getAction() == MotionEvent.ACTION_UP && down[0] != null && p != null) {
                inputs.offer(gesture(down[0], p, e.getEventTime() - downAt[0]));
                info.setText("Enviando o toque...");
                down[0] = null;
            }
            return true;
        });

        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("Tela" + (target != null ? " - " + target.label() : " deste aparelho"))
                .setView(box)
                .setPositiveButton("Fechar", null)
                .setNeutralButton("Voltar", null)
                .setNegativeButton("Início", null)
                .create();
        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> inputs.offer("input keyevent 4"));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> inputs.offer("input keyevent 3"));
        });
        dialog.setOnDismissListener(x -> stopper.stop());
        dialog.show();

        final Handler main = new Handler(Looper.getMainLooper());
        a.append(MainActivity.prefix(target) + "Ver tela aberto");
        a.background(() -> {
            String end = null;
            try {
                Adb.Shell shell = a.openTarget(target);
                try {
                    while (!stopper.isStopped()) {
                        String cmd;
                        while ((cmd = inputs.poll()) != null) shell.run(cmd);
                        if (!a.visible) {
                            // App em segundo plano: não fica capturando a tela do aparelho à toa.
                            Thread.sleep(500);
                            continue;
                        }
                        long start = System.currentTimeMillis();
                        byte[] png = RemoteFiles.screenshot(shell);
                        final Bitmap bmp = decode(png, shown, scale);
                        if (bmp == null) continue;
                        final long ms = System.currentTimeMillis() - start;
                        main.post(() -> {
                            if (stopper.isStopped()) return;
                            image.setImageBitmap(bmp);
                            info.setText(String.format(Locale.getDefault(),
                                    "Toque ou arraste na imagem para usar o aparelho  •  %.1f s por quadro", ms / 1000.0));
                        });
                    }
                } finally {
                    shell.close();
                }
            } catch (AdbPairingRequiredException e) {
                end = MainActivity.NEEDS_PAIRING;
            } catch (Exception e) {
                if (!stopper.isStopped()) end = "Falha: " + Adb.describe(e);
            }
            if (end != null) {
                final String message = end;
                a.log(MainActivity.prefix(target) + "Ver tela: " + message);
                main.post(() -> info.setText(message));
            }
        });
    }

    /**
     * Lê o PNG reduzindo à metade se for grande (menos memória e mais rápido de desenhar);
     * guarda o tamanho mostrado e a escala para converter os toques.
     */
    private static Bitmap decode(byte[] png, int[] shown, float[] scale) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, o);
        if (o.outWidth <= 0) return null;
        int sample = Math.max(o.outWidth, o.outHeight) > 1600 ? 2 : 1;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length, d);
        if (bmp == null) return null;
        shown[0] = bmp.getWidth();
        shown[1] = bmp.getHeight();
        scale[0] = (float) o.outWidth / bmp.getWidth();
        return bmp;
    }
}
