// Service Worker for J.A.R.V.I.S. PWA - Network-First Strategy
const CACHE_NAME = 'jarvis-hud-v10';
const ASSETS_TO_CACHE = [
  './',
  './index.html',
  './style.css',
  './app.js',
  './manifest.json',
  './icon-192.png',
  './icon-512.png',
  './icon.svg'
];

// Install Event: cache core app shell and skip waiting immediately
self.addEventListener('install', (event) => {
  self.skipWaiting();
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      console.log('[JARVIS SW] Pre-caching core assets:', CACHE_NAME);
      return cache.addAll(ASSETS_TO_CACHE);
    })
  );
});

// Activate Event: remove all previous obsolete caches and claim clients immediately
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((cacheNames) => {
      return Promise.all(
        cacheNames.map((cache) => {
          if (cache !== CACHE_NAME) {
            console.log('[JARVIS SW] Clearing old obsolete cache:', cache);
            return caches.delete(cache);
          }
        })
      );
    }).then(() => self.clients.claim())
  );
});

// Fetch Event: Network-First strategy for application assets (guarantees live fixes are delivered immediately)
self.addEventListener('fetch', (event) => {
  const request = event.request;
  const url = new URL(request.url);

  // Bypass cache completely for API endpoints, backend routes and external queries
  if (
    request.method !== 'GET' ||
    url.pathname.startsWith('/chat') ||
    url.pathname.startsWith('/local-chat') ||
    url.pathname.startsWith('/status') ||
    url.pathname.startsWith('/ping') ||
    url.pathname.startsWith('/reset') ||
    url.hostname.includes('api.openai.com') ||
    url.hostname.includes('api.x.ai') ||
    url.hostname.includes('api.elevenlabs.io') ||
    url.hostname.includes('wttr.in') ||
    url.protocol.startsWith('chrome-extension')
  ) {
    return;
  }

  // Network-First: Always attempt to fetch the freshest version over the network
  event.respondWith(
    fetch(request)
      .then((networkResponse) => {
        if (networkResponse && networkResponse.status === 200) {
          const responseClone = networkResponse.clone();
          caches.open(CACHE_NAME).then((cache) => {
            cache.put(request, responseClone);
          });
        }
        return networkResponse;
      })
      .catch(() => {
        // Only if offline, fall back to cached version
        return caches.match(request).then((cachedResponse) => {
          if (cachedResponse) return cachedResponse;
          if (request.mode === 'navigate') {
            return caches.match('./index.html');
          }
        });
      })
  );
});
