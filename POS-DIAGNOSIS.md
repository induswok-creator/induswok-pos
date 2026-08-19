# Indus Wok POS — Full Diagnosis Report
**Analyzed:** https://induswok-creator.github.io/mainprinting/ (index.html, v12.3, 4,017 lines)
**Date:** 2026-08-19

---

## 🔴 CRITICAL — Why your settings revert & old data comes back

### C1. The sync treats the ENTIRE database as one document, last-writer-wins
Every change any device makes (even a +1 qty tap) re-uploads your **whole database** — all bills, menu, settings, staff, carts, KOTs — as one JSON blob into a single Firestore document (`pos/induswok-default`) with `merge:false`.

In `applyRemote()`, these collections are **wholesale replaced** by whatever arrived, NOT merged:
`bills`, `customers`, `menu`, `settings`, `kots`, `tables`, `inventory`, `sessions`, `attendance`, `recipes`, `deductions`
(only vendors, staff, payments, expenses merge by id).

**Consequence:** if device B's snapshot is even slightly stale when it lands after your change, your newer settings/menu/bills are silently overwritten. Two devices busy at the same time = one device's work vanished.

### C2. The revision counter (`_rev`) is broken — this is the *exact* settings-revert bug
- `_rev` lives only in memory, **starts at 0 on every page load**, and every device counts its own revs.
- Two devices collide: A saves settings → pushes `rev 106`. B (stale, at 105) taps anything → pushes its OLD data, also as `rev 106`. Firestore keeps B's stale doc labelled 106. A's listener sees `106 > 106 = false` → never re-applies.
- Next time **any** device reloads the page, `boot()` force-pulls with `_rev=0` → the cloud's stale copy (with the OLD settings) overwrites everything → **settings "revert back"**.
- Same mechanism resurrects old menus, old tables, old user PINs.

### C3. Automatic menu replacement on version mismatch — the "old menu loads itself" bug
In `load()`:
```js
if(d.menuVersion!==MENU_VERSION){ d.menu=seed(); ... window.__menuUpgraded=true }
```
`boot()` then **pushes that hardcoded demo menu to the cloud**, overwriting your real menu on *every* device. Triggers:
- any device running an older/newer cached copy of the page (GitHub Pages caches aggressively; your repo history has index1→index12 renames, so different devices may run different versions),
- pressing **🔧 Fix Login** (`localStorage.clear()` wipes everything → reseeds the demo menu → pushes it),
- any `menuVersion` drift between app builds.

### C4. Every page reload wipes unpushed local work
`boot()` → `firebasePull({force:true})` with `_rev=0` always applies the cloud snapshot over local state. Anything saved while the network was weak/offline is discarded at next refresh instead of being uploaded.

---

## 🔴 CRITICAL — Sync reliability

### C5. The 1 MB Firestore document wall
Firestore allows **max 1 MiB per document**. Your whole restaurant database lives in ONE document (uncompressed JSON). Bills accumulate full item snapshots, so the doc grows hundreds of KB per month. Once it crosses ~1 MB:
- **every push starts failing permanently** (`invalid-argument`) → the permanent "📴 Offline – queued locally" banner, sync badge off, nothing syncs.
- Your auto-archive threshold (3.5 MB local / 1500 live bills) is **above** Firestore's 1 MB limit — the app is designed to hit this wall. First symptom: sync randomly "stops working" after some months.

### C6. Retry-backoff bug — offline devices hammer Firebase every 1s forever
```js
let _pushFail = 0;   // declared INSIDE firebasePush → resets every call
```
The exponential backoff never grows. An offline device retries every ~1 second indefinitely → battery drain, Firestore quota burn, console spam, and once the doc is >1 MB this becomes a permanent 1-error-per-second loop.

### C7. Duplicate global sync hooks — sync storms
`visibilitychange` / `online` / `focus` handlers that call `syncNow()` are registered **twice** (once near line 1261, again inside `startSync()`). `startSync()` re-registers them on **every** call (boot, every Firebase-config save, every 🔧 Repair Sync) → handlers pile up → each tab-switch fires more and more full push+pull cycles. Costs Firestore reads/writes and causes flickering data.

---

## 🟠 HIGH — Table cart bugs

