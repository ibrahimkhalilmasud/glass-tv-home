package com.mytvhome.hub;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * A minimal ADB client that talks to this box's own adbd on 127.0.0.1:5555 to run "input keyevent" and
 * "monkey" (an app may not inject keys into other apps itself). The first connection makes Android show
 * "Allow USB debugging?" for this app's key; after "Always allow" it connects silently.
 */
final class AdbClient {

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;
    private static final int VERSION = 0x01000000;
    private static final int MAX_DATA = 4096;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    // Waiting for the owner to accept the on-screen prompt can take a while.
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final KeyPair keys;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int nextLocalId = 1;

    AdbClient(Context context) throws IOException, GeneralSecurityException {
        this.keys = loadOrCreateKeys(context.getFilesDir());
    }

    /** Runs a shell command and returns its output; reconnects once if the old connection went stale. */
    synchronized String shell(String command) throws IOException, GeneralSecurityException {
        try {
            ensureConnected();
            return runShell(command);
        } catch (IOException e) {
            close();
            ensureConnected();
            return runShell(command);
        }
    }

    synchronized void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already gone.
            }
        }
        socket = null;
    }

    private void ensureConnected() throws IOException, GeneralSecurityException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) return;
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", 5555), CONNECT_TIMEOUT_MS);
        s.setSoTimeout(READ_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
        send(A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean sentSignature = false;
        while (true) {
            Message m = read();
            if (m.command == A_CNXN) return;
            if (m.command != A_AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected adb reply");
            if (!sentSignature) {
                send(A_AUTH, AUTH_SIGNATURE, 0, sign(m.data));
                sentSignature = true;
            } else {
                // Key not known yet: Android now shows the "Allow USB debugging?" prompt.
                send(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob());
            }
        }
    }

    private String runShell(String command) throws IOException {
        int localId = nextLocalId++;
        send(A_OPEN, localId, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            Message m = read();
            if (m.arg1 != localId) continue;
            if (m.command == A_WRTE) {
                output.write(m.data);
                send(A_OKAY, localId, m.arg0, new byte[0]);
            } else if (m.command == A_CLSE) {
                send(A_CLSE, localId, m.arg0, new byte[0]);
                return output.toString(StandardCharsets.UTF_8.name());
            }
        }
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte d : data) sum += d & 0xFF;
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~command).put(data);
        out.write(b.array());
        out.flush();
    }

    private Message read() throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message(b.getInt(), b.getInt(), b.getInt());
        int length = b.getInt();
        if (length < 0 || length > 1024 * 1024) throw new IOException("bad adb length");
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }

    /** ADB signs the 20-byte token as if it were a SHA-1 digest: PKCS#1 v1.5 over DigestInfo + token. */
    private byte[] sign(byte[] token) throws IOException {
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.ENCRYPT_MODE, keys.getPrivate());
            c.update(SHA1_DIGEST_INFO);
            return c.doFinal(token);
        } catch (GeneralSecurityException e) {
            throw new IOException("could not sign adb token", e);
        }
    }

    /** The public key in Android's own format: base64 of the RSAPublicKey struct, then a name. */
    private byte[] publicKeyBlob() {
        RSAPublicKey pub = (RSAPublicKey) keys.getPublic();
        int words = 64;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n = pub.getModulus();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).mod(r32).intValue());
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).mod(r32).intValue());
        b.putInt(pub.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " tvhub@box\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static KeyPair loadOrCreateKeys(File dir) throws IOException, GeneralSecurityException {
        File priv = new File(dir, "adbkey.pk8");
        File pub = new File(dir, "adbkey.x509");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.isFile() && pub.isFile()) {
            PrivateKey p = kf.generatePrivate(new PKCS8EncodedKeySpec(readFile(priv)));
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readFile(pub))), p);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        writeFile(priv, kp.getPrivate().getEncoded());
        writeFile(pub, kp.getPublic().getEncoded());
        return kp;
    }

    private static byte[] readFile(File f) throws IOException {
        try (InputStream s = new FileInputStream(f)) {
            return PhotoServer.readAll(s);
        }
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        try (OutputStream s = new FileOutputStream(f)) {
            s.write(data);
        }
    }

    private static final class Message {
        final int command;
        final int arg0;
        final int arg1;
        byte[] data;

        Message(int command, int arg0, int arg1) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
        }
    }
}
