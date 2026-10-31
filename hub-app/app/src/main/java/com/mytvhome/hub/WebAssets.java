package com.mytvhome.hub;

import android.content.Context;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Serves the bundled phone-page scripts (/s/...) and the hand-tracking library (/mp/...) straight from the
 * APK's assets. Only those two prefixes, only plain file names, never anything outside assets/web or assets/mp.
 */
final class WebAssets {

    private static final Pattern SAFE = Pattern.compile("^/(s|mp)/[A-Za-z0-9_][A-Za-z0-9_./-]*$");
    private static final int CHUNK = 16 * 1024;

    private WebAssets() {
    }

    /** True when the path looks like one of ours (found or not), so the caller stops routing. */
    static boolean handles(String path) {
        return path.startsWith("/s/") || path.startsWith("/mp/");
    }

    static void serve(Context context, String rawPath, OutputStream out) throws IOException {
        int q = rawPath.indexOf('?');
        String path = q >= 0 ? rawPath.substring(0, q) : rawPath;
        if (!SAFE.matcher(path).matches() || path.contains("..")) {
            PhotoServer.respond(out, 404, "text/plain", "not found");
            return;
        }
        String asset = path.startsWith("/s/") ? "web/" + path.substring(3) : "mp/" + path.substring(4);
        InputStream in;
        try {
            in = context.getAssets().open(asset);
        } catch (FileNotFoundException e) {
            PhotoServer.respond(out, 404, "text/plain", "not found");
            return;
        }
        try (InputStream file = in) {
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + type(path) + "\r\n"
                    + "Cache-Control: public, max-age=3600\r\n"
                    + "X-Content-Type-Options: nosniff\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            byte[] buf = new byte[CHUNK];
            for (int n; (n = file.read(buf)) > 0; ) out.write(buf, 0, n);
            out.flush();
        }
    }

    private static String type(String path) {
        if (path.endsWith(".js") || path.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".wasm")) return "application/wasm";
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }
}
