package com.induswok.pos;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
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
 * The web app (https://posinduswok.netlify.app/) calls window.IWNativePrint.* :
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

    private static final String APP_URL = "https://posinduswok.netlify.app/";
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
        web.setWebViewClient(new WebViewClient()); // keep all navigation inside the app
        web.addJavascriptInterface(new PrinterBridge(), "IWNativePrint");
        web.loadUrl(APP_URL);
        setContentView(web);
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

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

    @Override protected void onDestroy() {
        closeSocket();
        super.onDestroy();
    }
}
