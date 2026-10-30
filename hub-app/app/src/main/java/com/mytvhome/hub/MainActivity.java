package com.mytvhome.hub;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A full-screen WebView. The built-in home page (assets/home.html) shows the clock, weather and the cards;
 * the Agents Dashboard card loads the owner's own URL in the same WebView, with remote-key navigation injected.
 */
public class MainActivity extends Activity {

    static final String ASSET_PREFIX = "file:///android_asset/";
    static final String HOME_URL = ASSET_PREFIX + "home.html";
    static final int PHOTO_PORT = 8090;
    private static final String TAG = "TvHub";

    private WebView web;
    private PhotoServer photoServer;
    private String spatialNavScript = "";
    private volatile String currentUrl = HOME_URL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        spatialNavScript = readAsset("spatial-nav.js");
        photoServer = RemoteService.get(this);
        RemoteService.start(this);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        web.addJavascriptInterface(new HubBridge(this), "Hub");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                currentUrl = url;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!isHomeUrl(url)) view.evaluateJavascript(spatialNavScript, null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && !isHomeUrl(request.getUrl().toString())) {
                    view.loadUrl(HOME_URL + "?error=" + Uri.encode(String.valueOf(error.getDescription())));
                }
            }
        });
        setContentView(web);
        web.loadUrl(HOME_URL);
        web.requestFocus();
    }

    static boolean isHomeUrl(String url) {
        return url != null && url.startsWith(ASSET_PREFIX);
    }

    /** True while the built-in page is showing; the JS bridge refuses calls from any other page. */
    boolean onHomePage() {
        return isHomeUrl(currentUrl);
    }

    void openUrl(String url) {
        runOnUiThread(() -> web.loadUrl(url));
    }

    void onPhotoReceived(String name) {
        runOnUiThread(() -> web.evaluateJavascript("window.hubPhoto && hubPhoto(" + jsString(name) + ")", null));
    }

    void onPhotoCodeChanged() {
        runOnUiThread(() -> web.evaluateJavascript("window.hubCodeChanged && hubCodeChanged()", null));
    }

    PhotoServer photoServer() {
        return photoServer;
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        photoServer.setListener(new PhotoServer.Listener() {
            @Override
            public void onPhotoReceived(String name) {
                MainActivity.this.onPhotoReceived(name);
            }

            @Override
            public void onPhotoCodeChanged() {
                MainActivity.this.onPhotoCodeChanged();
            }
        });
        if (onHomePage()) web.evaluateJavascript("window.hubResume && hubResume()", null);
    }

    @Override
    protected void onPause() {
        photoServer.setListener(null);
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (!onHomePage()) {
            WebBackForwardList list = web.copyBackForwardList();
            int i = list.getCurrentIndex();
            if (i > 0 && !isHomeUrl(list.getItemAtIndex(i - 1).getUrl())) web.goBack();
            else web.loadUrl(HOME_URL);
            return;
        }
        // The page closes its own overlay first and answers true; otherwise leave for the launcher.
        web.evaluateJavascript("window.hubBack ? hubBack() : false", handled -> {
            if (!"true".equals(handled)) finish();
        });
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            return new String(PhotoServer.readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "missing asset " + name, e);
            return "";
        }
    }

    static String jsString(String s) {
        StringBuilder b = new StringBuilder("'");
        for (char c : s.toCharArray()) {
            if (c == '\'' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20 || c == '<' || c == '>') b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('\'').toString();
    }
}
