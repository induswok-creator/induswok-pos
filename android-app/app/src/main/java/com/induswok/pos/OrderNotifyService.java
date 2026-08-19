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
    private static final String CH_ORDERS = "iw_orders_v2"; // v14.9: channel settings freeze after creation — bump id to apply alarm sound/vibration
    private static final int FG_ID = 4071;
    private static final long POLL_OK_MS = 20000;   // 20s
    private static final long POLL_ERR_MS = 60000;  // backoff on error

    public static final String ACTION_STOP_SIREN = "com.induswok.pos.STOP_SIREN";

    private Handler handler;
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
        prepareSiren();
        handler.postDelayed(pollTask, 2500); // first poll shortly after start (marks baseline, no alert flood)
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
                next = POLL_ERR_MS;
            } finally {
                try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
                handler.postDelayed(this, next);
            }
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_SIREN.equals(intent.getAction())) {
            stopSiren();
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

    private void startSiren() {
        try {
            if (siren == null) prepareSiren();
            if (siren == null) return;
            if (siren.isPlaying()) return;
            siren.start();
            sirenStopAt = System.currentTimeMillis() + 60 * 1000; // auto-quiet safety
            handler.postDelayed(this::stopSirenIfOld, 60 * 1000);
        } catch (Exception e) { prepareSiren(); }
    }

    private void stopSirenIfOld() {
        if (System.currentTimeMillis() >= sirenStopAt) stopSiren();
    }

    private synchronized void stopSiren() {
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
        try { if (handler != null) handler.removeCallbacks(pollTask); } catch (Exception ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ---------- polling ----------
    private void pollOnce() throws Exception {
        String url = "https://firestore.googleapis.com/v1/projects/" + PROJECT_ID
                + "/databases/(default)/documents/" + COLLECTION
                + "?key=" + API_KEY + "&pageSize=3&orderBy=createdAt%20desc";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(10000);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String body = readAll(is);
        c.disconnect();
        if (code != 200) throw new RuntimeException("firestore http " + code);

        JSONObject root = new JSONObject(body);
        JSONArray docs = root.optJSONArray("documents");
        long lastSeen = prefs().getLong("last_order_ms", -1);

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
        if (lastSeen < 0) { prefs().edit().putLong("last_order_ms", newest).apply(); return; }
        if (fresh.length() > 0) {
            prefs().edit().putLong("last_order_ms", newest).apply();
            alert(fresh);
        } else {
            prefs().edit().putLong("last_order_ms", Math.max(lastSeen, 0)).apply();
        }
    }

    private static long orderTs(JSONObject f) {
        try {
            if (f.has("createdAtMs")) return (long) f.getJSONObject("createdAtMs").getDouble("numberValue");
            JSONObject ts = f.optJSONObject("createdAt");
            if (ts != null && ts.has("timestampValue")) {
                String s = ts.getString("timestampValue"); // e.g. 2026-08-19T01:44:46.082Z
                java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
                df.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.util.Date d = df.parse(s.substring(0, 19));
                return d == null ? 0 : d.getTime();
            }
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
                .setAutoCancel(true)
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

    private static String readAll(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line; while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }
}
