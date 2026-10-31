package com.mytvhome.hub;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.util.Date;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.security.auth.x500.X500Principal;

/**
 * HTTPS for the phone page. Phone browsers only allow the camera and microphone on https pages, so the
 * remote is also served on a second port with a self-signed certificate that this box generates for itself
 * (the private key never leaves Android's key store, and no two boxes share one). Browsers warn once
 * ("not private"); choose Advanced, then Continue.
 */
final class TlsSupport {

    // Not 8443: Chromecast built-in listens there.
    static final int HTTPS_PORT = 8091;
    private static final String TAG = "TvHubTls";
    private static final String KEY_STORE = "AndroidKeyStore";
    private static final String ALIAS = "tvhub-tls";
    private static final long CERT_YEARS_MS = 10L * 365 * 24 * 3600 * 1000;
    private static final long CLOCK_SKEW_MS = 24L * 3600 * 1000;
    private static final int KEY_BITS = 2048;

    private TlsSupport() {
    }

    /** A listening HTTPS socket, or null when this device cannot do it (the HTTP remote still works). */
    static SSLServerSocket listen(int port) {
        try {
            KeyStore store = KeyStore.getInstance(KEY_STORE);
            store.load(null);
            if (!store.containsAlias(ALIAS)) generate();
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(store, null);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            SSLServerSocket s = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port));
            return s;
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            Log.w(TAG, "https is not available on this device", e);
            return null;
        }
    }

    private static void generate() throws GeneralSecurityException {
        long now = System.currentTimeMillis();
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEY_STORE);
        g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(KEY_BITS)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512,
                        KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_NONE)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1, KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setCertificateSubject(new X500Principal("CN=TV Hub"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(new Date(now - CLOCK_SKEW_MS))
                .setCertificateNotAfter(new Date(now + CERT_YEARS_MS))
                .build());
        g.generateKeyPair();
    }
}
