(() => {
 const ensure=()=>{if(document.querySelector('.nh-dev-credit'))return;
 const foot=document.querySelector('footer');if(!foot)return;
 const div=document.createElement('div');div.className='nh-dev-credit';div.textContent='Tecnologia: Gestão Software LTDA';
 div.style.cssText='padding:12px;text-align:center;font-size:12px;opacity:.7';foot.appendChild(div);
 };
 if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',ensure,{once:true});else ensure();
})();
