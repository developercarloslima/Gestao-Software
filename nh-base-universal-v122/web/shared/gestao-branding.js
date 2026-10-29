(() => {
  const MODULE_BY_PATH = [
    [/^\/(?:cota|comparacao|confirmacao-valor)(?:\/|$)/i,'COTA'],
    [/^\/(?:analise|supervisao|guincho|oficina|checklist|financeiro|aceite-evento|retrato)(?:\/|$)/i,'VISTORIA_EVENTOS'],
    [/^\/(?:associados|veiculos)(?:\/|$)/i,'GESTAO_FINANCEIRA_COMERCIAL'],
    [/^\/ponto(?:\/|$)/i,'APONTAMENTO_DIGITAL'],
    [/^\/$/,'SITE_PRINCIPAL']
  ];

  function currentModule(){ const p=location.pathname||'/'; for(const [re,m] of MODULE_BY_PATH) if(re.test(p)) return m; return null; }
  function setText(selector,value){ if(!value)return; document.querySelectorAll(selector).forEach(el=>el.textContent=value); }
  function tenantLogo(url){
    const value=String(url||'').trim();
    // Nunca exibir a marca da Gestão como se fosse a marca do cliente.
    return value && !/\/assets\/logo-gestao-software\.png(?:[?#]|$)/i.test(value) ? value : '';
  }
  function companyInitials(c){
    const name=String(c.platformDisplayName||c.companyName||'Empresa').trim();
    const words=name.split(/\s+/).filter(Boolean);
    return (words.length>1 ? words.slice(0,2).map(w=>w[0]) : [name.slice(0,2)]).join('').toLocaleUpperCase('pt-BR');
  }
  function logoFallback(img,c){
    if(img.dataset.tenantLogoFallback==='done')return;
    img.dataset.tenantLogoFallback='done';
    img.hidden=true;
    img.style.display='none';
    const badge=document.createElement('span');
    badge.className='tenant-brand-monogram';
    badge.textContent=companyInitials(c);
    badge.setAttribute('aria-label','Identidade visual de '+String(c.platformDisplayName||c.companyName||'Empresa'));
    img.insertAdjacentElement('afterend',badge);
  }

  function apply(c){
    window.GESTAO_TENANT_CONFIG=c;
    if(c && c.active===false){
      document.body.innerHTML='<main style="min-height:100vh;display:grid;place-items:center;padding:24px;background:#f5f8ff;font-family:system-ui,sans-serif"><section style="max-width:560px;background:#fff;padding:36px;border-radius:22px;box-shadow:0 20px 60px rgba(5,22,74,.14);text-align:center"><h1 style="color:#07184a">Plataforma temporariamente indisponível</h1><p style="color:#667695">A assinatura desta empresa precisa ser regularizada. Entre em contato com o responsável administrativo.</p><small style="color:#8895aa">Gestão Software LTDA</small></section></main>';
      return;
    }
    const root=document.documentElement;
    root.style.setProperty('--brand-primary',c.primaryColor||'#263247');
    root.style.setProperty('--brand-secondary',c.secondaryColor||'#66a3b9');
    root.style.setProperty('--brand-accent',c.accentColor||'#ffffff');
    root.style.setProperty('--nh-blue',c.primaryColor||'#263247');
    root.style.setProperty('--nh-navy',c.primaryColor||'#263247');
    root.style.setProperty('--nh-yellow',c.secondaryColor||'#66a3b9');
    root.style.setProperty('--nh-white',c.accentColor||'#ffffff');
    root.style.setProperty('--yellow',c.secondaryColor||'#66a3b9');
    root.style.setProperty('--navy',c.primaryColor||'#263247');
    root.style.setProperty('--navy-950',c.primaryColor||'#263247');
    if(c.siteTitle) document.title=document.title.replace(/Empresa Cliente/gi,c.siteTitle);
    const logo=tenantLogo(c.logoUrl);
    const favicon=document.querySelector('link[rel="icon"]');
    if(favicon) {
      const custom=tenantLogo(c.faviconUrl);
      if(custom||logo) favicon.href=custom||logo;
    }
    document.querySelectorAll('img').forEach(img=>{
      const src=img.getAttribute('src')||'';
      if(!/logo-nh|favicon-nh|simbolo-fundo-azul|identidade-pendente\.svg/i.test(src))return;
      if(!logo){logoFallback(img,c);return;}
      img.onerror=()=>logoFallback(img,c);
      img.src=logo;
      img.alt=c.platformDisplayName||c.companyName||'Empresa';
      img.style.visibility='visible';
    });
    setText('.portal-brand strong, [data-company-brand], .brand-name strong',c.platformDisplayName||c.companyName);
    // Substituir placeholders em páginas antigas sem exibir o nome de outro cliente.
    const walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
    let node;
    while((node=walker.nextNode())){
      const parent=node.parentElement;
      if(!parent||/^(SCRIPT|STYLE|TEXTAREA|OPTION|CODE)$/i.test(parent.tagName)) continue;
      if(node.nodeValue&&node.nodeValue.includes('Empresa Cliente')){
        node.nodeValue=node.nodeValue.replaceAll('Empresa Cliente',c.platformDisplayName||c.companyName||'Empresa');
      }
    }
    document.querySelectorAll('[data-requires-customer-app]').forEach(a=>{
      const href=String(c.mobileAppUrl||'');
      if(/^https:\/\//.test(href)){a.href=href;a.hidden=false;}
    });
    setText('.portal-brand small, [data-platform-legal-name]',c.platformLegalName||'Gestão Software LTDA');
    document.querySelectorAll('.developer-credit').forEach(el=>el.textContent=`Tecnologia: ${c.platformLegalName||'Gestão Software LTDA'}`);

    const enabled=new Set((c.enabledModules||[]).map(String));
    document.querySelectorAll('[data-module-code]').forEach(el=>{if(!enabled.has(el.dataset.moduleCode))el.hidden=true});
    document.querySelectorAll('a[href]').forEach(a=>{
      const href=a.getAttribute('href')||'';
      let module=null;
      if(/^\/?cota|\/comparacao|\/confirmacao-valor/i.test(href)) module='COTA';
      else if(/\/(analise|supervisao|guincho|oficina|checklist|financeiro|aceite-evento|retrato)/i.test(href)) module='VISTORIA_EVENTOS';
      else if(/\/(associados|veiculos)/i.test(href)) module='GESTAO_FINANCEIRA_COMERCIAL';
      else if(/\/ponto/i.test(href)) module='APONTAMENTO_DIGITAL';
      if(module&&!enabled.has(module)) a.hidden=true;
    });

    const module=currentModule();
    if(module&&!enabled.has(module)){
      const target=c.defaultModuleLanding||'/colaborador/';
      if(location.pathname!==target) location.replace(target);
    }
  }

  fetch('/api/public/tenant-config',{cache:'no-store'})
    .then(r=>r.ok?r.json():Promise.reject(new Error('config')))
    .then(apply)
    .catch(()=>{document.body.innerHTML='<main style="font:16px system-ui;margin:6vh auto;padding:40px;max-width:620px"><h1>Configuração pendente</h1><p>Este ambiente ainda não recebeu sua identidade visual e licença da Gestão Software. Contate o administrador.</p></main>';});
})();
