const CACHE='my-space-v3.0-cosmic-dialogue-20261107';
const CORE=['./','./index.html','./manifest.webmanifest','./icons/icon-192.png','./icons/icon-512.png','./assets/chat/nebula-teal.jpg','./assets/chat/nebula-violet.jpg','./assets/chat/nebula-amber.jpg'];
const isAppAsset=url=>url.pathname==='/'||url.pathname.endsWith('/index.html')||url.pathname.endsWith('/manifest.webmanifest')||url.pathname.endsWith('/sw.js')||url.pathname.includes('/icons/')||url.pathname.includes('/assets/chat/');
self.addEventListener('install',event=>event.waitUntil(caches.open(CACHE).then(c=>c.addAll(CORE)).then(()=>self.skipWaiting())));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith('my-space-')&&k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',event=>{
 const req=event.request,url=new URL(req.url);
 if(req.method!=='GET'||url.origin!==self.location.origin||url.pathname.startsWith('/api/'))return;
 if(!isAppAsset(url))return;
 event.respondWith(caches.match(req).then(hit=>hit||fetch(req).then(res=>{
  if(res&&res.ok){const copy=res.clone();caches.open(CACHE).then(c=>c.put(req,copy))}
  return res;
 }).catch(error=>{
  if(req.mode==='navigate')return caches.match('./index.html');
  throw error;
 })));
});
