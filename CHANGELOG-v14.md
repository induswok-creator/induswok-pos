# v14 Changelog — built on top of your latest `induswok-qr` build (v13.5)

Your v13.5 already fixed a lot (transactional pushes, initial-sync gating, persisted
revision, bill sharding + sequential bill numbers, cart tombstones, union cart merge,
auto-archive at 700 KB). v14 closes the **remaining** holes — every fix below is
unit-tested against the real sync engine (16/16 assertions pass).

## Your complaints → remaining causes in v13.5 → v14 fixes

### 1. Settings reverting ✅
Still in v13.5: `settings` was wholesale-replaced by whatever snapshot arrived;
only printer/AI keys were protected. A stale snapshot (stale tab, out-of-order push,
first-boot force-pull) could still clobber name/GST/UPI/thanks changes.
**v14:** settings/menu/users now carry explicit edit timestamps (`_meta.settingsAt /
menuAt / usersAt`) — a remote copy applies **only if edited more recently** than yours.
Printer, RawBT, AI and Firebase keys are device-local permanently. Verified by tests.

### 2. Old menu silently loading ✅
Still in v13.5: `load()` replaced your whole menu with the built-in demo menu whenever
`menuVersion` differed, marked `__menuUpgraded`, and `boot()` **pushed the demo menu to
every device**. One stale cached page = everyone's menu wiped.
**v14:** auto-replace and auto-push **deleted**. Demo menu seeds only on a truly fresh
install; moving menus across devices is the explicit "Settings → Push menu to all
devices" button (which v14 now timestamps properly).

### 3. Table cart ✅
- Still in v13.5: carts remapped **by table number alone** — Table 5 (Ground) and
  Table 5 (First Floor) collided and carts jumped areas. **v14:** strictly
  area + number.
- Still in v13.5: merely *tapping* a table created a synced empty cart that was
  **never pruned** (mtime 0 bypassed the pruner) — ghost clutter on every device.
  **v14:** carts only materialize on the first real edit; zero-mtime ghosts prune
  immediately.
- (v13.5's union merge already handles two-staff-same-table correctly — kept.)

### 4. India date bug (found while auditing) ✅
`todayStr()` used **UTC**: everything billed between 00:00–05:30 IST landed on
*yesterday's* date — wrong day reports, Day-Out summaries, expenses, archive cutoffs.
**v14:** all 14 date spots converted to local time.

### 5. Migration & hosting hygiene ✅
- Old Firebase project keys scrubbed from saved data; **one `firebase-config.js`**
  now feeds both pages (POS + customer QR) so they can never point at different
  databases again.
- 🔧 Fix Login resets only PINs (keeps data, device id, sync revision) — it used to
  wipe localStorage entirely, which also helped trigger the demo-menu push.
- Duplicate `visibilitychange/online/focus` sync hooks (registered twice + on every
  startSync) → registered once.
- Missing `updatedAt` stamps added on sessions/inventory/table-status so record
  merges resolve correctly.
- PWA manifest + icons → **installable app**.
- Netlify `_headers`/`netlify.toml` → no-cache for HTML/JS (kills the stale-version
  mixing that powered half the sync bugs).
- `firestore.rules` covers all four collections your build actually uses
  (`pos/*`, `induswok_counters`, `induswok_qr_orders`, `induswok_table_requests`).
  This exact snippet matters: my earlier version would have permission-blocked the
  bill counter and your QR inbox.

## Untouched (working well in v13.5)
Transactional push with server-newer abort · initial-sync gating · persisted rev ·
sharded bills + atomic sequential bill numbers · cart tombstones · smart cart union
merge · 700 KB auto-archive · QR inbox with alerts · printer device-locality.
