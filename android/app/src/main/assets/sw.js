const CACHE_NAME = 'pdf-reader-v4';
const ASSETS_TO_CACHE = [
  './',
  './index.html',
  './manifest.json',
  './pdf.min.js',
  './pdf.worker.min.js',
  './favicon.png',
  './apple-touch-icon.png',
  './icon-192.png',
  './icon-maskable-192.png',
  './icon-512.png',
  './icon-maskable-512.png'
];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE_NAME).then(cache => {
      return cache.addAll(ASSETS_TO_CACHE);
    }).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys => {
      return Promise.all(
        keys.map(k => {
          if (k !== CACHE_NAME) return caches.delete(k);
        })
      );
    }).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  // CDN 외부 요청 및 가상 incoming.pdf 요청은 네트워크/네이티브 직접 통과
  const url = e.request.url;
  if (url.includes('cdn.jsdelivr.net') || url.includes('fonts.googleapis.com') || url.includes('incoming.pdf')) {
    return;
  }

  // Network First 전략: 최신 코드 우선 수신 후 캐시 저장, 오프라인 시 캐시 폴백
  e.respondWith(
    fetch(e.request)
      .then(networkResponse => {
        if (networkResponse && networkResponse.status === 200 && e.request.method === 'GET') {
          const responseToCache = networkResponse.clone();
          caches.open(CACHE_NAME).then(cache => {
            cache.put(e.request, responseToCache);
          });
        }
        return networkResponse;
      })
      .catch(() => {
        return caches.match(e.request).then(cached => {
          return cached || caches.match('./index.html');
        });
      })
  );
});
