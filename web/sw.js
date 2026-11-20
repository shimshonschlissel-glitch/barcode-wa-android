// Keeps the app working without a connection. Bump VERSION when any file changes.
const VERSION = 'v17';
const SHELL = ['./', 'index.html', 'zxing-reader.js', 'zxing_reader.wasm', 'manifest.webmanifest',
  'icon-192.png', 'icon-512.png', 'icon-maskable-512.png', 'apple-touch-icon.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== VERSION && k !== 'fonts').map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);

  // Pages: network first so updates arrive, cache when offline. Each page under its own name:
  // before v16 every navigation (also install.html, and tapping the .apk link) was stored as index.html.
  if (req.mode === 'navigate') {
    const isApp = /\/(index\.html)?$/.test(url.pathname);
    const isPage = isApp || url.pathname.endsWith('.html');
    e.respondWith(fetch(req).then(res => {
      if (res.ok && isPage) {
        const copy = res.clone();
        caches.open(VERSION).then(c => c.put(isApp ? 'index.html' : url.pathname, copy));
      }
      return res;
    }).catch(() => caches.match(isApp ? 'index.html' : url.pathname).then(hit => hit || caches.match('index.html'))));
    return;
  }

  // Which app version is out: always ask the network.
  if (url.pathname.endsWith('version.json')) return;

  // Google Fonts: cache on first use.
  if (url.hostname === 'fonts.googleapis.com' || url.hostname === 'fonts.gstatic.com') {
    e.respondWith(caches.open('fonts').then(c => c.match(req).then(hit => hit || fetch(req).then(res => {
      c.put(req, res.clone());
      return res;
    }))));
    return;
  }

  // Everything else from this site: cache first.
  if (url.origin === self.location.origin) {
    e.respondWith(caches.match(req, { ignoreSearch: true }).then(hit => hit || fetch(req)));
  }
});
