/* Identidade leve para telas que não usam o editor de marca do site principal. */
(()=>{
 'use strict';
 const tenantLogo=value=>{
  const s=String(value||'').trim();
  return /^https:\/\//i.test(s)&&!/\/assets\/logo-gestao-software\.png(?:[?#]|$)/i.test(s)?s:'';
 };
 const update=data=>{
  if(!data||data.active===false)return;
  const logo=tenantLogo(data.logoUrl);
  const icon=logo||tenantLogo(data.faviconUrl);
  if(icon){
   let links=document.querySelectorAll('link[rel="icon"],link[rel="shortcut icon"],link[rel="apple-touch-icon"]');
   if(!links.length){
    const link=document.createElement('link');link.rel='icon';document.head.append(link);
    links=[link];
   }
   links.forEach(link=>{link.href=icon;link.removeAttribute('type');});
  }
  if(document.body.classList.contains('admin-page')){
   const brand=document.querySelector('.portal-header .portal-brand');
   if(brand){
    const img=brand.querySelector('img');
    if(img&&logo){img.src=logo;img.alt='Logo de '+String(data.companyName||'empresa');}
    const subtitle=brand.querySelector('small');
    if(subtitle)subtitle.textContent=String(data.platformDisplayName||data.companyName||'Empresa');
   }
  }
 };
 async function refresh(){
  try{
   const response=await fetch('/api/public/tenant-config',{cache:'no-store'});
   if(!response.ok) return;
   const data=await response.json();
   update(data);
  }catch(_error){ /* Tenta novamente sem substituir a identidade em cache. */ }
 }
 refresh();
 document.addEventListener('tenant-branding-updated',event=>update(event.detail));
 setInterval(refresh,60000);
})();
