# R8 (minifyEnabled): remove o código não usado e otimiza o resto.

# Nomes originais nas mensagens e pilhas de erro (o Registro mostra o nome da exceção).
-dontobfuscate

# Biblioteca do ADB e Conscrypt (TLS): carregam provedores e classes por reflexão e pelo código
# nativo (JNI). Ficam inteiras.
-keep class io.github.muntashirakon.adb.** { *; }
-keep class org.conscrypt.** { *; }

# BouncyCastle: só gera o certificado do ADB (AdbManager.loadKeys), sem registrar o provedor BC.
# Ficam inteiros os pacotes desse caminho; o resto (algoritmos, pós-quânticos etc.) sai.
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.cert.** { *; }
-keep class org.bouncycastle.operator.** { *; }
-keep class org.bouncycastle.jcajce.util.** { *; }
-keep class org.bouncycastle.util.** { *; }
-dontwarn io.github.muntashirakon.adb.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn sun.security.**
-dontwarn javax.naming.**
