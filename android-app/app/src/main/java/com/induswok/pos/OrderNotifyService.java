package com.induswok.pos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Zomato-style always-on order watcher (v14.6).
 * Foreground service polls Firestore REST every 20s for NEW qr orders and posts
 * a loud notification even when the app is closed/background. Kept tiny: plain
 * HttpURLConnection + framework org.json, no external dependencies.
 *
 * IMPORTANT: PROJECT_ID / API_KEY below must mirror firebase-config.js.
 */
public class OrderNotifyService extends Service {

    private static final String PROJECT_ID = "indus-wok-pos-2026";
    private static final String API_KEY    = "AIzaSyA9_tfiGyoXooRrw5rr1P6nHih9_AOkZMg";
    private static final String COLLECTION = "induswok_qr_orders";

    private static final String CH_SVC = "iw_service";
    private static final String CH_ORDERS = "iw_orders_v3"; // Android freezes channel settings after first creation — bump id whenever sound/vibe change
    private static final int FG_ID = 4071;
    private static final long POLL_OK_MS = 20000;   // 20s
    private static final long POLL_ERR_MS = 60000;  // backoff on error

    public static final String ACTION_STOP_SIREN = "com.induswok.pos.STOP_SIREN";
    public static final String ACTION_TEST_ALERT = "com.induswok.pos.TEST_ALERT";

    // tiny on-device diagnostics (readable via the IWNativePrint bridge)
    public static volatile String diag = "service not started yet";

    private Handler handler;                 // main thread — siren timing only
    private android.os.HandlerThread bgThread;
    private Handler bgHandler;               // v15.2: network polling MUST run here
    private PowerManager.WakeLock wakeLock;
    private boolean running = false;
    private android.media.MediaPlayer siren;
    private long sirenStopAt = 0;

    private SharedPreferences prefs() { return getSharedPreferences("iw_pos_prefs", MODE_PRIVATE); }

    @Override public IBinder onBind(Intent i) { return null; }

