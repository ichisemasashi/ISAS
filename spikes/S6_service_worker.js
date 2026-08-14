const CACHE = 'isas-s6-v2';
const SHELL = [
  './S6_device_capabilities.html',
  './S6_manifest.webmanifest',
  './S6_icon.svg'
];
self.addEventListener('install', event => event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(SHELL))));
self.addEventListener('activate', event => event.waitUntil(
  caches.keys().then(keys => Promise.all(keys.filter(key => key.startsWith('isas-s6-') && key !== CACHE).map(key => caches.delete(key))))
));
self.addEventListener('fetch', event => {
  if (event.request.method === 'GET') event.respondWith(fetch(event.request).catch(() => caches.match(event.request)));
});
self.addEventListener('sync', event => {
  if (event.tag === 'isas-s6-probe') event.waitUntil(Promise.resolve());
});
