package com.mytvhome.hub;

import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A tiny LAN-only HTTP server that runs while TV Hub is open. The phone opens http://<tv ip>:8090, picks a
 * photo, and the page sends a 1920x1080 JPEG with the 6-digit code shown on the TV. The photo goes into the
 * launcher's wallpapers/ folder through its ThemeProvider, where Launcher settings > Wallpaper lists it.
 */
class PhotoServer {

    private static final String TAG = "TvHubPhoto";
    private static final String THEME_URI = "content://com.gathin.tvhome.theme/wallpapers/";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 8 * 1024;
    private static final int MAX_FAILED_CODES = 5;
    private static final int SOCKET_TIMEOUT_MS = 20_000;

    /** What the TV Hub screen shows when something arrives; null while TV Hub is not open. */
    interface Listener {
        void onPhotoReceived(String name);

        void onPhotoCodeChanged();
    }

    private static final String KEY_REMOTE_TOKEN = "remote_token";
    private static final java.util.regex.Pattern PACKAGE = java.util.regex.Pattern.compile("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$");
    private static final java.util.Map<String, Integer> KEYS = new java.util.HashMap<>();

    static {
        KEYS.put("up", 19);
        KEYS.put("down", 20);
        KEYS.put("left", 21);
        KEYS.put("right", 22);
        KEYS.put("ok", 23);
        KEYS.put("back", 4);
        KEYS.put("home", 3);
        KEYS.put("menu", 82);
        KEYS.put("volup", 24);
        KEYS.put("voldown", 25);
        KEYS.put("mute", 164);
        KEYS.put("playpause", 85);
        KEYS.put("rewind", 89);
        KEYS.put("forward", 90);
        KEYS.put("power", 26);
        KEYS.put("search", 84);
        KEYS.put("enter", 66);
        KEYS.put("del", 67);
    }

    private static final int MAX_TEXT_CHARS = 200;
    private static final int MAX_REMOTE_BODY = 600;