    private Notification.Builder builderFor(String channel) {
        // NotificationChannel-aware builder exists only on API 26+; older devices
        // use the classic constructor and get sound/vibration from setDefaults.
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(this, channel);
        return new Notification.Builder(this);
    }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) makeChannels();
        startForeground(FG_ID, buildSvcNotification());
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "induswok:orderwatch");
        wakeLock.setReferenceCounted(false);
        handler = new Handler(getMainLooper());
        running = true;
        diag = "service started " + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        prepareSiren();
        // v15.2: poll on a background thread — Handler(mainLooper) caused
        // NetworkOnMainThreadException ("poll FAILED: null") and zero alerts.
        bgThread = new android.os.HandlerThread("iw-poll");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        bgHandler.postDelayed(pollTask, 2500); // first poll shortly after start (marks baseline, no alert flood)
    }

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long next = POLL_OK_MS;
            try {
                if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(60 * 1000);
                pollOnce();
            } catch (Exception e) {
                Log.w("IW", "poll failed: " + e.getMessage());
                diag = "poll FAILED: " + e.getClass().getSimpleName() + " " + String.valueOf(e.getMessage());
                next = POLL_ERR_MS;
            } finally {
                try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
                bgHandler.postDelayed(this, next);
            }
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_SIREN.equals(intent.getAction())) {
            stopSiren();
            return START_STICKY;
        }
        if (intent != null && ACTION_TEST_ALERT.equals(intent.getAction())) {
            try { showOrderNotification("\ud83d\udd14 Test: new QR order", "\u2022 2 items \u2014 Rs.999 (Test Customer)", 1, 999); }
            catch (Throwable t) { diag = "test alert failed: " + t.getMessage(); }
            return START_STICKY;
        }
        return START_STICKY; // system restarts us if killed
    }

    // ---------- siren (rings over any app / silent mode / lock screen) ----------
    private void prepareSiren() {
        // v14.9: prepare the player ONCE at service start — creating it inside the
        // alert path raced/failed on some devices, which is why it "beeped once".
        stopSiren();
        try {
            siren = android.media.MediaPlayer.create(this, com.induswok.pos.R.raw.order_alarm);
            if (siren == null) return;
            siren.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM).build()); // alarm stream ≠ silent mode
            siren.setLooping(true);
        } catch (Exception ignored) {}
    }

    private volatile boolean sirenPlaying = false;

    private void startSiren() {
        if (sirenPlaying) return;
        boolean ok = false;
        try {
            if (siren == null) prepareSiren();
            if (siren != null) {
                siren.start();
                ok = siren.isPlaying();
            }
        } catch (Exception e) { prepareSiren(); }
        sirenPlaying = ok;
        if (!ok) fallbackSiren();   // v15: guaranteed voice even if the ROM breaks MediaPlayer
        sirenStopAt = System.currentTimeMillis() + 60 * 1000;
        handler.postDelayed(this::stopSirenIfOld, 60 * 1000);
    }

    // Backup siren: raw alarm-stream tones — works on practically every Android build.
    private void fallbackSiren() {
        sirenPlaying = true;
        new Thread(() -> {
            android.media.ToneGenerator tg = null;
            try {
                tg = new android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100);
                long end = System.currentTimeMillis() + 55000;
                while (sirenPlaying && System.currentTimeMillis() < end) {
                    try { tg.startTone(android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 700); } catch (Throwable ignored) {}
                    try { Thread.sleep(750); } catch (InterruptedException ignored) {}
                }
            } catch (Throwable ignored) {}
            try { if (tg != null) tg.release(); } catch (Throwable ignored) {}
        }).start();
    }

    private void stopSirenIfOld() {
        if (System.currentTimeMillis() >= sirenStopAt) stopSiren();
    }

    private synchronized void stopSiren() {
        sirenPlaying = false;
        try { if (siren != null) { if (siren.isPlaying()) siren.pause(); siren.seekTo(0); siren.release(); } } catch (Exception ignored) {}
        siren = null;
    }

    private void wakeScreen() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                    "induswok:orderwake");
            wl.acquire(3000);
        } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        running = false;
        stopSiren();
        closePrinter();
        try { if (handler != null) handler.removeCallbacks(pollTask); } catch (Exception ignored) {}
        try { if (bgHandler != null) bgHandler.removeCallbacks(pollTask); } catch (Exception ignored) {}
        try { if (bgThread != null) bgThread.quitSafely(); } catch (Exception ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ---------- polling ----------
    private void pollOnce() throws Exception {
        pollPrintJobs();   // v15.3: remote "print this at the restaurant" jobs
        String url = "https://firestore.googleapis.com/v1/projects/" + PROJECT_ID
                + "/databases/(default)/documents/" + COLLECTION
                + "?key=" + API_KEY + "&pageSize=3&orderBy=createdAt%20desc";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(10000);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String body = is == null ? "" : readAll(is);
        c.disconnect();
        if (code != 200) throw new RuntimeException("firestore http " + code);

        JSONObject root = new JSONObject(body);
        JSONArray docs = root.optJSONArray("documents");
        long lastSeen = prefs().getLong("last_order_ms", -1);
        if (docs != null && docs.length() == 0) diag = "poll OK, inbox empty · " + hhmmss(System.currentTimeMillis());

        if (docs == null || docs.length() == 0) {
            if (lastSeen < 0) prefs().edit().putLong("last_order_ms", System.currentTimeMillis()).apply();
            return;
        }

        long newest = lastSeen;
        JSONArray fresh = new JSONArray();
        for (int i = 0; i < docs.length(); i++) {
            JSONObject d = docs.getJSONObject(i);
            JSONObject f = d.optJSONObject("fields"); if (f == null) continue;
            long ts = orderTs(f);
            if (lastSeen < 0) {
                // baseline run — just mark, don't alert old stuff
                newest = Math.max(newest, ts); continue;
            }
            // v14.9: do NOT filter by status. Table QR orders get auto-accepted by an
            // open POS within a second, so checking status==new races it and silently
            // skipped alerts. Any newly created order doc = a real new order.
            if (ts > lastSeen) fresh.put(f);
            newest = Math.max(newest, ts);
        }
        if (lastSeen < 0) { prefs().edit().putLong("last_order_ms", Math.min(newest, System.currentTimeMillis())).apply(); diag="baseline set "+hhmmss(Math.min(newest,System.currentTimeMillis()))+" · docs:"+docs.length(); return; }
        if (fresh.length() > 0) {
            prefs().edit().putLong("last_order_ms", newest).apply();
            diag = "ALERT "+fresh.length()+" new · newest "+hhmmss(newest);
            alert(fresh);
        } else {
            diag = "poll OK docs:"+docs.length()+" · marker "+hhmmss(lastSeen)+" · newest "+hhmmss(newest);
            prefs().edit().putLong("last_order_ms", Math.max(lastSeen, 0)).apply();
        }
    }

    private static long orderTs(JSONObject f) {
        // v15.1: SERVER timestamp first (Firestore assigns it) — customer phones with
        // wrong clocks used to poison the comparison marker and kill all later alerts.
        // createdAtMs (client clock) is only a fallback, and future dates are clamped.
        long now = System.currentTimeMillis();
        try {
            JSONObject ts = f.optJSONObject("createdAt");
            if (ts != null && ts.has("timestampValue")) {
                String s = ts.getString("timestampValue"); // e.g. 2026-08-19T01:44:46.082Z
                java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
                df.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.util.Date d = df.parse(s.substring(0, 19));
                if (d != null) return Math.min(d.getTime(), now + 120000);
            }
            if (f.has("createdAtMs")) return Math.min((long) f.getJSONObject("createdAtMs").getDouble("numberValue"), now + 120000);
        } catch (Exception ignored) {}
        return 0;
    }

    private static String optStr(JSONObject f, String k) {
        try { return f.getJSONObject(k).getString("stringValue"); } catch (Exception e) { return ""; }
    }
    private static double optNum(JSONObject f, String k) {
        try { return f.getJSONObject(k).getDouble("numberValue"); } catch (Exception e) { return 0; }
    }

    private void alert(JSONArray fresh) {
        StringBuilder body = new StringBuilder();
        double total = 0; int count = 0; String where = "";
        for (int i = 0; i < fresh.length(); i++) {
            JSONObject f = fresh.optJSONObject(i); if (f == null) continue;
            JSONArray items = null;
            try { items = f.getJSONObject("items").getJSONObject("arrayValue").getJSONArray("values"); } catch (Exception ignored) {}
            int itemsCount = items != null ? items.length() : 0;
            total += optNum(f, "total");
            count++;
            if (where.isEmpty()) where = optStr(f, "table");
            if (where.isEmpty()) where = optStr(f, "deliveryPartner");
            body.append("• ").append(itemsCount).append(" items — Rs.").append((long) optNum(f, "total"));
            String who = optStr(f, "customerName"); if (!who.isEmpty()) body.append(" (").append(who).append(")");
            body.append("\n");
        }
        showOrderNotification(
                "🔔 New QR order" + (where.isEmpty() ? "" : " · " + where),
                body.toString().trim(),
                count,
                total);
    }

    // ---------- notifications ----------
    private void makeChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel svc = new NotificationChannel(CH_SVC, "Order watcher", NotificationManager.IMPORTANCE_MIN);
        svc.setDescription("Keeps the app listening for new QR orders in the background");
        nm.createNotificationChannel(svc);
        NotificationChannel ord = new NotificationChannel(CH_ORDERS, "New orders", NotificationManager.IMPORTANCE_HIGH);
        ord.setDescription("Loud siren alerts when a customer places a QR order");
        // Siren tone on the ALARM audio stream → loud even when the phone is on silent.
        android.media.AudioAttributes alarmAttr = new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        ord.setSound(android.net.Uri.parse("android.resource://" + getPackageName() + "/raw/order_alarm"), alarmAttr);
        // Long, unmistakable buzz-buzz-buzz.
        long[] vibes = new long[]{0, 600, 250, 600, 250, 600, 250, 900};
        ord.enableVibration(true);
        ord.setVibrationPattern(vibes);
        ord.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ord.setBypassDnd(true);
        nm.createNotificationChannel(ord);
    }

    private Notification buildSvcNotification() {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = builderFor(CH_SVC)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("🥢 IndusWok — listening for orders")
                .setContentText("New QR orders will ring here automatically")
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }

    private void showOrderNotification(String title, String body, int count, double total) {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 2, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // v14.9: simple WhatsApp-style notification — heads-up banner, loud alarm
        // sound, strong vibration, visible on lock screen. No full-screen takeover.
        Notification.Builder b = builderFor(CH_ORDERS)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setSubText("Total Rs." + (long) total + (count > 1 ? " · " + count + " orders" : ""))
                .setContentIntent(pi)
                .setAutoCancel(false)      // v15: stays in the notification panel like WhatsApp messages
                .setOngoing(false)         // but swipe-to-dismiss works
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_LIGHTS)
                .setSound(android.net.Uri.parse("android.resource://" + getPackageName() + "/raw/order_alarm"))
                .setVibrate(new long[]{0, 600, 250, 600, 250, 600, 250, 900});
        if (Build.VERSION.SDK_INT >= 21) b.setVisibility(Notification.VISIBILITY_PUBLIC); // lock screen
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify((int) (System.currentTimeMillis() % 100000), b.build());
        startSiren();  // siren keeps ringing ~60s until staff opens the app
        wakeScreen();
    }

    // ---------- v15.3: remote print jobs (owner prints KOT from home) ----------
    private static final String JOBS_COLLECTION = "induswok_print_jobs";

    private void pollPrintJobs() {
        try {
            String url = "https://firestore.googleapis.com/v1/projects/" + PROJECT_ID
                    + "/databases/(default)/documents/" + JOBS_COLLECTION
                    + "?key=" + API_KEY + "&pageSize=5&orderBy=createdAt%20desc";
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(10000);
            int code = c.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? c.getInputStream() : null;
            if (is == null) { c.disconnect(); return; }
            String body = readAll(is); c.disconnect();
            JSONObject root = new JSONObject(body);
            JSONArray docs = root.optJSONArray("documents");
            if (docs == null) return;
            long nowMs = System.currentTimeMillis();
            for (int i = 0; i < docs.length(); i++) {
                JSONObject d = docs.getJSONObject(i);
                JSONObject f = d.optJSONObject("fields"); if (f == null) continue;
                String status = optStr(f, "status");
                if ("done".equals(status)) continue;
                long ts = orderTs(f);
                if (ts > 0 && nowMs - ts > 20 * 60 * 1000) { markJobDone(d.getString("name")); continue; } // stale job — drop quietly
                String text = optStr(f, "text");
                String table = optStr(f, "table");
                if (text.isEmpty()) { markJobDone(d.getString("name")); continue; }
                boolean printed = printerWrite(text);
                if (printed) {
                    markJobDone(d.getString("name"));
                    quietNotify("🖨️ Remote KOT printed" + (table.isEmpty() ? "" : " — " + table));
                    diag = "printed remote job " + hhmmss(nowMs);
                } else {
                    diag = "remote print failed (printer?) " + hhmmss(nowMs);
                }
            }
        } catch (Exception e) {
            Log.w("IW", "print-jobs poll: " + e.getMessage());
        }
    }

    private void markJobDone(String docName) {
        HttpURLConnection c = null;
        try {
            String url = "https://firestore.googleapis.com/v1/" + docName
                    + "?key=" + API_KEY + "&currentDocument.exists=true"
                    + "&updateMask.fieldPaths=status&updateMask.fieldPaths=printedAt";
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("PATCH");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setDoOutput(true);
            String json = "{\"fields\":{\"status\":{\"stringValue\":\"done\"},\"printedAt\":{\"integerValue\":\"" + System.currentTimeMillis() + "\"}}}";            byte[] out = json.getBytes("UTF-8");
            c.getOutputStream().write(out);
            c.getResponseCode();
        } catch (Exception ignored) {} finally { if (c != null) c.disconnect(); }
    }

    // ---------- bluetooth thermal write (paired printer chosen in the POS app) ----------
    private android.bluetooth.BluetoothSocket pjSock;
    private java.io.OutputStream pjOut;

    private boolean printerWrite(String text) {
        String addr = getSharedPreferences("iw_pos_prefs", MODE_PRIVATE).getString("printer_addr", null);
        if (addr == null) return false;
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        try {
            boolean alive = pjSock != null && pjSock.isConnected() && pjSock != null && pjOut != null;
            if (!alive) {
                closePrinter();
                android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                if (ad == null || !ad.isEnabled()) return false;
                android.bluetooth.BluetoothDevice dev = ad.getRemoteDevice(addr);
                try { ad.cancelDiscovery(); } catch (Exception ignored) {}
                pjSock = dev.createRfcommSocketToServiceRecord(java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
                pjSock.connect();
                pjOut = pjSock.getOutputStream();
            }
            pjOut.write(text.getBytes("ISO-8859-1"));
            pjOut.flush();
            return true;
        } catch (Exception e) { closePrinter(); return false; }
    }

    private void closePrinter() {
        try { if (pjOut != null) pjOut.close(); } catch (Exception ignored) {}
        try { if (pjSock != null) pjSock.close(); } catch (Exception ignored) {}
        pjSock = null; pjOut = null;
    }

    private void quietNotify(String msg) {
        try {
            Notification.Builder b = builderFor(CH_SVC)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(msg)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify((int) (System.currentTimeMillis() % 100000), b.build());
        } catch (Exception ignored) {}
    }

    private static String hhmmss(long t){ return t<=0?"—":new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(t)); }

    private static String readAll(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line; while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }
}
