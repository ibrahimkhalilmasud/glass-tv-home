package com.mytvhome.hub;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.webkit.JavascriptInterface;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

/** window.Hub in the built-in page. Every call checks that the built-in page, not a loaded site, is asking. */
class HubBridge {

    private static final String PREFS = "hub";
    private static final String KEY_DASHBOARD = "dashboard_url";
    private static final int MAX_KEY_LENGTH = 64;
    private static final int MAX_VALUE_LENGTH = 4096;

    private final MainActivity activity;
    private final SharedPreferences prefs;

    HubBridge(MainActivity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @JavascriptInterface
    public String get(String key) {
        if (!activity.onHomePage() || key == null) return "";
        return prefs.getString(key, "");
    }

    @JavascriptInterface
    public void set(String key, String value) {
        if (!activity.onHomePage() || key == null || key.length() > MAX_KEY_LENGTH) return;
        if (value != null && value.length() > MAX_VALUE_LENGTH) return;
        prefs.edit().putString(key, value == null ? "" : value).apply();
    }

    /** Opens the saved dashboard address; returns false when none is set or it is not http(s). */
    @JavascriptInterface
    public boolean openDashboard() {
        if (!activity.onHomePage()) return false;
        String url = prefs.getString(KEY_DASHBOARD, "").trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;
        activity.openUrl(url);
        return true;
    }

    /** {"url": "http://<tv ip>:8090", "code": "123456", "running": true, "canWrite": true} for Send a photo. */
    @JavascriptInterface
    public String photoInfo() {
        if (!activity.onHomePage()) return "{}";
        JSONObject o = new JSONObject();
        try {
            String ip = localIp();
            o.put("url", ip == null ? "" : "http://" + ip + ":" + MainActivity.PHOTO_PORT);
            o.put("code", activity.photoServer().code());
            o.put("running", activity.photoServer().isRunning());
            o.put("canWrite", activity.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED);
        } catch (JSONException ignored) {
            // Keys and values are plain strings and booleans.
        }
        return o.toString();
    }

    /** {"url": "http://<tv ip>:8090/r#<token>"} for the phone-remote QR code; the token stays in the # part. */
    @JavascriptInterface
    public String remoteInfo() {
        if (!activity.onHomePage()) return "{}";
        JSONObject o = new JSONObject();
        try {
            String ip = localIp();
            String token = activity.photoServer().remoteToken();
            o.put("url", ip == null ? "" : "http://" + ip + ":" + MainActivity.PHOTO_PORT + "/r#" + token);
            // Phone browsers only allow the camera and microphone on https pages.
            o.put("secureUrl", ip == null || !activity.photoServer().isSecureRunning()
                    ? "" : "https://" + ip + ":" + TlsSupport.HTTPS_PORT + "/r#" + token);
            o.put("running", activity.photoServer().isRunning());
        } catch (JSONException ignored) {
            // Plain strings and booleans only.
        }
        return o.toString();
    }

    /** {"count": cameras Android can use, "usbVideo": a USB video device is plugged in} for the gesture card. */
    @JavascriptInterface
    public String cameraInfo() {
        if (!activity.onHomePage()) return "{}";
        JSONObject o = new JSONObject();
        int count = 0;
        boolean usbVideo = false;
        try {
            CameraManager cm = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            if (cm != null) count = cm.getCameraIdList().length;
        } catch (CameraAccessException | RuntimeException ignored) {
            // No camera service: count stays 0.
        }
        UsbManager usb = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        if (usb != null) {
            for (UsbDevice d : usb.getDeviceList().values()) {
                if (d.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO) usbVideo = true;
                for (int i = 0; i < d.getInterfaceCount(); i++) {
                    if (d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) usbVideo = true;
                }
            }
        }
        try {
            o.put("count", count);
            o.put("usbVideo", usbVideo);
        } catch (JSONException ignored) {
            // Plain numbers and booleans only.
        }
        return o.toString();
    }

    /** Starts gesture control from the TV's own camera; false when there is none. */
    @JavascriptInterface
    public boolean openTvGestures() {
        if (!activity.onHomePage()) return false;
        try {
            CameraManager cm = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null || cm.getCameraIdList().length == 0) return false;
        } catch (CameraAccessException | RuntimeException e) {
            return false;
        }
        activity.runOnUiThread(() -> activity.startActivity(new Intent(activity, TvGestureActivity.class)));
        return true;
    }

    /** Makes every phone that scanned the old QR code stop working; the next QR code is new. */
    @JavascriptInterface
    public void resetRemote() {
        if (activity.onHomePage()) activity.photoServer().resetRemoteToken();
    }

    private static String localIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (SocketException ignored) {
            // No network: the panel says so.
        }
        return null;
    }
}
