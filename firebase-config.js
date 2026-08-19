/* ===========================================================================
   Indus Wok POS — Firebase project config  ✅ CONFIGURED (project: indus-wok-pos-2026)
   ---------------------------------------------------------------------------
   This file is shared by BOTH pages (index.html and customer-orde.html), so the
   POS and the customer QR page always point at the same data.

   docPath: the shared Firestore document that holds the synced restaurant data.
   Keep it IDENTICAL on every device. (Optional: rename to something unguessable,
   e.g. "pos/iw8f3kx-data" — the rules in firestore.rules allow any doc under pos/)
   =========================================================================== */
window.IW_FIREBASE_CONFIG = {
  apiKey:   "AIzaSyA9_tfiGyoXooRrw5rr1P6nHih9_AOkZMg",
  projectId:"indus-wok-pos-2026",
  appId:    "1:469374828769:web:9e40728efff76a1de8b7a0",
  docPath:  "pos/induswok-default"
};
