// v83-96
const CACHE = 'apontamento-digital-v83-96-jornada-normal-sem-aprovacao-presenca-historica';
const MODEL_CACHE = 'nh-ponto-biometric-models-v3';
const APP_SHELL = ['/ponto/', '/ponto/index.html', '/ponto/manifest.webmanifest', '/ponto/identidade-pendente.svg', '/ponto/identidade-pendente.svg'];

self.addEventListener('install', event => {
  self.skipWaiting();
  event.waitUntil(caches.open(CACHE).then(c => c.addAll(APP_SHELL)));
});

self.addEventListener('activate', event => event.waitUntil((async()=>{
  const keys=await caches.keys();
  await Promise.all(keys
    .filter(k => (k.startsWith('pontoproof-') || k.startsWith('nh-ponto-') || k.startsWith('apontamento-digital-')) && k!==CACHE && k!==MODEL_CACHE)
    .map(k=>caches.delete(k)));
  await self.clients.claim();
})()));

self.addEventListener('fetch', event => {
  if (event.request.method !== 'GET') return;
  const url=new URL(event.request.url);

  // O service worker do Apontamento Digital cuida apenas de recursos do próprio domínio.
  // Requisições externas (fontes, CDN, etc.) seguem direto pelo navegador.
  if(url.origin !== self.location.origin) return;

  if(url.pathname.startsWith('/ponto/models/human/')){
    event.respondWith((async()=>{
      const cache=await caches.open(MODEL_CACHE);
      const cached=await cache.match(event.request);
      if(cached)return cached;
      const response=await fetch(event.request);
      if(response.ok)cache.put(event.request,response.clone());
      return response;
    })());
    return;
  }

  // API nunca deve cair no fallback da SPA/cache.
  if(url.pathname.startsWith('/ponto-api/')) return;

  // App network-first para receber deploys imediatamente.
  event.respondWith(fetch(event.request).catch(() => caches.match(event.request).then(r => r || caches.match('/ponto/'))));
});