    /**
     * Turns {"del":N,"add":"text"} into one shell line: N backspaces, then the new characters. "input text"
     * only types ASCII on Android 9, so anything else is refused (null) instead of being typed wrongly.
     */
    static String textCommand(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            int del = o.optInt("del", 0);
            String add = o.optString("add", "");
            if (del < 0 || del > MAX_TEXT_CHARS || add.length() > MAX_TEXT_CHARS) return null;
            for (int i = 0; i < add.length(); i++) {
                char c = add.charAt(i);
                if (c < 32 || c > 126) return null;
            }
            StringBuilder cmd = new StringBuilder();
            if (del > 0) {
                cmd.append("input keyevent");
                for (int i = 0; i < del; i++) cmd.append(" 67");
            }
            if (!add.isEmpty()) {
                if (cmd.length() > 0) cmd.append("; ");
                // %s is how "input text" spells a space; single quotes keep every other character literal.
                cmd.append("input text '").append(add.replace("'", "'\\''").replace(" ", "%s")).append("'");
            }
            return cmd.toString();
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private final android.content.Context context;
    private final android.content.SharedPreferences prefs;
    private final int port;
    private final SecureRandom random = new SecureRandom();
    private volatile ServerSocket socket;
    private volatile ServerSocket tlsSocket;
    // Several phone requests at once (a big .wasm download must not block a key press).
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tvhub-http");
        t.setDaemon(true);
        return t;
    });
    private volatile String code;
    private volatile Listener listener;
    private int failedCodes;
    private AdbClient adb;

    PhotoServer(android.content.Context context, int port) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences("hub", android.content.Context.MODE_PRIVATE);
        this.port = port;
        this.code = newCode();
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** The secret in the phone remote's QR code; it stays the same until reset, so a bookmark keeps working. */
    synchronized String remoteToken() {
        String t = prefs.getString(KEY_REMOTE_TOKEN, "");
        if (t.isEmpty()) t = resetRemoteToken();
        return t;
    }

    synchronized String resetRemoteToken() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.US, "%02x", x));
        prefs.edit().putString(KEY_REMOTE_TOKEN, s.toString()).apply();
        return s.toString();
    }

    String code() {
        return code;
    }

    boolean isRunning() {
        ServerSocket s = socket;
        return s != null && !s.isClosed();
    }

    synchronized void start() throws IOException {
        if (isRunning()) return;
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        socket = s;
        Thread t = new Thread(() -> serve(s), "photo-server");
        t.setDaemon(true);
        t.start();
        ServerSocket tls = TlsSupport.listen(TlsSupport.HTTPS_PORT);
        if (tls != null) {
            tlsSocket = tls;
            Thread tt = new Thread(() -> serve(tls), "photo-server-tls");
            tt.setDaemon(true);
            tt.start();
        }
    }

    /** True when the https address (needed for the phone's camera and microphone) is up. */
    boolean isSecureRunning() {
        ServerSocket s = tlsSocket;
        return s != null && !s.isClosed();
    }

    synchronized void stop() {
        for (ServerSocket s : new ServerSocket[] {socket, tlsSocket}) {
            if (s == null) continue;
            try {
                s.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }
        socket = null;
        tlsSocket = null;
    }

    private void serve(ServerSocket s) {
        while (!s.isClosed()) {
            try {
                Socket c = s.accept();
                pool.execute(() -> handleSocket(c));
            } catch (IOException e) {
                if (!s.isClosed()) Log.w(TAG, "accept failed", e);
            }
        }
    }

    private void handleSocket(Socket c) {
        try (Socket sock = c) {
            sock.setSoTimeout(SOCKET_TIMEOUT_MS);
            handle(sock.getInputStream(), sock.getOutputStream());
        } catch (IOException e) {
            // A browser that rejects our self-signed certificate hangs up mid-handshake; that is expected.
            Log.d(TAG, "request failed: " + e.getMessage());
        }
    }

    private void handle(InputStream in, OutputStream out) throws IOException {
        String head = readHead(in);
        String[] lines = head == null ? new String[0] : head.split("\r\n");
        String[] request = lines.length == 0 ? new String[0] : lines[0].split(" ");
        if (request.length < 2) {
            respond(out, 400, "text/plain", "bad request");
            return;
        }
        String method = request[0];
        String path = request[1];
        if ("GET".equals(method) && ("/".equals(path) || path.startsWith("/?"))) {
            servePage(out, "upload.html");
            return;
        }
        if ("GET".equals(method) && ("/r".equals(path) || path.startsWith("/r?"))) {
            servePage(out, "remote.html");
            return;
        }
        if ("GET".equals(method) && ("/g".equals(path) || path.startsWith("/g?"))) {
            servePage(out, "g.html");
            return;
        }
        if ("GET".equals(method) && WebAssets.handles(path)) {
            WebAssets.serve(context, path, out);
            return;
        }
        if ("POST".equals(method) && ("/key".equals(path) || "/app".equals(path) || "/text".equals(path))) {
            handleRemote(path, lines, in, out);
            return;
        }
        if (!"POST".equals(method) || !"/upload".equals(path)) {
            respond(out, 404, "text/plain", "not found");
            return;
        }
        int length = parseLength(header(lines, "content-length"));
        if (length <= 0 || length > MAX_BYTES) {
            respond(out, 413, "application/json", "{\"error\":\"Photo is too large (8 MB max).\"}");
            return;
        }
        // Read the body before answering: replying and closing mid-upload makes the phone's browser report a
        // network error instead of the message.
        byte[] body = readExactly(in, length);
        if (!codeMatches(header(lines, "x-code"))) {
            respond(out, 403, "application/json", "{\"error\":\"Wrong code. Check the code on the TV.\"}");
            return;
        }
        if (body.length != length || body.length < 3 || (body[0] & 0xFF) != 0xFF || (body[1] & 0xFF) != 0xD8) {
            respond(out, 400, "application/json", "{\"error\":\"Not a JPEG photo.\"}");
            return;
        }
        String name = "zz-phone-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".jpg";
        if (!save(name, body)) {
            respond(out, 503, "application/json",
                    "{\"error\":\"The TV needs its one-time setup for photos (see the README).\"}");
            return;
        }
        Listener l = listener;
        if (l != null) l.onPhotoReceived(name);
        respond(out, 200, "application/json", "{\"ok\":true,\"name\":\"" + name + "\"}");
    }

    private void servePage(OutputStream out, String asset) throws IOException {
        try (InputStream page = context.getAssets().open(asset)) {
            respond(out, 200, "text/html; charset=utf-8", new String(readAll(page), StandardCharsets.UTF_8));
        }
    }

    /** POST /key with body "ok", "up", ... or POST /app with a package name; X-Remote must carry the token. */
    private void handleRemote(String path, String[] lines, InputStream in, OutputStream out) throws IOException {
        int length = parseLength(header(lines, "content-length"));
        if (length < 0 || length > MAX_REMOTE_BODY) {
            respond(out, 400, "application/json", "{\"error\":\"bad request\"}");
            return;
        }
        String body = new String(readExactly(in, length), StandardCharsets.UTF_8).trim();
        String token = remoteToken();
        if (!MessageDigest.isEqual(header(lines, "x-remote").getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            respond(out, 403, "application/json", "{\"error\":\"This remote link is no longer valid. Scan the QR code on the TV again.\"}");
            return;
        }
        String command;
        if ("/key".equals(path)) {
            Integer key = KEYS.get(body);
            if (key == null) {
                respond(out, 400, "application/json", "{\"error\":\"unknown key\"}");
                return;
            }
            command = "input keyevent " + key;
        } else if ("/text".equals(path)) {
            command = textCommand(body);
            if (command == null) {
                respond(out, 400, "application/json",
                        "{\"error\":\"The TV can only be typed to with English letters, numbers and common symbols.\"}");
                return;
            }
            if (command.isEmpty()) {
                respond(out, 200, "application/json", "{\"ok\":true}");
                return;
            }
        } else {
            if (!PACKAGE.matcher(body).matches()) {
                respond(out, 400, "application/json", "{\"error\":\"unknown app\"}");
                return;
            }
            command = "monkey -p " + body + " -c android.intent.category.LEANBACK_LAUNCHER 1";
        }
        try {
            synchronized (this) {
                if (adb == null) adb = new AdbClient(context);
            }
            adb.shell(command);
            respond(out, 200, "application/json", "{\"ok\":true}");
        } catch (IOException | java.security.GeneralSecurityException e) {
            Log.w(TAG, "remote command failed", e);
            respond(out, 503, "application/json",
                    "{\"error\":\"The TV did not accept the command. Is USB debugging on and this remote allowed on the TV?\"}");
        }
    }

    private boolean save(String name, byte[] body) {
        try (OutputStream file = context.getContentResolver().openOutputStream(Uri.parse(THEME_URI + name))) {
            if (file == null) return false;
            file.write(body);
            return true;
        } catch (SecurityException | IOException e) {
            Log.w(TAG, "could not write " + name, e);
            return false;
        }
    }

    private synchronized boolean codeMatches(String sent) {
        boolean ok = MessageDigest.isEqual(
                sent.trim().getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
        if (ok) {
            failedCodes = 0;
            return true;
        }
        if (++failedCodes >= MAX_FAILED_CODES) {
            failedCodes = 0;
            code = newCode();
            Listener l = listener;
            if (l != null) l.onPhotoCodeChanged();
        }
        return false;
    }

    private String newCode() {
        return String.format(Locale.US, "%06d", random.nextInt(1_000_000));
    }

    /** The whole stream; InputStream.readAllBytes() only exists from Android 13. */
    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toByteArray();
    }

    /** Up to length bytes, fewer only when the stream ends first. */
    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(buf, off, length - off);
            if (n < 0) break;
            off += n;
        }
        return off == length ? buf : java.util.Arrays.copyOf(buf, off);
    }

    private static int parseLength(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The request line and headers, up to the blank line; null when they run past MAX_HEADER_BYTES. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int matched = 0;
        while (b.size() < MAX_HEADER_BYTES) {
            int c = in.read();
            if (c < 0) return null;
            b.write(c);
            matched = (c == '\r' || c == '\n') ? matched + 1 : 0;
            if (matched == 4) return b.toString(StandardCharsets.ISO_8859_1.name()).trim();
        }
        return null;
    }

    private static String header(String[] lines, String name) {
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0 && lines[i].substring(0, colon).trim().equalsIgnoreCase(name)) {
                return lines[i].substring(colon + 1).trim();
            }
        }
        return "";
    }

    static void respond(OutputStream out, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            case 503: return "Service Unavailable";
            default: return "Bad Request";
        }
    }
}
