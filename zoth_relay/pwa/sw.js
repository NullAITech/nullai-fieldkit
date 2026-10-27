// FieldKit PWA shell cache: page works offline, data always live from the relay.
const CACHE = "fieldkit-pwa-v1";
self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(["/pwa", "/pwa/manifest.webmanifest"])));
  self.skipWaiting();
});
self.addEventListener("fetch", e => {
  if (e.request.method !== "GET" || e.request.url.includes("/v1/")) return;
  e.respondWith(fetch(e.request).catch(() => caches.match(e.request)));
});
