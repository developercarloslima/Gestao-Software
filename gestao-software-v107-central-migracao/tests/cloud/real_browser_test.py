"""Homologação visual do orquestrador com API simulada. Não usa dados reais."""
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT=Path(__file__).resolve().parents[2]
JS=ROOT/'web/admin/cloud.js'
CSS=ROOT/'web/admin/cloud.css'
HTML='''<!DOCTYPE html><html lang="pt-br"><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body style="margin:0;font-family:Arial,sans-serif"><main style="max-width:1120px;margin:auto;padding:14px">
<section id="tab-cloud" class="active">
<div id="cloud-status"></div><div id="cloud-count-servers"></div><div id="cloud-count-online"></div><div id="cloud-count-domains"></div>
<button id="cloud-refresh">Atualizar</button><form id="cloud-domain-form">
<label>Empresa<select id="cloud-company"></select></label>
<label>Endereço<input id="cloud-slug"></label>
<div id="cloud-domain-preview"></div>
<label>VPS<select id="cloud-server"></select></label><div id="cloud-server-tip"></div>
<div class="cloud-orchestrator-box"><strong>Seleção inteligente de VPS</strong><span id="cloud-orchestrator-mode"></span>
<button type="button" id="cloud-recommend">Analisar as VPS para esta empresa</button>
<div id="cloud-recommendation"></div>
<button type="button" id="cloud-auto-reserve" disabled>Escolher VPS e reservar automaticamente</button>
<button type="button" id="cloud-configure-dns" disabled>Configurar DNS</button></div>
<button type="button" id="cloud-check">Conferir domínio</button><button id="cloud-reserve">Reservar endereço</button>
<div id="cloud-form-message"></div></form><section class="cloud-columns"><div id="cloud-servers"></div></section>
<div id="cloud-domains"></div></section></main></body></html>'''
MOCK='''
window.companies=[{id:'11111111-1111-4111-8111-111111111111',tradeName:'Empresa Teste'}];
window.api=async (path,opt={}) => {
 window._calls=(window._calls||[]).concat([{path,method:opt.method||'GET'}]);
 if(path.includes('/overview'))return {baseDomain:'gestaosoftware.com.br',orchestratorEnabled:true,dnsProvider:'CLOUDFLARE',registeredServers:50,onlineServers:2,domainCount:0,
    servers:[{id:'22222222-2222-4222-8222-222222222222',name:'VPS 18',provider:'Teste',status:'AVAILABLE',active:true,healthyForStart:true,agentOnline:true,remainingSlots:5,maxCompanies:6,usedCompanies:1,cpuPercent:10,memoryPercent:18,diskPercent:20}],domains:[]};
 if(path.includes('/recomendacao'))return {enabledForAutomaticReservation:true,candidates:[
    {vpsId:'22222222-2222-4222-8222-222222222222',name:'VPS 18',eligible:true,score:88.5,reasons:[]},
    {vpsId:'33333333-3333-4333-8333-333333333333',name:'VPS 29',eligible:false,score:0,reasons:['RAM insuficiente']} ]};
 if(path.includes('/disponibilidade'))return {available:true,hostname:'exemplo.gestaosoftware.com.br'};
 if(path.includes('/reservar-automaticamente'))return {hostname:'exemplo.gestaosoftware.com.br',status:'RESERVED'};
 if(path.includes('/configurar-dns'))return {hostname:'exemplo.gestaosoftware.com.br',dnsStatus:'READY'};
 if(path.includes('/endereco'))return {hostname:'exemplo.gestaosoftware.com.br',status:'RESERVED'};
 throw new Error('API não simulada: '+path);
};'''

def main():
 with sync_playwright() as p:
  browser=p.chromium.launch(headless=True,executable_path='/usr/bin/chromium',args=['--no-sandbox','--disable-dev-shm-usage'])
  try:
   for width in (390,768,1366):
    page=browser.new_page(viewport={'width':width,'height':900})
    page.set_default_timeout(6000)
    errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
    page.set_content(HTML)
    page.add_style_tag(content=CSS.read_text())
    page.evaluate("() => {" + MOCK + "}")
    page.add_script_tag(path=str(JS))
    page.locator('#cloud-refresh').click()
    try:
     page.wait_for_function("document.getElementById('cloud-count-servers').textContent==='50'", timeout=5000)
    except Exception as exc:
     print('DEBUG',page.locator('#cloud-status').inner_text(),errors,page.evaluate('Object.keys(window).filter(x=>x.startsWith("api"))'))
     raise
    page.locator('#cloud-company').select_option('11111111-1111-4111-8111-111111111111')
    page.wait_for_function("document.getElementById('cloud-recommendation').textContent.includes('RAM insuficiente')")
    assert not page.locator('#cloud-auto-reserve').is_disabled(), f'auto disabled {width}'
    page.locator('#cloud-slug').fill('exemplo')
    page.locator('#cloud-auto-reserve').click()
    page.wait_for_function("document.getElementById('cloud-form-message').textContent.includes('reservou')")
    page.locator('#cloud-configure-dns').click()
    page.wait_for_function("document.getElementById('cloud-form-message').textContent.includes('DNS')")
    overflow=page.evaluate('document.documentElement.scrollWidth > innerWidth')
    assert not overflow, f'Rolagem horizontal {width}px'
    assert not errors, f'Erros JavaScript: {errors}'
    print(f'PASS Chrome {width}px: recomendação, reserva, DNS mock, sem overflow e sem erro JS')
    page.close()
  finally:browser.close()

if __name__=='__main__':main()
