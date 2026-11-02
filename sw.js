// Guarda todo lo que se descarga (páginas, Ruffle, emuladores) para que funcione sin internet.
// shortcut: cache-first means updates only arrive after bumping CACHE; bump it when any app file changes.
const CACHE = "rv-v16";
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("fetch", e => {
  if (e.request.method !== "GET" || !e.request.url.startsWith("http")) return;
  e.respondWith(caches.open(CACHE).then(async c => {
    const hit = await c.match(e.request, { ignoreSearch: true }); // play.html?id=N sirve para cualquier juego
    if (hit) return hit;
    const res = await fetch(e.request);
    if (res.ok) c.put(e.request, res.clone());
    return res;
  }));
});
self.addEventListener("activate", e => e.waitUntil(
  caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())
));
