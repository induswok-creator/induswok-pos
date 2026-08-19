# 🚀 v14 Deploy Guide — for your `induswok-qr` codebase (Netlify + new Firebase)

Status: ✅ Firebase config already pasted into `firebase-config.js` (project `indus-wok-pos-2026`).
Remaining: ~10 minutes.

---

## Step 1 — Publish the Firestore rules  ← finish this first

1. Firebase Console → project **indus-wok-pos-2026** → **Firestore Database → Rules** tab
2. Delete everything in the editor, paste the entire contents of **`firestore.rules`** (the clean file — no notes in it), click **Publish**.

> The earlier error happened because the file you pasted contained my explanation text
> (`====`, arrows, backticks). `firestore.rules` is pure rules now — it'll publish cleanly.
> ⚠️ The extra collections covered (`induswok_counters`, `induswok_qr_orders`,
> `induswok_table_requests`) are needed by your QR ordering + bill-number counter,
> otherwise they'll silently show permission-denied.

## Step 2 — Replace the code in the `induswok-qr` repo

Upload **all files from this folder** into `github.com/induswok-creator/induswok-qr`, overwriting existing `index.html` and `customer-order.html`:

```
index.html · customer-order.html · migrate-aug.html · firebase-config.js
manifest.webmanifest · icon-192.png · icon-512.png · netlify.toml · _headers
firestore.rules · .github/
```

Then **DELETE these old files from the repo** (they contain the buggy old build and are still reachable as live pages today — anyone opening them syncs old data):
`index.html12`, `index.html13`, `index.htmlfresh`, `indexlasttime`

Also retire the other repo: in `induswok-creator/mainprinting` → **Settings → Pages → None** (stops https://induswok-creator.github.io/mainprinting/).

## Step 3 — Netlify

1. <https://app.netlify.com> → **Add new site → Import an existing project → GitHub** → `induswok-qr`
2. No build command needed (netlify.toml included) → **Deploy**
3. Site name: set something like `induswok-pos` → your URL: `https://induswok-pos.netlify.app`

Every future `git push` auto-deploys. (The `.github/workflows` file is an optional extra — not needed with Step 3.)

## Step 4 — First run (do it once, carefully)

The new Firebase database starts **empty**. Seed it from the device that has your best/current data (the main billing phone):

1. Any device: open the Netlify URL → log in (Owner 1111 / Cashier 2222 / Waiter 3333 / Partner 4444)
2. On the **main device** → Settings → Sync Doctor → **☁️ Sync Now / Upload** → it will ask "No cloud database exists… Initialize it from THIS device?" → **Yes**
3. Other devices then open the site (+ refresh once) → they receive the menu/tables from the cloud automatically
4. If your real menu isn't on that main device, just re-upload your menu Excel on any device afterward (Menu Management)

Watch the ☁️ badge: it should say **live**.

## Step 4.5 — Migrate your data from 1 Aug (one-time, ~3 min)

Fresh start for everything, and only records **dated 1 Aug 2026 onward** come over.

1. Wait until Step 4 (cloud initialized) is done.
2. On your **main billing device**, open the OLD app → **Settings → ⬇️ Export Backup (.json)** → save the file (it has everything, including archived bills that are no longer in the old cloud).
3. Open **`https://YOUR-SITE.netlify.app/migrate-aug.html`**
4. Pick the backup file → keep "Also merge from OLD Firebase cloud" ON → check the preview counts (bills, expenses, payroll, sessions, customers)
5. Click **🚀 Migrate** → done. Open the POS and tap the ☁️ badge once — data appears everywhere.

What it moves (deduped, safe to run twice): bills (live + archived), expenses, payroll payments, day sessions, customers, and optionally vendor ledger / attendance / deductions (off by default — edit the checkboxes in the file if you want them; default switches are in the `COLLECTIONS` list).

Note: bills older than ~7 days auto-move into each device's local **Bill Archive** as the POS runs (by design, keeps sync fast) — in Reports tick **Include Archived Bills** to see/print them.

## Step 5 — Install as an app on each device

- **Android Chrome** → ⋮ → **Install app / Add to Home screen**
- **iPad/iPhone Safari** → Share → **Add to Home Screen**
- **Computer Chrome/Edge** → install icon in address bar

## Step 6 — Reprint table QR stickers

Old printed QR stickers point to `…github.io/induswok-qr/customer-order.html`. QR generation is domain-relative, so **Tables → QR menu** on the Netlify site prints fresh stickers for the new domain — replace the stickers on tables.

## Migration checklist

| ✔ | |
|---|---|
| ☐ | firestore.rules published |
| ☐ | v14 files uploaded; 4 legacy index* files deleted from repo |
| ☐ | mainprinting Pages disabled |
| ☐ | Netlify live; ☁️ live badge on devices |
| ☐ | Cloud initialized from the main device |
| ☐ | 1-Aug data migrated via migrate-aug.html |
| ☐ | Menu looks right on 2 devices; a test item added on one appears on the other **and stays** |
| ☐ | Settings change → other device keep it after refresh (the old revert bug) |
| ☐ | Test settle a bill → table clears, Print Station clean |
| ☐ | New table QR stickers printed & placed |
| ☐ | (Later) old Firebase project `induswokpos` can be deleted — nothing points to it anymore |
