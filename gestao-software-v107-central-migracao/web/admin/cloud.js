/* Gestão Cloud v104: interface funcional; sem provisionamento fictício. */
(()=>{
  'use strict';
  const $c=id=>document.getElementById(id);
  let state=null;
  let requestCounter=0;
  let available=false;
  let recommendation=null;
  let recommendationCounter=0;
  const msg=(str,type='')=>{const el=$c('cloud-form-message');el.textContent=str;el.className='form-message '+type;};
  const safe=value=>String(value??'').replace(/[&<>"']/g,ch=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'}[ch]));
  const statusName={AVAILABLE:'Disponível',REVIEW:'Requer atenção',OFFLINE:'Agente offline',DISABLED:'Desativada'};
  const fmt=n=>n==null?'—':`${Math.round(Number(n))}%`;
  const slug=()=>($c('cloud-slug').value||'').trim().toLowerCase();
  const pickServer=()=>state?.servers?.find(x=>x.id===$c('cloud-server').value);
  function selection(){
    const selected=$c('cloud-company').value;
    $c('cloud-company').innerHTML='<option value="">Selecione uma empresa</option>'+(window.companies||companies||[]).map(x=>`<option value="${safe(x.id)}">${safe(x.tradeName)}</option>`).join('');
    if(selected)$c('cloud-company').value=selected;
    const server=$c('cloud-server').value;
    $c('cloud-server').innerHTML='<option value="">Selecione um servidor</option>'+(state?.servers||[]).map(x=>`<option value="${safe(x.id)}" ${(!x.active||!x.remainingSlots)?'disabled':''}>${safe(x.name)} · ${safe(statusName[x.status]||x.status)} · ${x.remainingSlots} vaga(s)</option>`).join('');
    if(server)$c('cloud-server').value=server;
    const recommended=state?.servers?.find(x=>x.healthyForStart);
    // A primeira VPS online não é necessariamente adequada ao porte desta empresa.
    // Deixe a escolha em branco até avaliação técnica ou ranking do orquestrador.
    if(!state?.orchestratorEnabled && !$c('cloud-server').value && recommended)renderTip();
    renderTip();
  }
  function renderTip(){
    const server=pickServer();
    $c('cloud-server-tip').textContent=!server?'Cadastre uma VPS em “VPS e hospedagem” e instale o agente autorizado.':server.healthyForStart?'Agente online e métricas básicas dentro do limite. Use “Analisar as VPS” para verificar CPU, RAM e disco reservados antes de escolher.':`Servidor: ${statusName[server.status]||'Revisão necessária'}. A reserva pode ser feita, mas o provisionamento ficará bloqueado até o agente e a capacidade estarem adequados.`;
  }
  function render(){
    $c('cloud-count-servers').textContent=state.registeredServers;
    $c('cloud-count-online').textContent=state.onlineServers;
    $c('cloud-count-domains').textContent=state.domainCount;
    const servers=state.servers||[];
    $c('cloud-servers').innerHTML=servers.length?servers.map(x=>`<article class="cloud-server-card"><header><div><strong>${safe(x.name)}</strong><small>${safe(x.provider)}${x.hostname?' · '+safe(x.hostname):''}</small></div><span class="cloud-pill ${x.status==='AVAILABLE'?'available':x.status==='OFFLINE'?'offline':x.status==='DISABLED'?'disabled':''}">${safe(statusName[x.status]||x.status)}</span></header><div class="cloud-metrics"><div>CPU<b>${fmt(x.cpuPercent)}</b></div><div>RAM<b>${fmt(x.memoryPercent)}</b></div><div>Disco<b>${fmt(x.diskPercent)}</b></div></div><small>${x.usedCompanies} de ${x.maxCompanies} vagas ocupadas ou reservadas · ${x.agentOnline?'Agente online':'Aguardando o agente'}</small></article>`).join(''):'<div class="empty">Nenhuma VPS. Cadastre seu primeiro servidor na aba VPS e hospedagem.</div>';
    const domains=state.domains||[];
    $c('cloud-domains').innerHTML=domains.length?domains.map(x=>`<article class="cloud-domain-card"><strong>${safe(x.companyName)}</strong><code>${safe(x.hostname)}</code><small>Servidor: ${safe(x.vpsName)}</small>${x.dnsTarget?`<small>DNS: registro A → ${safe(x.dnsTarget)} (ou proxy central configurado)</small>`:''}<small>DNS: ${x.dnsStatus==='READY'?'Registro configurado':'Não configurado ou manual'}</small><small>Situação: ${({RESERVED:'Reservado, aguardando implantação',DEPLOYING:'Implantação em andamento',ACTIVE:'Ativo e validado',ERROR:'Falha na implantação'}[x.status]||safe(x.status))}</small>${x.status==='ACTIVE'?`<a href="https://${safe(x.hostname)}" target="_blank" rel="noopener noreferrer">Abrir plataforma validada ↗</a>`:''}</article>`).join(''):'<div class="empty">Nenhum endereço reservado. Escolha uma empresa acima para começar.</div>';
    selection();
    const mode=$c('cloud-orchestrator-mode');
    mode.textContent=state.orchestratorEnabled?'Ativo para reservas':'Homologação: reserva automática desativada';
    $c('cloud-configure-dns').disabled=!(state.orchestratorEnabled&&state.dnsProvider==='CLOUDFLARE'&&$c('cloud-company').value);
  }
  async function refresh(){
    $c('cloud-status').textContent='Consultando servidores e reservas...';
    try{state=await api('/api/platform/cloud/overview');render();$c('cloud-status').textContent='Dados atualizados. A disponibilidade depende de um agente ativo e métricas recentes.';}
    catch(error){$c('cloud-status').textContent=error.message;}
  }
  function preview(){
    const raw=slug();
    available=false;
    $c('cloud-domain-preview').textContent=(raw||'empresa')+'.'+(state?.baseDomain||'gestaosoftware.com.br');
    $c('cloud-domain-preview').style.borderColor='';
  }
  async function check(){
    msg('Verificando disponibilidade...');
    const id=++requestCounter;
    try{
      const result=await api('/api/platform/cloud/enderecos/disponibilidade?slug='+encodeURIComponent(slug())+($c('cloud-company').value?'&companyId='+encodeURIComponent($c('cloud-company').value):''));
      if(id!==requestCounter)return;
      available=result.available;
      $c('cloud-domain-preview').textContent=result.hostname;
      msg(result.alreadyReservedForCompany?'Esse endereço já está reservado para esta empresa.':result.available?'Endereço disponível para reservar.':'Esse endereço já está reservado. Escolha outro.',result.available?'success':'error');
    }catch(e){if(id===requestCounter)msg(e.message,'error');}
  }
  async function reserve(event){
    event.preventDefault();
    const companyId=$c('cloud-company').value, server=$c('cloud-server').value;
    if(!companyId||!server){msg('Escolha a empresa e o servidor.');return;}
    const button=$c('cloud-reserve');button.disabled=true;
    try{
      const result=await api('/api/platform/cloud/empresas/'+encodeURIComponent(companyId)+'/endereco',{
        method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({slug:slug(),vpsId:server})
      });
      msg('Endereço '+result.hostname+' reservado! Acesse “Implantação guiada” para concluir as próximas etapas.','success');
      available=false;await refresh();
    }catch(e){msg(e.message,'error')}finally{button.disabled=false;}
  }
  async function recommend(){
    const company=$c('cloud-company').value;
    const target=$c('cloud-recommendation');
    const button=$c('cloud-auto-reserve');
    const current=++recommendationCounter;
    recommendation=null;button.disabled=true;
    if(!company){target.textContent='Escolha uma empresa para analisar os servidores disponíveis.';return;}
    target.textContent='Analisando recursos reservados e métricas das VPS...';
    try{
      const result=await api('/api/platform/cloud/empresas/'+encodeURIComponent(company)+'/recomendacao');
      if(current!==recommendationCounter)return;
      recommendation=result;
      const candidates=result.candidates||[];
      target.innerHTML=candidates.length?`<div class="cloud-ranking">${candidates.map((item,i)=>`<div class="cloud-ranking-row"><div><strong>${safe(item.name)}</strong><small>${item.eligible?'Apta · pontuação '+safe(item.score):safe((item.reasons||[]).join(' · '))}</small></div><span class="cloud-pill ${item.eligible?'available':'disabled'}">${item.eligible?(i===0?'Recomendada':'Apta'):'Bloqueada'}</span></div>`).join('')}</div>`:'<p>Nenhuma VPS cadastrada. Cadastre e conecte pelo menos uma.</p>';
      const best=candidates.find(x=>x.eligible);
      if(best){$c('cloud-server').value=best.vpsId;renderTip();}
      button.disabled=!best||!result.enabledForAutomaticReservation;
    }catch(e){if(current===recommendationCounter)target.textContent=e.message;}
  }
  async function autoReserve(){
    const company=$c('cloud-company').value;
    if(!company||!slug()){msg('Escolha a empresa e informe o subdomínio.');return;}
    if(!recommendation?.enabledForAutomaticReservation){msg('Ative o orquestrador somente após homologação.','error');return;}
    const b=$c('cloud-auto-reserve');b.disabled=true;
    try{
      const result=await api('/api/platform/cloud/empresas/'+encodeURIComponent(company)+'/reservar-automaticamente',{
        method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({slug:slug()})
      });
      msg('A Gestão reservou '+result.hostname+' em uma VPS adequada. Continue no fluxo Implantação Guiada.','success');
      recommendation=null;await refresh();await recommend();
    }catch(e){msg(e.message,'error');await recommend();}
  }
  async function configureDns(){
    const company=$c('cloud-company').value;
    if(!company){msg('Selecione a empresa antes de configurar o DNS.');return;}
    const b=$c('cloud-configure-dns');b.disabled=true;
    try{
      const result=await api('/api/platform/cloud/empresas/'+encodeURIComponent(company)+'/configurar-dns',{method:'POST'});
      msg('DNS '+result.hostname+' configurado. Aguarde propagação e validação HTTPS.','success');
      await refresh();
    }catch(e){msg(e.message,'error')}finally{b.disabled=!(state?.orchestratorEnabled&&state?.dnsProvider==='CLOUDFLARE');}
  }
  function init(){
    $c('cloud-refresh').addEventListener('click',refresh);
    $c('cloud-recommend').addEventListener('click',recommend);
    $c('cloud-auto-reserve').addEventListener('click',autoReserve);
    $c('cloud-configure-dns').addEventListener('click',configureDns);
    $c('cloud-check').addEventListener('click',check);
    $c('cloud-slug').addEventListener('input',()=>{requestCounter++;preview();msg('')});
    $c('cloud-server').addEventListener('change',renderTip);
    $c('cloud-domain-form').addEventListener('submit',reserve);
    $c('cloud-company').addEventListener('change',()=>{
       recommendation=null;
       $c('cloud-configure-dns').disabled=!(state?.orchestratorEnabled&&state?.dnsProvider==='CLOUDFLARE'&&$c('cloud-company').value);
       recommend();
       const found=state?.domains?.find(d=>d.companyId===$c('cloud-company').value);
       if(found){$c('cloud-slug').value=found.hostname.slice(0,-(state.baseDomain.length+1));$c('cloud-server').value=found.vpsId;preview();renderTip();}
    });
    const observer=new MutationObserver(()=>{if($c('tab-cloud').classList.contains('active'))refresh()});
    observer.observe($c('tab-cloud'),{attributes:true,attributeFilter:['class']});
    preview();
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',init,{once:true});else init();
})();
