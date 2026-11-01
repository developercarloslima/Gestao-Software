/* Gestão Software v102 — implantação guiada; o servidor decide o que está liberado. */
(()=>{
  'use strict';
  const $=id=>document.getElementById(id);
  const root='/api/platform/implantacao';
  let companyId=null, draft=null, currentEstimate=null, capabilities=null, lastReady=null, financialProvider=null;
  let currentJobs=[],lastCompany=null, loadSerial=0;
  const labels={QUEUED:'Na fila',PROVISIONING:'Instalando serviços',VERIFYING:'Verificando endereço',ACTIVE:'Pronto para uso',FAILED:'Falhou — exige atenção'};
  const formRequest=(payload,method='POST')=>({method,headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
  const selectedModules=()=>Array.from(document.querySelectorAll('[data-size-module]:checked')).map(i=>i.value);
  const count=id=>Number($(id).value || 0);
  const toText=(value)=>String(value ?? '');
  const hint=(text,error=false)=>{
    const el=$('onboarding-message');el.textContent=text;
    el.className='form-message '+(error?'error':'success');
  };
  function readForm(){return {employees:count('size-employees'),simultaneousUsers:count('size-concurrent'),salesPerMonth:count('size-sales'),paymentsPerMonth:count('size-payments'),workDaysPerMonth:count('size-days'),marksPerDay:count('size-marks'),photoKilobytes:count('size-photo-kb'),retentionDays:count('size-retention'),faceRecognition:$('size-face').checked,highAvailability:$('size-ha').checked,hosting:$('size-provider').value,modules:selectedModules()};}
  function renderEstimate(x){
    currentEstimate=x;
    if(!x){$('size-result').textContent='Preencha os campos e clique em Analisar capacidade.';return;}
    $('size-result').innerHTML=`<p><strong>${esc(x.tier)} — análise inicial</strong></p><div class="onboarding-kpis"><div><small>CPU inicial estimada</small><strong>${Number(x.recommendedCpuVcpu).toLocaleString('pt-BR')} vCPU</strong></div><div><small>Memória sugerida</small><strong>${Number(x.recommendedRamGb).toLocaleString('pt-BR')} GB</strong></div><div><small>Armazenamento estimado</small><strong>${Number(x.estimatedStorageGb).toLocaleString('pt-BR')} GB</strong></div><div><small>Marcações de ponto/mês</small><strong>${Number(x.estimatedMarksPerMonth).toLocaleString('pt-BR')}</strong></div></div><p>${esc(x.nextStep)}</p><small>${esc(x.explanation)}</small>`;
  }
  const checked=(label,ok,detail)=>`<div class="onboarding-check"><span class="${ok?'ok-icon':'wait-icon'}" aria-hidden="true">${ok?'✓':'!'}</span><div><strong>${esc(label)}</strong><small>${esc(detail)}</small></div></div>`;
  function stages(ready={},jobs=[]){
    const live=jobs.some(j=>j.status==='ACTIVE');
    const queued=jobs.some(j=>['QUEUED','PROVISIONING','VERIFYING'].includes(j.status));
    const done={company:!!companyId,capacity:!!ready.capacityConfigured,contract:!!ready.contractSigned,payment:!!ready.paymentProviderVerified,deploy:queued||live,url:live};
    const first=Object.keys(done).find(key=>!done[key]);
    document.querySelectorAll('#onboarding-steps [data-stage]').forEach(node=>{
      const stage=node.dataset.stage;
      node.classList.toggle('is-complete',done[stage]);
      node.classList.toggle('is-current',stage===first);
      node.classList.toggle('is-blocked',stage==='deploy' && !ready.environmentCertified && !!companyId);
      if(stage===first)node.setAttribute('aria-current','step');else node.removeAttribute('aria-current');
    });
  }
  function nextAction(ready={},jobs=[]){
    const el=$('onboarding-next-action');
    const active=jobs.find(j=>j.status==='ACTIVE');
    const pending=jobs.find(j=>['QUEUED','PROVISIONING','VERIFYING'].includes(j.status));
    if(!companyId){el.textContent='Comece escolhendo uma empresa cadastrada ou clique em Cadastrar empresa.';return;}
    if(active){el.innerHTML=`<strong>Implantação concluída e endereço validado.</strong> Use o histórico abaixo para abrir o endereço verificado.`;return;}
    if(pending){el.innerHTML=pending.status==='VERIFYING'?'<strong>Aplicação instalada, mas ainda não liberada.</strong> Abra as configurações da nova empresa no histórico, conclua catálogo, identidade visual, regulamento e testes. Depois clique em Verificar implantação. A URL continua bloqueada para o cliente.':`<strong>Implantação em andamento.</strong> Situação: ${esc(labels[pending.status])}. A URL só será liberada após validação de HTTPS, identidade e configuração.`;return;}
    if(!ready.adminConfigured){el.innerHTML='<strong>Próximo passo:</strong> edite a empresa e informe o usuário do administrador que será criado no ambiente do cliente.';return;}
    if(!ready.hasModules){el.innerHTML='<strong>Próximo passo:</strong> edite a empresa e escolha os módulos do contrato.';return;}
    if(!ready.capacityConfigured){el.innerHTML='<strong>Próximo passo:</strong> responda às perguntas à esquerda, clique em Analisar capacidade e depois em Confirmar e salvar análise.';return;}
    if(!ready.contractSigned){el.innerHTML='<strong>Próximo passo:</strong> abra a empresa em Empresas e finalize as assinaturas do cliente e da Gestão Software.';return;}
    if(!ready.paymentProviderVerified){el.innerHTML='<strong>Próximo passo:</strong> selecione a fatura, gere a cobrança e confirme o recebimento pelo Asaas. Alterar manualmente o status não libera o deploy.';return;}
    if(!ready.companyAllowed){el.innerHTML='<strong>Próximo passo:</strong> confirme que a empresa está ativa e pode receber os módulos.';return;}
    if(!ready.providerSupported){el.innerHTML='<strong>Infraestrutura ainda não automatizada.</strong> AWS, Render e VPS do cliente precisam de conectores homologados. Selecione uma opção suportada ou solicite uma revisão técnica.';return;}
    if(!ready.capacityApproved){el.innerHTML='<strong>Revisão de arquitetura obrigatória.</strong> Esta operação ultrapassa o perfil START e precisa de dimensionamento e teste de carga antes do deploy.';return;}
    if(!ready.environmentCertified){el.innerHTML='<strong>Implantação de clientes reais bloqueada.</strong> A versão-base dos módulos ainda precisa passar na auditoria white-label, testes de segurança, pagamento e restauração.';return;}
    el.innerHTML='<strong>Requisitos concluídos.</strong> Solicite implantação; a URL será publicada após validação automática.';
  }
  function renderReadiness(ready){
    lastReady=ready;
    const checks=[
      ['Administrador da empresa configurado',ready.adminConfigured,'Informe o usuário administrador no cadastro da empresa.'],
      ['Módulos contratados',ready.hasModules,'Escolha os módulos na ficha da empresa.'],
      ['Capacidade calculada e salva',ready.capacityConfigured,'A análise deve usar os módulos do contrato atual.'],
      ['Contrato assinado pelas duas partes',ready.contractSigned,'As duas assinaturas devem constar no contrato.'],
      ['Pagamento confirmado no Asaas',ready.paymentProviderVerified,'O servidor confere a cobrança diretamente no provedor.'],
      ['Empresa ativa',ready.companyAllowed,'Empresas suspensas ou canceladas não podem ser implantadas.'],
      ['Provedor com automação autorizada',ready.providerSupported,'Somente VPS gerenciada START está integrada neste estágio.'],
      ['Capacidade aprovada para automação',ready.capacityApproved,'Operações Business/Enterprise precisam de revisão técnica.'],
      ['Base universal certificada',ready.environmentCertified,'A base NH ainda possui pontos de marca e integrações a substituir.']
    ];
    $('provision-readiness').innerHTML='<div class="onboarding-checks">'+checks.map(([a,b,c])=>checked(a,b,b?'Concluído.':c)).join('')+'</div><small>'+esc(ready.warning||'')+'</small>';
    const eligible=Boolean(ready.canQueue);
    $('queue-deployment').disabled=!eligible;
    $('queue-deployment').title=eligible?'Solicitar instalação no ambiente configurado.':'Corrija as pendências listadas antes de implantar.';
    stages(ready,currentJobs);nextAction(ready,currentJobs);
  }
  function renderJobs(jobs=[]){
    currentJobs=jobs;
    $('deployment-history').innerHTML=jobs.length?jobs.map(j=>{
      const url=j.status==='ACTIVE'?safeUrl(j.app_url):null;
      const wizard=j.status==='VERIFYING' && j.app_url?safeUrl(String(j.app_url).replace(/\/+$/,'')+'/admin/implantacao.html'):null;
      const btn=j.status==='VERIFYING'?`<div class="form-actions">${wizard?`<a href="${esc(wizard)}" target="_blank" rel="noopener noreferrer" class="button outline small">Abrir configuração da empresa ↗</a>`:''}<button type="button" class="button outline small" data-verify-job="${esc(j.id)}">Conferir todos os requisitos</button></div>`:'';
      return `<article class="onboarding-deployment"><header><span class="onboarding-state ${j.status==='ACTIVE'?'active':j.status==='FAILED'?'failed':''}">${esc(labels[j.status]||j.status)}</span><small>${esc(j.created_at||'')}</small></header><p><strong>Endereço:</strong> ${esc(j.requested_host)}</p><p>${esc(j.last_message||'Aguardando atualização do provedor.')}</p>${url?`<div class="form-actions"><a class="button outline" href="${esc(url)}" target="_blank" rel="noopener noreferrer">Abrir sistema liberado ↗</a><button type="button" class="button outline" data-copy-app-url="${esc(url)}">Copiar endereço para o cliente</button></div>`:''}${btn}</article>`;
    }).join(''):'<p>Não existe implantação para esta empresa. Conclua as etapas acima para iniciar.</p>';
    stages(lastReady||{},jobs);nextAction(lastReady||{},jobs);
  }
  function providerNotice(){
    const selected=$('size-provider').value;
    const provider=capabilities?.providers?.find(p=>p.code===selected);
    const el=$('onboarding-provider-notice');
    if(!provider){el.textContent='A integração de infraestrutura ainda não foi verificada.';el.classList.add('is-unavailable');return;}
    el.textContent=provider.provisionable?'VPS gerenciada: agente inicial disponível para empresas pequenas. Exige VPS ativa, DNS e imagens Docker certificadas.':'Essa hospedagem ainda precisa de conector: '+provider.note;
    el.classList.toggle('is-unavailable',!provider.provisionable);
  }
  async function populate(){
    try{
      const [companies,modules,caps,payment]=await Promise.all([api('/api/platform/companies'),api('/api/platform/modules'),api(`${root}/capacidades`),api('/api/platform/finance/provider')]);
      capabilities=caps;
      financialProvider=payment;
      $('payment-create').disabled=!payment.configured;
      $('payment-verify').disabled=!payment.configured||payment.provider!=='ASAAS';
      $('onboarding-financial-provider').textContent=payment.configured
        ? `Financeira ${payment.provider} conectada e apta a gerar cobranças.`
        : `Financeira aguardando configuração (${payment.provider}). A emissão e confirmação bancária estão bloqueadas.`;
      const old=$('onboarding-company').value;
      $('onboarding-company').innerHTML='<option value="">Selecione uma empresa</option>'+companies.map(c=>`<option value="${esc(c.id)}">${esc(c.tradeName)}</option>`).join('');
      if(companies.some(c=>String(c.id)===old))$('onboarding-company').value=old;
      $('size-modules').innerHTML=modules.map(m=>`<label class="module-option"><input type="checkbox" data-size-module value="${esc(m.code)}"><span><strong>${esc(m.name)}</strong><small>${esc(m.description||'')}</small></span></label>`).join('');
      providerNotice();
      if(old && $('onboarding-company').value===old)await loadCompany();
    }catch(e){hint(e.message,true);}
  }
  async function loadCompany(){
    companyId=$('onboarding-company').value||null;
    ++loadSerial;
    draft=null;currentEstimate=null;lastReady=null;currentJobs=[];lastCompany=null;
    $('size-save-btn').disabled=true;$('queue-deployment').disabled=true;
    $('payment-checkout').hidden=true;$('payment-asaas-id').value='';
    if(!companyId){$('provision-readiness').textContent='Selecione uma empresa para começar.';$('payment-invoice').innerHTML='<option value="">Selecione uma empresa</option>';renderEstimate(null);renderJobs([]);return;}
    const selectedId=companyId, requestId=++loadSerial;
    try{
      const [company,assessment,ready,jobs]=await Promise.all([
        api(`/api/platform/companies/${selectedId}`),api(`${root}/empresas/${selectedId}/dimensionamento`),
        api(`${root}/empresas/${selectedId}/prontidao`),api(`${root}/empresas/${selectedId}/jobs`)
      ]);
      if(requestId!==loadSerial || selectedId!==companyId)return; // evita mostrar empresa errada por resposta atrasada
      lastCompany=company;
      const modset=new Set((company.modules||[]).filter(m=>m.enabled).map(m=>m.code));
      document.querySelectorAll('[data-size-module]').forEach(ch=>{ch.checked=modset.has(ch.value)});
      $('payment-invoice').innerHTML='<option value="">Escolha a fatura</option>'+(company.invoices||[]).map(i=>`<option value="${esc(i.id)}">${esc(i.referenceMonth)} · ${money(i.amount)} · ${esc(statusLabel(i.status))}</option>`).join('');
      if(assessment.input){
        const data=assessment.input;
        const map={employees:'size-employees',simultaneousUsers:'size-concurrent',salesPerMonth:'size-sales',paymentsPerMonth:'size-payments',workDaysPerMonth:'size-days',marksPerDay:'size-marks',photoKilobytes:'size-photo-kb',retentionDays:'size-retention',hosting:'size-provider'};
        Object.entries(map).forEach(([name,id])=>{if(data[name]!==undefined)$(id).value=data[name]});
        $('size-face').checked=Boolean(data.faceRecognition);$('size-ha').checked=Boolean(data.highAvailability);
        draft={...data,modules:data.modules||[]};renderEstimate(assessment.estimate);
      } else renderEstimate(null);
      renderJobs(jobs);renderReadiness(ready);providerNotice();
      hint('Empresa selecionada. O sistema destaca o que falta para colocá-la em operação.');
    }catch(e){if(requestId===loadSerial)hint(e.message,true);}
  }
  function invalidate(){draft=null;$('size-save-btn').disabled=true;if(currentEstimate){renderEstimate(null);hint('Dados alterados: clique novamente em Analisar capacidade para atualizar a estimativa.');}}
  $('sizing-form').addEventListener('input',invalidate);
  $('sizing-form').addEventListener('change',()=>{invalidate();providerNotice()});
  $('sizing-form').addEventListener('submit',async e=>{
    e.preventDefault();const input=readForm();
    try{
      const result=await api(`${root}/dimensionar`,formRequest(input));draft=input;renderEstimate(result);
      $('size-save-btn').disabled=!companyId;hint('Análise concluída. Clique em Confirmar e salvar análise na empresa para registrar.');
    }catch(err){draft=null;$('size-save-btn').disabled=true;hint(err.message,true);}
  });
  $('size-save-btn').addEventListener('click',async()=>{
    if(!companyId||!draft)return;
    try{await api(`${root}/empresas/${companyId}/dimensionamento`,formRequest(draft,'PUT'));await loadCompany();hint('Dimensionamento salvo. Confira o próximo passo destacado acima.');}
    catch(err){hint(err.message,true);}
  });
  $('refresh-readiness').addEventListener('click',loadCompany);
  $('payment-create').addEventListener('click',async()=>{
    const invoice=$('payment-invoice').value;
    if(!companyId||!invoice){hint('Escolha uma empresa e uma fatura.',true);return;}
    if(!confirm('Esta ação cria uma cobrança na financeira configurada. Confira empresa, valor e vencimento antes de continuar.'))return;
    const btn=$('payment-create');btn.disabled=true;
    try{
      const result=await api(`${root}/faturas/${invoice}/gerar-cobranca`,{method:'POST'});
      $('payment-asaas-id').value=result.paymentId||'';
      const url=safeUrl(result.invoiceUrl);
      $('payment-checkout').hidden=false;
      $('payment-checkout').innerHTML=url?`<p><strong>Cobrança criada, ainda não paga.</strong></p><p><a href="${esc(url)}" target="_blank" rel="noopener noreferrer">Abrir cobrança ↗</a></p><small>ID: ${esc(result.paymentId)}</small>`:'Cobrança criada. Aguarde confirmação bancária.';
      hint('Cobrança criada. A implantação permanece bloqueada até confirmação efetiva do pagamento.');
    }catch(err){hint(err.message,true);}finally{btn.disabled=!financialProvider?.configured;}
  });
  $('payment-verify').addEventListener('click',async()=>{
    const invoice=$('payment-invoice').value,id=$('payment-asaas-id').value.trim();
    if(!companyId||!invoice||!id){hint('Selecione a fatura e informe o ID Asaas da cobrança.',true);return;}
    try{await api(`${root}/faturas/${invoice}/verificar-asaas`,formRequest({paymentId:id}));await loadCompany();hint('Pagamento validado diretamente no provedor.',false);}
    catch(err){hint(err.message,true);}
  });
  $('queue-deployment').addEventListener('click',async()=>{
    if(!companyId || !lastReady?.canQueue)return;
    if(!confirm('A implantação pode alocar recursos na VPS configurada e gerar custos. Deseja confirmar a solicitação?'))return;
    try{await api(`${root}/empresas/${companyId}/solicitar`,{method:'POST'});await loadCompany();hint('Job autorizado. A URL só será liberada depois de confirmar o ambiente.');}
    catch(err){hint(err.message,true);}
  });
  $('onboarding-company').addEventListener('change',loadCompany);
  $('onboarding-new-company').addEventListener('click',()=>{activateTab('companies');$('new-company').click();});
  $('payment-invoice').addEventListener('change',()=>{$('payment-asaas-id').value='';$('payment-checkout').hidden=true;});
  document.addEventListener('click',async e=>{
    const copy=e.target.closest('[data-copy-app-url]');
    if(copy){try{await navigator.clipboard.writeText(copy.dataset.copyAppUrl);hint('URL verificada copiada. Envie ao responsável da empresa por canal confiável.');}catch(err){hint('Não foi possível copiar automaticamente. Abra o endereço liberado e copie pela barra do navegador.',true);}return;}
    const btn=e.target.closest('[data-verify-job]');if(!btn)return;
    btn.disabled=true;
    try{const job=await api(`${root}/jobs/${btn.dataset.verifyJob}/validar`,{method:'POST'});await loadCompany();hint(job.status==='ACTIVE'?'HTTPS e identidade confirmados. A URL está pronta para uso.':'A verificação ainda não passou. Confira DNS, certificado e identidade do ambiente.',job.status!=='ACTIVE');}
    catch(err){hint(err.message,true);}finally{btn.disabled=false;}
  });
  // Depois de criar a empresa, o atendente é direcionado ao próximo passo automaticamente.
  window.addEventListener('gestao:company-created',async event=>{
    const id=String(event.detail?.id||'');if(!id)return;
    activateTab('onboarding');await populate();
    if(Array.from($('onboarding-company').options).some(option=>option.value===id)){
      $('onboarding-company').value=id;await loadCompany();
      $('onboarding-next-action').scrollIntoView({behavior:'smooth',block:'center'});
    }
  });
  const tab=document.querySelector('[data-tab="onboarding"]');
  if(tab)tab.addEventListener('click',populate);
  stages();nextAction();
})();
