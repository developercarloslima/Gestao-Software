/* Site Principal white-label. Nenhum HTML de empresa é injetado; campos usam textContent. */
(() => {
  'use strict';
  const $=(selector)=>document.querySelector(selector);
  const all=(selector)=>Array.from(document.querySelectorAll(selector));
  function text(selector,value){
    if(typeof value!=='string'||!value.trim())return;
    const node=$(selector);
    if(node)node.textContent=value.trim();
  }
  function safeUrl(value,{image=false}={}){
    const raw=String(value||'').trim();
    if(!raw)return '';
    if(image){
      if(raw.startsWith('https://'))return raw;
      if(raw.startsWith('/api/public/platform/site-assets/'))return raw;
      return '';
    }
    if(raw.startsWith('#')&&/^#[a-zA-Z0-9_-]+$/.test(raw))return raw;
    if(raw.startsWith('/')&&!raw.startsWith('//'))return raw;
    if(raw.startsWith('https://'))return raw;
    return '';
  }
  function phone(value){
    const clean=String(value||'').replace(/[^0-9+]/g,'');
    return /^[+]?[0-9]{8,16}$/.test(clean)?clean:'';
  }
  function email(value){
    const clean=String(value||'').trim();
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(clean)?clean:'';
  }
  function toggle(selector,value){
    if(value!==false)return;
    all(selector).forEach(node=>{node.hidden=true;node.style.setProperty('display','none','important');});
  }
  function blocks(containerSelector,items,kind){
    if(typeof items!=='string'||!items.trim())return;
    const container=$(containerSelector);
    if(!container)return;
    const fragment=document.createDocumentFragment();
    const lines=items.split(/\r?\n/).map(line=>line.trim()).filter(Boolean).slice(0,20);
    for(const [index,line] of lines.entries()){
      const [name,description,icon]=line.split('|').map(v=>String(v||'').trim());
      if(!name)continue;
      const article=document.createElement('article');
      article.className=kind==='steps'?'step-card':'benefit-card';
      const circle=document.createElement('div');circle.className='icon-circle';
      const iconUrl=safeUrl(icon,{image:true});
      if(iconUrl){const img=document.createElement('img');img.src=iconUrl;img.alt='';img.loading='lazy';circle.append(img);}
      else{const symbol=document.createElement('span');symbol.textContent='✓';symbol.setAttribute('aria-hidden','true');circle.append(symbol);}
      article.append(circle);
      if(kind==='steps'){const number=document.createElement('span');number.className='step-number';number.textContent=String(index+1);article.append(number);}
      const h=document.createElement('h3');h.textContent=name;article.append(h);
      if(description){const p=document.createElement('p');p.textContent=description;article.append(p);}
      fragment.append(article);
    }
    if(fragment.childNodes.length)container.replaceChildren(fragment);
  }
  function applySite(config,identity){
    if(!config||typeof config!=='object')return;
    text('.site-header .brand-name small',config.headerSubtitle);
    text('.site-header .header-phone span',config.assistanceText);
    text('.site-header .main-menu .assistance-menu-link',config.assistanceText);
    const assistancePhone=phone(config.assistancePhone);
    if(assistancePhone){
      all('.site-header .header-phone, .site-header .main-menu .assistance-menu-link, .site-footer .footer-compact-phone').forEach(a=>{
        a.setAttribute('href','tel:'+assistancePhone);
      });
    }
    if(typeof config.navigationLinks==='string'&&config.navigationLinks.trim()){
      const nav=$('.site-header .main-menu');
      if(nav){
        all('.site-header .main-menu a:not(.assistance-menu-link):not(.collaborator-menu-link):not(.associate-menu-link)').forEach(a=>a.remove());
        const divider=nav.querySelector('.collaborator-menu-link');
        for(const line of config.navigationLinks.split(/\r?\n/).slice(0,12)){
          const [label,link]=line.split('|').map(v=>String(v||'').trim());
          const href=safeUrl(link);if(!label||!href)continue;
          const a=document.createElement('a');a.textContent=label.slice(0,80);a.href=href;
          nav.insertBefore(a,divider||null);
        }
      }
    }
    toggle('.hero-section',config.showHero);
    text('.hero-section .hero-copy h1',config.heroTitle);
    text('.hero-section .hero-copy p',config.heroDescription);
    const heroBg=safeUrl(config.heroImageUrl,{image:true});
    if(heroBg){
      const bg=$('.hero-section .hero-bg');
      if(bg){bg.style.backgroundImage='url('+JSON.stringify(heroBg)+')';bg.style.backgroundSize='cover';bg.style.backgroundPosition='center';}
    }
    text('.hero-section .primary-button',config.heroButtonText);
    const heroHref=safeUrl(config.heroButtonUrl);
    if(heroHref){
      const link=$('.hero-section .primary-button');
      if(link){link.href=heroHref;link.classList.remove('cotacao-trigger');}
    }
    toggle('.hero-section .hero-actions .cotacao-trigger, .site-header .header-cta',config.showQuoteButton);
    toggle('.benefits-section',config.showBenefits);
    text('.benefits-section .section-heading span',config.benefitsEyebrow);
    text('.benefits-section .section-heading h2',config.benefitsTitle);
    blocks('.benefits-section .benefits-grid',config.benefitsItems,'benefits');
    toggle('.steps-section',config.showSteps);
    text('.steps-section .section-heading span',config.stepsEyebrow);
    text('.steps-section .section-heading h2',config.stepsTitle);
    blocks('.steps-section .steps-grid',config.stepsItems,'steps');
    toggle('.app-section',config.showApp);
    text('.app-section .app-copy h2',config.appTitle);
    text('.app-section .app-copy p',config.appDescription);
    const appImage=safeUrl(config.appImageUrl,{image:true});
    if(appImage){const img=$('.app-section .app-phone-image');if(img){img.src=appImage;img.onerror=null;img.alt='Aplicativo de '+String(identity.platformDisplayName||identity.companyName||'empresa');}}
    const ios=$('.app-section a[href*="apps.apple.com"]');
    if(ios){const url=safeUrl(config.iosUrl);if(url){ios.href=url;ios.hidden=false;}else{ios.hidden=true;ios.style.display='none';}}
    const android=$('.app-section a[data-requires-customer-app]');
    if(android){const url=safeUrl(config.androidUrl||identity.mobileAppUrl);if(url){android.href=url;android.hidden=false;}else{android.hidden=true;android.style.display='none';}}
    toggle('.contact-section',config.showContact);
    text('.contact-section .contact-eyebrow',config.contactEyebrow);
    text('.contact-section .contact-copy h2',config.contactTitle);
    text('.contact-section .contact-copy p',config.contactDescription);
    const contactPhone=phone(config.contactPhone);
    if(contactPhone){
      const link=$('.contact-section a[href^="tel:"]');if(link){link.href='tel:'+contactPhone;link.textContent='Ligar '+contactPhone;}
      const foot=$('.site-footer .footer-compact-contact a[href^="tel:"]');
      if(foot){foot.href='tel:'+contactPhone;foot.textContent=contactPhone;}
    }
    const contactWhatsapp=phone(config.contactWhatsapp);
    if(contactWhatsapp){
      const link=$('.contact-section a[href^="https://wa.me/"]');
      if(link)link.href='https://wa.me/'+contactWhatsapp.replace('+','');
    }
    const contactEmail=email(config.contactEmail);
    if(contactEmail){
      const link=$('.site-footer .footer-compact-contact a[href^="mailto:"]');
      if(link){link.href='mailto:'+contactEmail;link.textContent=contactEmail;}
    }
    toggle('.contact-section .contact-form',config.showContactForm);
    const formRecipient=email(config.contactFormRecipient);
    const form=$('.contact-section .contact-form');
    if(form){
      if(formRecipient)form.action='https://formsubmit.co/ajax/'+encodeURIComponent(formRecipient);
      else{form.hidden=true;form.style.setProperty('display','none','important');}
    }
    text('.site-footer .footer-compact-bottom p:first-child',config.footerLegalText||config.footerDescription);
    text('.site-footer .footer-compact-address p',config.footerAddress);
    text('.site-footer .footer-compact-hours p',config.footerHours);
    const instagram=safeUrl(config.socialInstagramUrl);
    const instagramLink=$('.floating-social a[aria-label^="Instagram"]');
    if(instagramLink){
      if(instagram){instagramLink.href=instagram;instagramLink.hidden=false;}
      else{instagramLink.hidden=true;instagramLink.style.display='none';}
    }
    // Impedir vazamento dos contatos e do endereço específicos do modelo para novos clientes.
    if(!contactPhone){
      all('.contact-section a[href^="tel:"], .site-footer .footer-compact-contact a[href^="tel:"]').forEach(a=>a.hidden=true);
    }
    if(!assistancePhone){
      all('.site-header .header-phone, .site-header .main-menu .assistance-menu-link, .site-footer .footer-compact-assist a[href^="tel:"]').forEach(a=>a.hidden=true);
    }
    if(!config.footerAddress){
      const address=$('.site-footer .footer-compact-address');if(address)address.hidden=true;
    }
    if(!config.footerHours){
      const hours=$('.site-footer .footer-compact-hours');if(hours)hours.hidden=true;
    }
    if(!contactEmail){
      const link=$('.site-footer .footer-compact-contact a[href^="mailto:"]');if(link)link.hidden=true;
    }
    const bottom=$('.site-footer .footer-compact-bottom p:first-child');
    if(bottom&&!(config.footerLegalText||config.footerDescription))bottom.textContent=String(identity.platformDisplayName||identity.companyName||'Empresa');
    // O favicon da empresa e o titulo sao atualizados em gestao-branding.js.
  }
  window.NH_SITE_EDITOR_APPLY=applySite;
})();
