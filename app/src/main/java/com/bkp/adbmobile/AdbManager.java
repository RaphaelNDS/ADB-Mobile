package com.bkp.adbmobile;

import android.content.Context;
import android.os.Build;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * Cliente ADB do app. A chave/certificado ficam salvos para o pareamento valer entre execuções,
 * e são os mesmos para todos os aparelhos (local e remotos).
 */
public class AdbManager extends AbsAdbConnectionManager {

    private static AdbManager instance;
    private static PrivateKey privateKey;
    private static Certificate certificate;

    /** Conexão com o próprio aparelho (127.0.0.1), reaproveitada entre comandos. */
    public static synchronized AdbManager get(Context context) throws Exception {
        if (instance == null) instance = new AdbManager(context.getApplicationContext());
        return instance;
    }

    /** Nova conexão independente, para outro aparelho da rede. Quem cria deve chamar close(). */
    public static AdbManager create(Context context) throws Exception {
        return new AdbManager(context.getApplicationContext());
    }

    private AdbManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        setHostAddress("127.0.0.1");
        loadKeys(context);
    }

    private static synchronized void loadKeys(Context context) throws Exception {
        if (privateKey != null) return;
        File keyFile = new File(context.getFilesDir(), "adb.key");
        File certFile = new File(context.getFilesDir(), "adb.crt");
        if (keyFile.exists() && certFile.exists()) {
            privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(readAll(keyFile)));
            InputStream in = new FileInputStream(certFile);
            try {
                certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
            } finally {
                in.close();
            }
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();

            X500Name name = new X500Name("CN=ADB Mobile");
            Date notBefore = new Date();
            Date notAfter = new Date(notBefore.getTime() + 3650L * 24 * 60 * 60 * 1000);
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name,
                    BigInteger.valueOf(System.currentTimeMillis()), notBefore, notAfter, name,
                    keyPair.getPublic());
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());

            privateKey = keyPair.getPrivate();
            certificate = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
            writeAll(keyFile, privateKey.getEncoded());
            writeAll(certFile, certificate.getEncoded());
        }
    }

    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @Override
    protected String getDeviceName() {
        return "ADB Mobile";
    }

    private static byte[] readAll(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void writeAll(File file, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }
}
