const CACHE = "isas-shell-v2";
const SHELL = ["/", "/manifest.webmanifest", "/icon.svg"];
const CACHEABLE_DESTINATIONS = new Set(["script", "style", "image", "font"]);

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key)))),
  );
  self.clients.claim();
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET" || url.origin !== self.location.origin || url.pathname.startsWith("/api/") || event.request.headers.has("Authorization")) return;
  if (!SHELL.includes(url.pathname) && !CACHEABLE_DESTINATIONS.has(event.request.destination)) return;
  event.respondWith(
    fetch(event.request)
      .then((response) => {
        const cacheControl = response.headers.get("Cache-Control") || "";
        const canCache = response.ok && !response.headers.has("Set-Cookie") && !/(?:private|no-store)/i.test(cacheControl);
        if (canCache) caches.open(CACHE).then((cache) => cache.put(event.request, response.clone()));
        return response;
      })
      .catch(() => caches.match(event.request).then((cached) => cached || caches.match("/"))),
  );
});