### C8. Cart remap mixes up same-numbered tables in different areas
In `applyRemote()`:
```js
localByNo['|'+t.no] = localByNo['|'+t.no] || t.id;   // number-only key!
```
If you have **Table 5 on Ground Floor AND Table 5 on 1st Floor**, a synced cart can be relocated to the *wrong* Table 5 → items "jump tables", wrong table totals, mystery carts.

### C9. Two people on the same table → one person's items vanish
Cart merge is whole-cart, last-`mtime`-wins. If a waiter's phone and the cashier laptop edit the same table, the later timestamp wins wholesale and the other device's items disappear. The `_lastLocalEdit` protection covers only ONE key for only **800 ms**.

### C10. Empty ghost carts everywhere
`loadCartFor()` creates a cart object the moment you merely **tap** a table (mtime 0). These empty carts sync to all devices, linger up to 1 hour (`pruneEmptyCarts`), and participate in merges/Print Station logic. Combined with C8 this produces "ghost" tables and duplicate entries. Abandoned Takeaway/Delivery carts (`TA_*`/`DL_*` with items, never settled) stay "active" in Print Station **forever** — never pruned.

### C11. Settle-close race (KOTs/bills resurrecting)
At settle, `closedCarts[key]={ts:Date.now()}` and `emptyCart()` bumps `mtime=Date.now()` in the same/millisecond-tick. Whether the cart is treated as "settled" or "genuinely re-edited → new order" depends on which `Date.now()` wins — a literal race condition. This is the leftover-settled-KOT gremlin the code's comments keep fighting symptomatically.

---

## 🟠 HIGH — Other issues found

- **N1. 🔧 Fix Login is dangerous**: `localStorage.clear()` → reseeds demo menu → `menuVersion` mismatch → pushes demo menu to the cloud (feeds bug C3). It's placed right next to everyday buttons.
- **N2. Firestore security rules**: your API key + doc path `pos/induswok-default` are public in source (normal for Firebase) — but if your Firestore rules are the default test-mode `allow read, write: if true`, **anyone on the internet can read or wipe your restaurant data**. Must lock rules to that doc.
- **N3. localStorage quota**: `persist()` reserializes the whole DB on every tap; in incognito/shared-iPad/low-storage it silently stops saving (toast only once).
- **N4. Payload cost**: open live view → `estimatePayloadKB()` shows your current doc size. At ~50 bills/day with item snapshots you can expect 300–800 KB within months → approaching C5.
- **N5. `customer-orde.html`** reads the same Firestore doc — fine (read-only menu), but it also means any future change to its structure must stay compatible; it writes orders to a separate collection (`induswok_qr_orders`) which the POS never reads — QR customer orders placed there **never appear in the POS**.
- **N6. Half-price rule** `full − 60` silently invents half prices for items that never had one (₹150 soup → ₹90 half). If unintentional, bills are undercharging.
- **N7. `menuVersion` “keepLocalMenu”** logic re-sets `__menuUpgraded=true` and re-pushes — two devices with different versions ping-pong the cloud doc endlessly.
- **N8. No cache-busting** on GitHub Pages: devices keep running stale JS from cache, mixing versions → feeds C2/C3/N7. Add version pinning / a forced-reload mechanism.

---

## ✅ Fix plan

**Phase 1 — tactical patch (keeps current architecture, ~1 drop-in file):**
1. Persist `_rev` per device; make rev server-authoritative via a Firestore **transaction** (`runTransaction` + `increment`) so rev collisions can't happen.
2. Merge `settings` and `menu` by field/id instead of wholesale replace; never apply a remote snapshot whose payload is *older* than unpushed local edits (last-write queue).
3. Delete the auto menu replace (C3) — make seed-menu import an explicit button.
4. Fix backoff variable; dedupe the global hooks; auto-archive at **750 KB** payload, well under 1 MiB.
5. Cart remap keyed by `area|no` everywhere; don't create a cart on table tap until first item; merge same-table cart items line-wise instead of whole-cart; prune abandoned TA/DL carts.
6. Move Fix Login behind a confirm + make it never reseed-push; add cache-busting + "version mismatch → reload" banner.

**Phase 2 — proper fix (recommended when time permits):**
Split the monolithic document into Firestore collections (`bills/{id}`, `carts/{tableId}`, `settings/main`, `menu/{id}`…) with per-document writes and collection listeners. This eliminates rev collisions, the 1 MB wall, and most merge code in one move.
