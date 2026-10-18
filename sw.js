const CACHE_PREFIX='djs-pdf-app:';
const CACHE_NAME='djs-pdf-app:1.2.1-396facfd8ea0281a';
const ASSETS=['./','./index.html','./app.js','./reader-core.js','./reader-store.js','./manifest.json','./pdf.min.js','./pdf.worker.min.js','./favicon.png','./apple-touch-icon.png','./icon-192.png','./icon-maskable-192.png','./icon-512.png','./icon-maskable-512.png'];
self.addEventListener('install',event=>event.waitUntil(caches.open(CACHE_NAME).then(cache=>cache.addAll(ASSETS)).then(()=>self.skipWaiting())));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(key=>key.startsWith(CACHE_PREFIX)&&key!==CACHE_NAME).map(key=>caches.delete(key)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',event=>{
  const url=new URL(event.request.url),base=new URL('./',self.registration.scope);
  const readerNavigation=url.pathname===base.pathname||url.pathname===base.pathname+'index.html';
  if(event.request.method!=='GET'||url.origin!==base.origin||!url.pathname.startsWith(base.pathname)||url.pathname.endsWith('/incoming.pdf'))return;
  event.respondWith((async()=>{
    const cache=await caches.open(CACHE_NAME);
    try{
      const response=await fetch(event.request);
      // A PWA navigation must use the same asset generation as the offline cache.
      // Updating the SW installs the whole new asset set before activation.
      if(event.request.mode==='navigate'&&readerNavigation)return await cache.match('./index.html')||response;
      const cached=await cache.match(event.request,{ignoreSearch:true});
      return cached||response;
    }catch(_){
      const cached=await cache.match(event.request,{ignoreSearch:true});
      if(cached)return cached;
      if(event.request.mode==='navigate'&&readerNavigation)return await cache.match('./index.html')||Response.error();
      return Response.error();
    }
  })());
});
