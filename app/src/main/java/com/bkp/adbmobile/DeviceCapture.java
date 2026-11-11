package com.bkp.adbmobile;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.widget.ImageView;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.muntashirakon.adb.AdbPairingRequiredException;

import static com.bkp.adbmobile.MainActivity.Status;

/** "Gerar log do aparelho" e "Capturar tela": trazem do aparelho alvo e salvam no celular. */
final class DeviceCapture {

    private final MainActivity a;
    /** Captura mostrada na janela, guardada enquanto o seletor de onde salvar está aberto. */
    private byte[] screenshotPng;

    DeviceCapture(MainActivity activity) {
        a = activity;
    }

    // ---------- Log do aparelho ----------

    /** Abre o seletor do sistema para escolher onde salvar o log (.txt). */
    void chooseLogFile() {
        Devices.Device[] t = new Devices.Device[1];
        if (!a.oneTarget("Gerar log", t)) return;
        String who = t[0] != null ? t[0].host : (Build.MODEL == null ? "aparelho" : Build.MODEL);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, DeviceLog.fileName(who));
        a.openPicker(intent, MainActivity.REQUEST_SAVE_LOG);
    }

    void onLogFileChosen(Uri uri) {
        if (uri == null) {
            a.setStatus(Status.INFO, "Geração de log cancelada.");
            return;
        }
        // Recalcula o alvo: a tela pode ter sido recriada enquanto o seletor estava aberto.
        Devices.Device[] t = new Devices.Device[1];
        if (!a.oneTarget("Gerar log", t)) return;
        saveLog(uri, t[0]);
    }

    /** Coleta o logcat do aparelho alvo e grava no arquivo escolhido. */
    private void saveLog(final Uri uri, final Devices.Device target) {
        a.start(MainActivity.prefix(target) + "$ " + Adb.LOG_COMMAND);
        a.background(() -> {
            try {
                Adb.Shell shell = a.openTarget(target);
                long lines;
                try {
                    lines = DeviceLog.save(shell, a.getContentResolver(), uri,
                            target != null ? target.address() : "este aparelho", null).lines;
                } finally {
                    shell.close();
                }
                a.finish(Status.SUCCESS, "Log salvo (" + lines + " linhas): " + a.fileName(uri));
            } catch (AdbPairingRequiredException e) {
                DeviceLog.delete(a.getContentResolver(), uri);
                a.pairingRequired(target != null ? target.address() : null);
            } catch (Exception e) {
                DeviceLog.delete(a.getContentResolver(), uri);
                a.finish(Status.ERROR, "Não foi possível gerar o log: " + Adb.describe(e));
            }
        });
    }

    // ---------- Capturar tela ----------

    void captureScreen() {
        a.withTargetShell("Capturar tela", "capturar tela", "Não foi possível capturar a tela: ", shell -> {
            final byte[] png = RemoteFiles.screenshot(shell);
            final Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (bmp == null) throw new IOException("a imagem recebida não é um PNG válido");
            a.finish(Status.SUCCESS, "Tela capturada (" + bmp.getWidth() + "x" + bmp.getHeight() + ").");
            a.runOnUiThread(() -> showScreenshot(png, bmp));
        });
    }

    private void showScreenshot(final byte[] png, Bitmap bmp) {
        ImageView image = new ImageView(a);
        image.setImageBitmap(bmp);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(a.dp(12), a.dp(12), a.dp(12), 0);
        image.setMaxHeight((int) (a.getResources().getDisplayMetrics().heightPixels * 0.65));
        new AlertDialog.Builder(a)
                .setTitle("Tela do aparelho")
                .setView(image)
                .setPositiveButton("Salvar", (d, w) -> {
                    screenshotPng = png;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/png");
                    intent.putExtra(Intent.EXTRA_TITLE, "tela_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",
                            Locale.getDefault()).format(new Date()) + ".png");
                    a.openPicker(intent, MainActivity.REQUEST_SAVE_SCREENSHOT);
                })
                .setNeutralButton("Atualizar", (d, w) -> captureScreen())
                .setNegativeButton("Fechar", null)
                .show();
    }

    void saveScreenshot(final Uri uri) {
        final byte[] png = screenshotPng;
        screenshotPng = null;
        if (uri == null) {
            a.setStatus(Status.INFO, "Captura não salva.");
            return;
        }
        if (png == null) {
            DeviceLog.delete(a.getContentResolver(), uri);
            a.setStatus(Status.ERROR, "Capture a tela de novo para salvar.");
            return;
        }
        final String name = a.fileName(uri);
        a.background(() -> {
            try (OutputStream out = a.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("não foi possível gravar em " + name);
                out.write(png);
                a.runOnUiThread(() -> {
                    a.append("Captura salva: " + name);
                    a.setStatus(Status.SUCCESS, "Captura salva: " + name);
                });
            } catch (Exception e) {
                DeviceLog.delete(a.getContentResolver(), uri);
                a.runOnUiThread(() -> a.setStatus(Status.ERROR, "Não foi possível salvar: " + Adb.describe(e)));
            }
        });
    }
}
