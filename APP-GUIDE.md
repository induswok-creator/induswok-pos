# 📱 App Guide — install Indus Wok POS like a native app

Live URL: **https://induswok-pos.induswok.workers.dev/**

## Option 1 — Install straight from the browser (30 seconds, recommended)

The site is now a full PWA (manifest + icons + service worker as of v14.3):

- **Android (Chrome):** open the URL → tap **⋮ → Install app** (or "Add to Home screen").
  It installs as a real app: its own icon (🥢 wok), its own window, no browser bar.
- **Windows/Mac (Chrome/Edge):** install icon in the address bar (⊕ / monitor) → **Install**.
- **iPad/iPhone (Safari):** Share → **Add to Home Screen**.

Install it on every device (billing computer, cashier phone, waiter phones) once.

## Option 1.5 — ⭐ IndusWok Printer App (built by GitHub, direct Bluetooth print — no RawBT)

The repo now contains `android-app/` — a tiny Android shell of the POS with a **native
Bluetooth thermal-printer bridge**. KOTs and bills print straight to your paired
ESC/POS printer, and **QR orders auto-print their KOT** to the kitchen.

Setup:
1. On GitHub: repo → **Actions** tab → *set up a workflow yourself* → paste the
   contents of **`APK-BUILD-WORKFLOW.yml`** (in repo root) → Commit
2. Run the workflow once → download artifact **induswok-pos-android** → unzip →
   install `app-debug.apk` on each Android device (allow "install unknown apps")
3. Pair the thermal printer: Android Settings → Bluetooth → pair (PIN usually 0000/1234)
4. Open the IndusWok app → POS **Settings → Printing** → new blue row appears:
   **🤖 IndusWok app printer → Choose Bluetooth printer** → pick it → 🧪 Test print
5. Done: "Print Bill"/KOT buttons and **QR-order auto-KOTs** print silently —
   no dialog, no RawBT.

(In a normal browser the app codes stay inactive — RawBT and Chrome print keep working as before.)

## Option 2 — Real APK for Android (via PWABuilder, ~10 min)

If you want an installable file you can share directly (e.g. via WhatsApp) or later publish on Play Store:

1. Go to <https://www.pwabuilder.com>
2. Enter `https://induswok-pos.induswok.workers.dev` → it scores the PWA (all green with v14.3)
3. Click **Package For Android** → Download → you get a signed **AAB + APK**
4. Share the APK with staff; or upload the AAB to Play Console if you want a Play Store listing (needs a one-time $25 Google developer account)

No coding needed — PWABuilder wraps your live site (TWA). It auto-updates whenever Netlify deploys a new version — staff never need to update the APK manually.

## Tips for the restaurant

- **Billing computer:** install to taskbar; printing works the same (browser print).
- **Android + Bluetooth thermal printer:** keep using RawBT — Settings → Printing → RawBT toggles are per-device.
- **Lock device to the app (counter kiosk):** Android → Settings → Screen pinning, or Guided Access on iPad.
- Updates are automatic: the app fetches fresh code on every open (no-cache headers + service worker = never stale, never offline-blocked).
