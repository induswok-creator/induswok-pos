package com.induswok.pos;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

/**
 * IndusWok POS — WebView shell + direct Bluetooth ESC/POS printing bridge.
 *
 * The web app (https://induswok-pos.induswok.workers.dev/) calls window.IWNativePrint.* :
 *   available()            -> bridge present (always true inside the app)
 *   status()               -> current printer name or ""
 *   print(escPosText)      -> prints raw ESC/POS text; returns true on success
 *   pickPrinter()          -> system dialog listing PAIRED Bluetooth printers
 *   test()                 -> prints a small test ticket
 *
 * Receipt text arrives pre-formatted (ESC/POS control characters + plain text;
 * '₹' is replaced with 'Rs.' by the web side because thermal fonts lack it).
 */
public class MainActivity extends Activity {

    private static final String APP_URL = "https://induswok-pos.induswok.workers.dev/";
    private static final String PREFS = "iw_pos_prefs";
    private static final String KEY_ADDR = "printer_addr";
    private static final String KEY_NAME = "printer_name";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private WebView web;
    private BluetoothSocket socket;
    private OutputStream sockOut;
    private String currentAddr = null;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestBtPermissionIfNeeded();

        currentAddr = prefs().getString(KEY_ADDR, null);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage — required by the POS
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE); // always show latest POS/WhatsApp fixes
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleExternalNavigation(request == null || request.getUrl() == null ? null : request.getUrl().toString());
            }
            @SuppressWarnings("deprecation")
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternalNavigation(url);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                try {
                    WebView.HitTestResult hit = view == null ? null : view.getHitTestResult();
                    String extra = hit == null ? null : hit.getExtra();
                    if (handleExternalNavigation(extra)) return false;
                } catch (Throwable ignored) {}
                return false; // keep popups out of the POS WebView
            }
        });
        web.addJavascriptInterface(new PrinterBridge(), "IWNativePrint");
        try { web.clearCache(true); } catch (Throwable ignored) {}
        web.loadUrl(APP_URL);
        setContentView(web);

        requestNotifPermissionIfNeeded();
        startOrderWatcher(); // always-on QR order alerts (Zomato-style)
    }

    // ---------- background order alerts ----------
    private void startOrderWatcher() {
        try {
            Intent svc = new Intent(this, OrderNotifyService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        } catch (Throwable t) { android.util.Log.w("IW", "svc start: " + t.getMessage()); }
    }

    private void requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        }
    }

    private void promptBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            String pkg = getPackageName();
            if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + pkg)));
                toastOnUi("Pick ALLOW so order alerts keep working in background");
            }
        } catch (Throwable ignored) {}
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    // ---------- external app links (WhatsApp / UPI) ----------
    private boolean handleExternalNavigation(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        try {
            url = url.replace("&amp;", "&").trim();
            Uri u = Uri.parse(url);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
            boolean isWhatsAppHost = host.equals("wa.me") || host.equals("api.whatsapp.com") || host.endsWith(".whatsapp.com");
            boolean isExternal = scheme.equals("whatsapp") || scheme.equals("upi") || scheme.equals("intent") || isWhatsAppHost;
            if (!isExternal) return false; // normal POS pages stay inside the app
            openExternalUrl(url, true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean openExternalUrl(final String rawUrl, final boolean showError) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) return false;
        String url = rawUrl.replace("&amp;", "&").trim();
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();

            if (scheme.equals("intent")) {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                try { startActivity(intent); return true; }
                catch (Throwable ignored) {
                    String fallback = intent.getStringExtra("browser_fallback_url");
                    if (fallback != null && !fallback.isEmpty()) return openExternalUrl(fallback, showError);
                    return openWhatsAppUrl(url, showError);
                }
            }

            if (scheme.equals("whatsapp") || host.equals("wa.me") || host.equals("api.whatsapp.com") || host.endsWith(".whatsapp.com")) {
                return openWhatsAppUrl(url, showError);
            }

            if (scheme.equals("upi")) {
                Intent i = new Intent(Intent.ACTION_VIEW, uri);
                startActivity(i);
                return true;
            }

            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(i);
            return true;
        } catch (Throwable t) {
            if (showError) toastOnUi("Install WhatsApp / UPI app first");
            return false;
        }
    }

    private boolean openWhatsAppUrl(final String rawUrl, final boolean showError) {
        try {
            String url = rawUrl == null ? "" : rawUrl.replace("&amp;", "&").trim();
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            String phone = "";
            String text = "";

            if (scheme.equals("whatsapp")) {
                phone = uri.getQueryParameter("phone");
                text = uri.getQueryParameter("text");
            } else if (host.equals("wa.me")) {
                String path = uri.getPath() == null ? "" : uri.getPath();
                phone = path.replaceAll("[^0-9]", "");
                text = uri.getQueryParameter("text");
            } else if (host.equals("api.whatsapp.com") || host.endsWith(".whatsapp.com")) {
                phone = uri.getQueryParameter("phone");
                text = uri.getQueryParameter("text");
            }

            if (phone == null) phone = "";
            phone = phone.replaceAll("[^0-9]", "");
            if (text == null) text = "";

            String wa = "whatsapp://send" + (phone.length() > 0 ? "?phone=" + phone + "&text=" + Uri.encode(text) : "?text=" + Uri.encode(text));
            Intent base = new Intent(Intent.ACTION_VIEW, Uri.parse(wa));

            // Try regular WhatsApp, then WhatsApp Business, then generic chooser.
            String[] packages = new String[]{"com.whatsapp", "com.whatsapp.w4b", null};
            Throwable last = null;
            for (String pkg : packages) {
                try {
                    Intent i = new Intent(base);
                    if (pkg != null) i.setPackage(pkg);
                    startActivity(i);
                    return true;
                } catch (Throwable t) { last = t; }
            }
            if (showError) toastOnUi("WhatsApp not found — install WhatsApp first");
            return false;
        } catch (Throwable t) {
            if (showError) toastOnUi("Could not open WhatsApp");
            return false;
        }
    }

    // ---------- Bluetooth permission (Android 12+) ----------
    private void requestBtPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 41);
        }
    }

    // ---------- printing ----------
    private boolean hasBtPermission() {
        return Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private synchronized boolean ensureConnected() {
        try {
            if (socket != null && socket.isConnected() && sockOut != null) return true;
        } catch (Exception ignored) {}
        closeSocket();
        if (currentAddr == null || currentAddr.isEmpty()) return false;
        if (!hasBtPermission()) { requestBtPermissionIfNeeded(); return false; }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return false;
            BluetoothDevice dev = adapter.getRemoteDevice(currentAddr);
            try { adapter.cancelDiscovery(); } catch (Exception ignored) {}
            socket = dev.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
            sockOut = socket.getOutputStream();
            return true;
        } catch (Exception e) {
            closeSocket();
            return false;
        }
    }

    private synchronized void closeSocket() {
        try { if (sockOut != null) sockOut.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        sockOut = null; socket = null;
    }

    private boolean doPrint(String text) {
        if (!ensureConnected()) return false;
        try {
            // The web app emits ESC/POS bytes as code points 0-255 inside a JS string.
            // ISO-8859-1 is a 1:1 byte mapping for those, so no mangling.
            byte[] data = text.getBytes("ISO-8859-1");
            sockOut.write(data);
            sockOut.flush();
            return true;
        } catch (Exception e) {
            closeSocket();
            return false;
        }
    }

    // ---------- JS bridge ----------
    class PrinterBridge {
        @JavascriptInterface public boolean available() { return true; }

        @JavascriptInterface public String status() {
            String n = prefs().getString(KEY_NAME, "");
            return n == null ? "" : n;
        }

        @JavascriptInterface public boolean print(String text) {
            boolean ok = doPrint(text);
            if (!ok) toastOnUi("🖨️ Printer not connected — choose it in Settings");
            return ok;
        }

        @JavascriptInterface public boolean test() {
            // char constants via unicode escapes keep this file pure-ASCII
            String ESC = "\u001B", GS = "\u001D";
            String t = ESC + "@"
                    + ESC + "a\u0001" + ESC + "E\u0001" + ESC + "!\u0011"
                    + "*** TEST PRINT OK ***" + ESC + "!\u0000" + ESC + "E\u0000" + "\n"
                    + ESC + "a\u0000"
                    + "IndusWok POS app bridge active.\n"
                    + "Bluetooth direct - no RawBT needed.\n\n\n"
                    + GS + "V\u0042\u0000";
            return doPrint(t);
        }

        @JavascriptInterface public void pickPrinter() { showPrinterPicker(); }

        /** Open WhatsApp / UPI / other external app links outside the POS WebView. */
        @JavascriptInterface public boolean openExternal(final String url) {
            runOnUiThread(() -> openExternalUrl(url, true));
            return true;
        }

        /** "🔔 Enable Alerts" button inside the POS lands here in the app. */
        @JavascriptInterface public boolean enableOrderAlerts() {
            requestNotifPermissionIfNeeded();
            startOrderWatcher();
            promptBatteryExemption();
            toastOnUi("🔔 Order alerts ON — works even when the app is closed");
            return true;
        }

        /** Test: fire a demo notification + siren right now. */
        @JavascriptInterface public boolean testOrderAlert() {
            startOrderWatcher();
            try {
                Intent i = new Intent(MainActivity.this, OrderNotifyService.class);
                i.setAction(OrderNotifyService.ACTION_TEST_ALERT);
                startService(i);
                return true;
            } catch (Throwable t) { toastOnUi("service not running: " + t.getMessage()); return false; }
        }

        /** Diagnostics line for the POS help screen. */
        @JavascriptInterface public String alertStatus() {
            String notif;
            if (Build.VERSION.SDK_INT >= 33) {
                notif = (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                        ? "notifications: granted" : "notifications: DENIED — enable in Android settings";
            } else notif = "notifications: pre-33 (granted)";
            return OrderNotifyService.diag + " · " + notif;
        }
    }

    private void toastOnUi(final String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void showPrinterPicker() {
        runOnUiThread(() -> {
            if (!hasBtPermission()) { requestBtPermissionIfNeeded(); return; }
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                Toast.makeText(this, "Turn Bluetooth ON first", Toast.LENGTH_LONG).show();
                return;
            }
            Set<BluetoothDevice> bonded;
            try { bonded = adapter.getBondedDevices(); }
            catch (SecurityException se) { requestBtPermissionIfNeeded(); return; }
            ArrayList<String> names = new ArrayList<>();
            ArrayList<String> addrs = new ArrayList<>();
            if (bonded != null) for (BluetoothDevice d : bonded) {
                try { names.add(d.getName() + "\n" + d.getAddress()); addrs.add(d.getAddress()); }
                catch (SecurityException ignored) {}
            }
            if (addrs.isEmpty()) {
                Toast.makeText(this, "No paired printers. Pair the thermal printer in Android Settings → Bluetooth first.", Toast.LENGTH_LONG).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Choose thermal printer")
                    .setItems(names.toArray(new String[0]), (dlg, which) -> {
                        String addr = addrs.get(which);
                        String name = names.get(which).split("\n")[0];
                        prefs().edit().putString(KEY_ADDR, addr).putString(KEY_NAME, name).apply();
                        currentAddr = addr;
                        closeSocket();
                        Toast.makeText(this, "Printer saved: " + name, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    // ---------- lifecycle ----------
    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        // staff saw the order → silence the siren
        try {
            Intent stop = new Intent(this, OrderNotifyService.class);
            stop.setAction(OrderNotifyService.ACTION_STOP_SIREN);
            startService(stop);
        } catch (Throwable ignored) {}
    }

    @Override protected void onDestroy() {
        closeSocket();
        super.onDestroy();
    }
}
