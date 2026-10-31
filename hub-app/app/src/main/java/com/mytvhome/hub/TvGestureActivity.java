package com.mytvhome.hub;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * Hand-gesture control from a camera plugged into the TV box. It shows the same hand-tracking page the phone
 * uses (served by this app on 127.0.0.1, which browsers treat as a secure origin) and lets it use the camera.
 * Back closes it.
 */
public class TvGestureActivity extends Activity {

    private static final String LOCAL_ORIGIN = "http://127.0.0.1:" + MainActivity.PHOTO_PORT;
    private static final int REQUEST_CAMERA = 7;

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                boolean local = request.getOrigin().toString().startsWith(LOCAL_ORIGIN);
                runOnUiThread(() -> {
                    if (local && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[] {PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        request.deny();
                    }
                });
            }
        });
        setContentView(web);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) load();
        else requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA);
    }

    private void load() {
        web.loadUrl(LOCAL_ORIGIN + "/g#" + RemoteService.get(this).remoteToken());
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code == REQUEST_CAMERA && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) load();
        else finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
