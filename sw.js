/* Indus Wok POS — service worker (v14.3)
   Strategy: icons/manifest = cache-first; HTML/JS = network (handled by the
   browser, NOT cached here). Sync-critical data is Firestore's job (its own
   offline persistence), so this SW intentionally caches almost nothing — its
   main purpose is enabling the real "Install app" prompt. Bump CACHE name on
   any caching-strategy change so old clients self-clean. */
const CACHE = 'iw-pos-v14.3';
const STATIC = ['./icon-192.png', './icon-512.png', './manifest.webmanifest'];

self.addEventListener('install', (e) => {
  self.skipWaiting();
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(STATIC)).catch(() => {}));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  const url = new URL(e.request.url);
  if (url.origin !== self.location.origin) return; // never intercept Firebase/CDN traffic
  if (/\.(png|webmanifest)$/.test(url.pathname)) {
    e.respondWith(
      caches.match(e.request).then((hit) => hit || fetch(e.request).then((res) => {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(e.request, copy)).catch(() => {});
        return res;
      }).catch(() => hit))
    );
  }
  // everything else (HTML/JS) = plain network → always the freshest code
});
