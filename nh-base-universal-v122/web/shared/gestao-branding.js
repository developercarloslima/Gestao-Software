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
    // A marca do cliente tem prioridade no ícone da aba. Favicon genérico da
    // Gestão não substitui a logo do tenant.
    const customized=tenantLogo(c.faviconUrl);
    const browserIcon=customized||logo;
    if(browserIcon){
      document.querySelectorAll('link[rel="icon"],link[rel="shortcut icon"],link[rel="apple-touch-icon"]').forEach(link=>{
        link.href=browserIcon;
        if(link.getAttribute('rel')==='apple-touch-icon')return;
        link.removeAttribute('type'); // conteúdo PNG, JPG e WebP de cada cliente
      });
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
    setText('.portal-brand small',c.siteContent?.headerSubtitle||c.companyTagline||'Área da empresa');
    setText('[data-platform-legal-name]',c.platformLegalName||'Gestão Software LTDA');
    if(window.NH_SITE_EDITOR_APPLY)window.NH_SITE_EDITOR_APPLY(c.siteContent||{},c);
    document.querySelectorAll('.developer-credit').forEach(el=>el.textContent=`Tecnologia: ${c.platformLegalName||'Gestão Software LTDA'}`);

    const enabled=new Set((c.enabledModules||[]).map(String));
    if(!enabled.has('COTA'))document.querySelectorAll('.cotacao-trigger, #cotacaoModal').forEach(el=>{el.hidden=true;el.style.setProperty('display','none','important')});
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

  // A API Render Free pode hibernar ou reiniciar. Erros de rede não são
  // evidência de suspensão da licença: mantenha o conteúdo coberto até
  // recebermos uma configuração validada.
  let attempts=0, nextRetry=null, connectionPanel=null;
  function connectionView(title,message){
    if(!connectionPanel){
      connectionPanel=document.createElement('main');
      connectionPanel.id='nh-tenant-connection-panel';
      connectionPanel.setAttribute('role','status');
      connectionPanel.setAttribute('aria-live','polite');
      connectionPanel.style.cssText='position:fixed;inset:0;z-index:2147483647;display:grid;place-items:start center;padding:clamp(50px,11vh,130px) 20px 30px;overflow:auto;background:#f5f7fc;color:#070c40;font:16px system-ui,sans-serif';
      const card=document.createElement('section');
      card.style.cssText='width:min(100%,520px);padding:clamp(24px,5vw,38px);border-radius:18px;background:#fff;box-shadow:0 20px 60px rgba(7,12,64,.10)';
      const h=document.createElement('h1');h.id='nh-connect-title';h.style.cssText='margin:0 0 12px;font:700 25px/1.3 system-ui';
      const p=document.createElement('p');p.id='nh-connect-message';p.style.cssText='line-height:1.6;margin:0 0 20px';
      const button=document.createElement('button');button.type='button';button.textContent='Tentar novamente';button.style.cssText='border:0;border-radius:10px;background:#070c40;color:#fff;padding:12px 18px;cursor:pointer;font:600 14px system-ui';
      button.addEventListener('click',()=>{clearTimeout(nextRetry);attempts=0;loadConfig();});
      card.append(h,p,button);connectionPanel.append(card);
      document.body.append(connectionPanel);
    }
    connectionPanel.querySelector('#nh-connect-title').textContent=title;
    connectionPanel.querySelector('#nh-connect-message').textContent=message;
  }
  async function loadConfig(){
    attempts++;
    connectionView('Conectando ao sistema','Consultando a identidade visual e os módulos autorizados da empresa.');
    try {
      const response=await fetch('/api/public/tenant-config',{cache:'no-store'});
      if(!response.ok)throw new Error('HTTP '+response.status);
      const data=await response.json();
      if(!data||typeof data!=='object'||(!data.companyId&&data.source==='UNCONFIGURED_FAIL_CLOSED')){
        connectionView('Configuração da empresa pendente','A API respondeu, mas ainda não recebeu uma identidade validada da Gestão Software. Confira o vínculo da empresa e a integração da API.');
        return;
      }
      if(connectionPanel){connectionPanel.remove();connectionPanel=null;}
      apply(data);
    } catch(err) {
      const message=attempts<24
        ? 'A API está indisponível temporariamente ('+String(err.message||'falha de conexão')+'). Tentativa automática '+attempts+' de 24. Esta falha não indica, por si só, suspensão da licença.'
        : 'Não foi possível consultar a API após várias tentativas. Confira o serviço nh122-demo-api no Render e tente novamente.';
      connectionView('Aguardando conexão com a API',message);
      if(attempts<24)nextRetry=setTimeout(loadConfig,Math.min(25000,3000+attempts*2000));
    }
  }
  loadConfig();
})();
