// Service worker: keeps the app shell, the sky icons and the last forecast available offline.
var CACHE = 'weather-v2';
var SHELL = ['./', 'index.html', 'manifest.webmanifest', 'icons/icon-192.png', 'icons/icon-512.png', 'icons/apple-touch-icon.png'];

self.addEventListener('install', function (e) {
  e.waitUntil(caches.open(CACHE).then(function (c) { return c.addAll(SHELL); }).then(function () { return self.skipWaiting(); }));
});
self.addEventListener('activate', function (e) {
  e.waitUntil(caches.keys().then(function (keys) {
    return Promise.all(keys.filter(function (k) { return k !== CACHE; }).map(function (k) { return caches.delete(k); }));
  }).then(function () { return self.clients.claim(); }));
});

function put(req, res) {
  if (res && (res.ok || res.type === 'opaque')) { var copy = res.clone(); caches.open(CACHE).then(function (c) { c.put(req, copy); }); }
  return res;
}
// fresh copy when online, last saved copy when offline
function networkFirst(req) {
  return fetch(req).then(function (res) { return put(req, res); }).catch(function () { return caches.match(req, { ignoreSearch: req.mode === 'navigate' }); });
}
// saved copy if there is one (the icon files never change), otherwise fetch and save
function cacheFirst(req) {
  return caches.match(req).then(function (hit) { return hit || fetch(req).then(function (res) { return put(req, res); }); });
}

self.addEventListener('fetch', function (e) {
  if (e.request.method !== 'GET') return;
  var host = new URL(e.request.url).hostname;
  if (host === 'cdn.jsdelivr.net') e.respondWith(cacheFirst(e.request));
  else if (host === location.hostname || host === 'api.open-meteo.com') e.respondWith(networkFirst(e.request));
});
