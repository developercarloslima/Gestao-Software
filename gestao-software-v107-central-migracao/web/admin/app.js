const TOKEN_KEY='gsControlToken';
const ROLE_KEY='gsControlRole';
const CONSULTANT_KEY='gsControlContext';
const LAST_ACTIVITY_KEY='gsControlLastActivityAt';
const $=id=>document.getElementById(id);
const brl=new Intl.NumberFormat('pt-BR',{style:'currency',currency:'BRL'});
const pct=new Intl.NumberFormat('pt-BR',{minimumFractionDigits:0,maximumFractionDigits:2});
let dashboard=null, companies=[], modules=[], expenses=[], vps=[], currentCompany=null, currentUser=null;
const PLAN_CALCULATOR_USERS=new Set(['carlos.lima','pedro.henrique']);
const GESTAO_DEFAULT_LOGO='/assets/logo-gestao-software.png';
let calculatorReference=null;
let calcAwsManual=false;

function token(){return localStorage.getItem(TOKEN_KEY)}
function apiUrl(path){return window.NH_API?.backend(path)||path}
function esc(value){return String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'}[c]))}
function money(value){const n=Number(value||0);return brl.format(Number.isFinite(n)?n:0)}
function numberOrNull(value){const s=String(value??'').trim();if(!s)return null;const n=Number(s);return Number.isFinite(n)?n:null}
function dateOnly(value){if(!value)return '—';const [y,m,d]=String(value).slice(0,10).split('-');return y&&m&&d?`${d}/${m}/${y}`:'—'}
function monthLabel(value){if(!value)return '—';const [y,m]=String(value).slice(0,7).split('-');return `${m}/${y}`}
function safeUrl(value){try{const u=new URL(String(value||''));return ['http:','https:'].includes(u.protocol)?u.href:null}catch(_){return null}}
function statusLabel(value){return ({ACTIVE:'Ativa',TRIAL:'Teste',SUSPENDED:'Suspensa',CANCELLED:'Excluída (arquivo)',PENDING:'Pendente',PAID:'Pago',OVERDUE:'Vencido',PENDING_SIGNATURES:'Aguardando assinaturas',PARTIALLY_SIGNED:'Parcialmente assinado',SIGNED:'Assinado'})[value]||value||'—'}
function statusClass(value){return ({ACTIVE:'ok',PAID:'ok',SIGNED:'ok',TRIAL:'warn',PENDING:'warn',PENDING_SIGNATURES:'warn',PARTIALLY_SIGNED:'warn',OVERDUE:'bad',SUSPENDED:'off',CANCELLED:'off'})[value]||'off'}
function badge(value){return `<span class="badge ${statusClass(value)}">${esc(statusLabel(value))}</span>`}
function emptyRow(cols,text){return `<tr><td class="empty" colspan="${cols}">${esc(text)}</td></tr>`}
function setMessage(text='',type='error'){const el=$('page-message');if(!text){el.hidden=true;return}el.hidden=false;el.className=`page-message ${type}`;el.textContent=text}
function formMessage(id,text='',type='error'){const el=$(id);if(!el)return;el.className=text?`form-message ${type}`:'form-message';el.textContent=text}
function currentMonth(){return $('reference-month').value||new Date().toISOString().slice(0,7)}
function today(){return new Date().toISOString().slice(0,10)}
function paymentMethodLabel(value){return ({PIX_AUTOMATICO:'Pix Automático',DEBITO_AUTOMATICO:'Débito Automático',BOLETO:'Boleto'})[value]||value||'—'}
function contractTermLabel(value){return ({MONTHLY:'Mensal',SEMIANNUAL:'Semestral',ANNUAL:'Anual'})[value]||value||'—'}
function canManageCompanyLifecycle(){return PLAN_CALCULATOR_USERS.has(String(currentUser?.username||'').trim().toLowerCase())}

function confirmCompanyAction({title,description,confirmLabel,companyName='',requireName=false,danger=false}){
  return new Promise(resolve=>{
    const dialog=$('company-action-dialog'),card=$('company-action-form'),confirmButton=$('company-action-confirm');
    if(!dialog||dialog.open){resolve(false);return}
    dialog.returnValue='';
    card.classList.toggle('danger-mode',danger);
    $('company-action-kicker').textContent=danger?'EXCLUSÃO ADMINISTRATIVA':'ADMINISTRAÇÃO DE EMPRESAS';
    $('company-action-title').textContent=title;
    $('company-action-description').textContent=description;
    confirmButton.textContent=confirmLabel;
    $('company-action-name-block').hidden=!requireName;
    $('company-action-expected-name').textContent=companyName;
    const input=$('company-action-name');input.value='';
    const validate=()=>{confirmButton.disabled=requireName&&input.value.trim()!==companyName};
    input.addEventListener('input',validate);
    validate();
    dialog.addEventListener('close',()=>{
      input.removeEventListener('input',validate);
      resolve(dialog.returnValue==='confirm'&&!confirmButton.disabled);
    },{once:true});
    dialog.showModal();
    if(requireName)input.focus();
  });
}

async function api(path,options={}){
  const headers=new Headers(options.headers||{});if(token())headers.set('Authorization',`Bearer ${token()}`);
  const response=await fetch(apiUrl(path),{...options,headers});
  if(!response.ok){
    const raw=await response.text().catch(()=>'');
    let body=null;try{body=JSON.parse(raw)}catch(_){/* Proxy pode retornar HTML. */}
    if(response.status===401||response.status===403){
      logout(response.status===403?'Acesso administrativo não autorizado. Entre novamente.':'Sua sessão expirou. Entre novamente.');
      throw new Error(response.status===403?'Acesso não autorizado.':'Sessão expirada.');
    }
    if(response.status===413)throw new Error('Arquivo muito grande para o servidor (HTTP 413). O PDF deve ter até 20 MB e a logo até 5 MB. Confira também client_max_body_size do Nginx.');
    if(response.status===502||response.status===503||response.status===504)throw new Error(`API da Gestão Software indisponível (HTTP ${response.status}). Verifique docker compose ps e logs do backend.`);
    throw new Error(body?.message||body?.detail||body?.error||`Não foi possível concluir (HTTP ${response.status}).`);
  }
  if(response.status===204)return null;
  const text=await response.text();
  if(!text)return null;
  try{return JSON.parse(text)}catch(_){throw new Error('A API retornou uma resposta inesperada. Confira os logs do backend.');}
}

function logout(message=''){
  localStorage.removeItem(TOKEN_KEY);localStorage.removeItem(ROLE_KEY);localStorage.removeItem(CONSULTANT_KEY);localStorage.removeItem(LAST_ACTIVITY_KEY);
  if(message)sessionStorage.setItem('nhPortalLoginMessage',message);
  location.replace('/admin/login.html');
}

function activateTab(name){
  if(name==='calculator'&&!PLAN_CALCULATOR_USERS.has(String(currentUser?.username||'').toLowerCase()))return;
  document.querySelectorAll('.platform-tabs button').forEach(b=>b.classList.toggle('active',b.dataset.tab===name));
  document.querySelectorAll('.tab-panel').forEach(p=>p.classList.toggle('active',p.id===`tab-${name}`));
  if(name==='finance')$('expense-month').value=currentMonth();
}

async function boot(){
  if(!token()){logout('Entre com seu usuário do Control Plane da Gestão Software.');return}
  try{
    const me=await api('/api/auth/me');currentUser=me;localStorage.setItem(ROLE_KEY,me.role);
    if(me.role!=='PLATFORM_ADMIN'){logout('Acesso restrito aos administradores da Gestão Software.');return}
    $('owner-name').textContent=me.displayName||me.username;
    const canUseCalculator=PLAN_CALCULATOR_USERS.has(String(me.username||'').toLowerCase());
    $('plan-calculator-tab-button').hidden=!canUseCalculator;$('tab-calculator').hidden=!canUseCalculator;
    if(me.passwordChangeRequired){$('app').hidden=true;openStaticDialog($('password-dialog'));return}
    $('app').hidden=false;
    const now=new Date();$('reference-month').value=`${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}`;$('expense-month').value=$('reference-month').value;
    await reloadAll();
    if(canUseCalculator){calculatorReference=await api('/api/platform/pricing-calculator');populateCompanyPlanOptions();resetPlanCalculator();renderPlanCalculator();}
  }catch(error){if(token())setMessage(error.message)}
}

function openStaticDialog(dialog){if(!dialog.open)dialog.showModal();dialog.addEventListener('cancel',e=>e.preventDefault(),{once:true})}

async function reloadAll(){
  setMessage('');
  try{
    [dashboard,companies,modules,expenses,vps]=await Promise.all([
      api(`/api/platform/dashboard?month=${encodeURIComponent(currentMonth())}`),api('/api/platform/companies'),api('/api/platform/modules'),
      api(`/api/platform/expenses?month=${encodeURIComponent(currentMonth())}`),api('/api/platform/vps')
    ]);
    renderDashboard();renderCompanies();renderExpenses();renderVps();
  }catch(error){setMessage(error.message)}
}

function renderDashboard(){
  if(!dashboard)return;
  $('kpi-gross').textContent=money(dashboard.grossRevenue);$('kpi-net').textContent=money(dashboard.netOperationalRevenue);$('kpi-mrr').textContent=money(dashboard.contractedMrr);$('kpi-delinquent').textContent=dashboard.delinquentCompaniesCount||0;
  $('finance-billed').textContent=money(dashboard.billedRevenue);$('finance-gross').textContent=money(dashboard.grossRevenue);$('finance-expenses').textContent=money(dashboard.manualOperatingExpenses);$('finance-infra').textContent=money(dashboard.infrastructureCost);$('finance-net').textContent=money(dashboard.netOperationalRevenue);
  renderPartners('partner-overview',dashboard.partners);renderPartners('partner-finance',dashboard.partners);
  const pedro=dashboard.partners?.find(p=>p.code==='PEDRO_HENRIQUE'),carlos=dashboard.partners?.find(p=>p.code==='CARLOS_LIMA');
  if(pedro)$('pedro-share').value=pedro.profitSharePercent;if(carlos)$('carlos-share').value=carlos.profitSharePercent;
  const c=dashboard.capacity||{};const box=$('capacity-status');box.className=`capacity-status ${c.needMoreVps?'warn':'ok'}`;box.innerHTML=`<strong>${c.needMoreVps?'Planejar capacidade adicional':'Capacidade adequada'}</strong><div>${esc(c.message||'')}</div>`;
  $('capacity-companies').textContent=`${c.plannedCompanies||0} / ${c.totalCapacityCompanies||0}`;$('capacity-vps').textContent=c.activeVps||0;$('capacity-risk').textContent=`${pct.format(Number(c.highestResourceUsePercent||0))}%`;
  const del=dashboard.delinquentCompanies||[];$('overview-delinquent-body').innerHTML=del.length?del.map(item=>`<tr><td class="company-name"><strong>${esc(item.tradeName)}</strong><small>${esc(item.legalName)}</small></td><td>${esc(item.groupName||'—')}</td><td>${dateOnly(item.oldestDueDate)}</td><td>${item.daysOverdue} dias</td><td><strong>${money(item.overdueAmount)}</strong></td><td><button class="button outline small" data-open-company="${item.id}" type="button">Abrir</button></td></tr>`).join(''):emptyRow(6,'Nenhuma empresa inadimplente.');
  const banner=$('vps-alert');banner.className=`capacity-banner ${c.needMoreVps?'warn':'ok'}`;banner.textContent=c.message||'';
}

function renderPartners(target,items=[]){$(target).innerHTML=items.map(p=>`<div class="partner-card"><span>${esc(p.name)}</span><strong>${money(p.estimatedAmount)}</strong><span class="share">${pct.format(Number(p.profitSharePercent))}% do líquido operacional</span></div>`).join('')}

function renderCompanies(){
  const q=String($('company-search').value||'').toLocaleLowerCase('pt-BR'),status=$('company-status-filter').value;
  const filtered=companies.filter(c=>(status?c.status===status:c.status!=='CANCELLED')&&(!q||[c.tradeName,c.legalName,c.groupName,c.document,c.contactName].some(v=>String(v||'').toLocaleLowerCase('pt-BR').includes(q))));
  $('companies-body').innerHTML=filtered.length?filtered.map(c=>{
    const actions=canManageCompanyLifecycle()
      ? (c.status==='SUSPENDED'
          ? `<button class="button outline small" data-reactivate-company="${c.id}" type="button">Reativar</button>`
          : c.status==='CANCELLED'
            ? `<button class="button outline small" data-reactivate-company="${c.id}" type="button">Restaurar cadastro</button>`
            : `<button class="button outline small danger-soft" data-deactivate-company="${c.id}" type="button">Desativar</button>`)
        +(c.status==='CANCELLED'?'':`<button class="button outline small danger-soft" data-delete-company="${c.id}" type="button">Excluir</button>`)
      : '';
    return `<tr><td class="company-name"><strong>${esc(c.tradeName)}</strong><small>${esc(c.legalName)}${c.document?` · ${esc(c.document)}`:''}</small><small>${esc(c.platformDisplayName||c.tradeName)} · ${esc(c.platformLegalName||'Gestão Software LTDA')}</small></td><td>${esc(c.groupName||'—')}${c.pointSuperAdminEligible?'<br><span class="badge ok">Super Admin Ponto</span>':''}</td><td>${esc(c.planName||'—')}</td><td>${c.moduleCount||0}</td><td><strong>${money(c.monthlyFee)}</strong><br><small>dia ${c.billingDueDay}</small></td><td>${c.status==='CANCELLED'?badge(c.status):c.delinquent?'<span class="badge bad">Inadimplente</span>':badge(c.status)}</td><td>${c.vpsName?`<strong>${esc(c.vpsName)}</strong><br><small>${esc(c.vpsProvider||'')} · ${esc(c.vpsPlan||'')}</small>`:'<span class="badge warn">Não vinculada</span>'}</td><td><div class="table-actions"><button class="button outline small" data-open-company="${c.id}" type="button">Ver empresa</button><button class="button outline small" data-edit-company-row="${c.id}" type="button">Editar</button>${actions}</div></td></tr>`;
  }).join(''):emptyRow(8,'Nenhuma empresa encontrada.');
}

function renderExpenses(){
  $('expenses-body').innerHTML=expenses.length?expenses.map(e=>`<tr><td>${esc(e.category)}</td><td>${esc(e.description)}</td><td><strong>${money(e.amount)}</strong></td><td>${esc(e.notes||'—')}</td><td><button class="button outline small" data-edit-expense="${e.id}" type="button">Editar</button> <button class="button outline small" data-delete-expense="${e.id}" type="button">Excluir</button></td></tr>`).join(''):emptyRow(5,'Nenhuma despesa operacional lançada nesta competência.');
}

function progress(label,value){const n=Math.max(0,Math.min(100,Number(value||0))),kind=n>=90?'critical':n>=80?'warning':'';return `<div class="progress-row"><div class="progress-label"><span>${esc(label)}</span><strong>${pct.format(n)}%</strong></div><div class="progress ${kind}"><span style="width:${n}%"></span></div></div>`}
function renderVps(){
  $('vps-grid').innerHTML=vps.length?vps.map(node=>`<article class="vps-card ${node.capacityStatus==='CRITICAL'?'critical':node.capacityStatus==='ATTENTION'?'attention':''}"><div class="vps-card-head"><div><h3>${esc(node.name)}</h3><p>${esc(node.provider)}${node.planName?` · ${esc(node.planName)}`:''}</p></div>${node.active?badge(node.capacityStatus==='CRITICAL'?'OVERDUE':node.capacityStatus==='ATTENTION'?'PENDING':'ACTIVE'):badge('CANCELLED')}</div><div class="vps-meta"><div><span>Empresas</span><strong>${node.allocatedCompanies} / ${node.maxCompanies}</strong></div><div><span>Custo mensal</span><strong>${money(node.monthlyCost)}</strong></div><div><span>CPU / RAM</span><strong>${node.cpuCores??'—'} / ${node.ramGb??'—'} GB</strong></div><div><span>Disco</span><strong>${node.storageGb??'—'} GB</strong></div></div>${progress('Capacidade de empresas',node.companyUsePercent)}${node.cpuUsagePercent!=null?progress('CPU utilizada',node.cpuUsagePercent):''}${node.ramUsagePercent!=null?progress('RAM utilizada',node.ramUsagePercent):''}${node.storageUsagePercent!=null?progress('Armazenamento utilizado',node.storageUsagePercent):''}<div class="form-actions" style="margin-top:14px"><button class="button outline small" data-edit-vps="${node.id}" type="button">Editar / atualizar métricas</button></div></article>`).join(''):'<div class="empty">Nenhuma VPS cadastrada. Cadastre a infraestrutura para o sistema calcular a capacidade disponível.</div>';
}

function populateCompanyPlanOptions(){
  const select=$('company-plan');if(!select)return;
  const current=select.value||'CUSTOM';const plans=calculatorReference?.plans||[];
  select.innerHTML='<option value="CUSTOM">Personalizado</option>'+plans.map(p=>`<option value="${esc(p.code)}">${esc(p.name)}</option>`).join('');
  select.value=[...select.options].some(o=>o.value===current)?current:'CUSTOM';
}
function companyPlanByCode(code){return (calculatorReference?.plans||[]).find(p=>p.code===code)||null}
function selectedCompanyModuleCount(){return [...document.querySelectorAll('[data-module-code]')].filter(x=>x.checked).length}
function syncCompanyPricing(){
  const select=$('company-plan'),monthly=$('company-monthly'),help=$('company-price-help');if(!select||!monthly)return;
  const plan=companyPlanByCode(select.value||'CUSTOM');
  monthly.readOnly=false;
  if(help)help.textContent=plan?`${plan.name}: use a calculadora de planos para chegar ao valor e informe aqui a mensalidade final aprovada.`:'Plano personalizado: informe a mensalidade final aprovada.';
}
function resetCompanyForm(){
  $('company-form').reset();$('company-id').value='';$('company-tenant-admin').value='';$('company-dialog-title').textContent='Nova empresa';populateCompanyPlanOptions();$('company-plan').value='START';$('company-contract-term').value='MONTHLY';$('company-payment-method').value='BOLETO';$('company-monthly').value='0';$('company-due-day').value='10';$('company-status').value='ACTIVE';$('company-status').disabled=false;$('company-start').value=today();$('company-platform-legal').value='Gestão Software LTDA';setCompanyBrandColors('#070c40','#f0f000','#ffffff');$('company-default-landing').value='/colaborador/';$('company-tenant-host').value='';currentCompanyFileStatus={files:[],logoUrl:''};updateCompanyFileStatus();formMessage('company-form-message');renderModuleOptions([]);syncCompanyPricing();
}
function renderModuleOptions(selected=[]){const byCode=new Map((selected||[]).map(m=>[m.code,m]));$('company-modules').innerHTML=modules.map(m=>{const s=byCode.get(m.code);return `<label class="module-option"><input type="checkbox" data-module-code="${esc(m.code)}" ${s?.enabled?'checked':''}><span><strong>${esc(m.name)}</strong><small>${esc(m.description||'')}</small></span></label>`}).join('')}
function companyPayload(){const planCode=$('company-plan').value||'CUSTOM',plan=companyPlanByCode(planCode);return {groupName:$('company-group').value.trim()||null,legalName:$('company-legal').value.trim(),tradeName:$('company-trade').value.trim(),document:$('company-document').value.trim()||null,contactName:$('company-contact').value.trim()||null,contactEmail:$('company-email').value.trim()||null,contactPhone:$('company-phone').value.trim()||null,tenantAdminUsername:$('company-tenant-admin').value.trim().toLowerCase()||null,status:$('company-status').value,planCode,planName:plan?.name||'Personalizado',contractTerm:$('company-contract-term').value||'MONTHLY',monthlyFee:numberOrNull($('company-monthly').value)||0,billingDueDay:Number($('company-due-day').value||10),paymentMethod:$('company-payment-method').value||'BOLETO',contractStartDate:$('company-start').value||today(),contractEndDate:$('company-end').value||null,platformDisplayName:$('company-platform-display').value.trim()||$('company-trade').value.trim()||null,platformLegalName:$('company-platform-legal').value.trim()||'Gestão Software LTDA',logoUrl:$('company-logo-url').value.trim()||null,faviconUrl:$('company-favicon-url').value.trim()||null,primaryColor:$('company-primary-color').value||'#070c40',secondaryColor:$('company-secondary-color').value||'#f0f000',accentColor:$('company-accent-color').value||'#ffffff',siteTitle:$('company-site-title').value.trim()||$('company-trade').value.trim()||null,companyTagline:$('company-tagline').value.trim()||null,loginSubtitle:$('company-login-subtitle').value.trim()||null,defaultModuleLanding:$('company-default-landing').value.trim()||'/colaborador/',notes:$('company-notes').value.trim()||null,modules:[...document.querySelectorAll('[data-module-code]')].map(ch=>({code:ch.dataset.moduleCode,enabled:ch.checked,monthlyPrice:0}))}}
const BRAND_FIELDS=[['company-primary-color','company-primary-hex'],['company-secondary-color','company-secondary-hex'],['company-accent-color','company-accent-hex']];
function setCompanyBrandColors(primary,secondary,accent){
  [primary,secondary,accent].forEach((value,index)=>{
    const [pickerId,hexId]=BRAND_FIELDS[index],color=/^#[0-9a-f]{6}$/i.test(String(value||''))?String(value).toLowerCase():'#ffffff';
    $(pickerId).value=color;$(hexId).value=color;$(hexId).setCustomValidity('');
  });
}
BRAND_FIELDS.forEach(([pickerId,hexId])=>{
  $(pickerId).addEventListener('input',()=>{$(hexId).value=$(pickerId).value;$(hexId).setCustomValidity('')});
  $(hexId).addEventListener('input',()=>{
    const value=$(hexId).value.trim();
    if(/^#[0-9a-f]{6}$/i.test(value)){$(pickerId).value=value;$(hexId).setCustomValidity('');}
    else $(hexId).setCustomValidity('Use #RRGGBB, por exemplo #070C40.');
  });
});
$('brand-nh-preset').addEventListener('click',()=>setCompanyBrandColors('#070c40','#f0f000','#ffffff'));

function companyFileByPurpose(data,purpose){return (data.files||[]).find(f=>f.purpose===purpose)||null}
let currentCompanyFileStatus={files:[],logoUrl:''};
function updateCompanyFileStatus(){
  const logoSelected=$('company-logo-file')?.files?.[0];
  const pdfSelected=$('company-identity-file')?.files?.[0];
  const hasLogo=!!companyFileByPurpose(currentCompanyFileStatus,'LOGO')||!!currentCompanyFileStatus.logoUrl;
  const hasPdf=!!companyFileByPurpose(currentCompanyFileStatus,'VISUAL_IDENTITY_PDF');
  $('company-logo-current').textContent=logoSelected
    ? `Nova imagem selecionada: ${logoSelected.name}. Salve a empresa para enviar.`
    : hasLogo ? '✓ Imagem recebida e salva.' : 'Nenhuma logo enviada.';
  $('company-identity-current').textContent=pdfSelected
    ? `Novo PDF selecionado: ${pdfSelected.name}. Salve a empresa para enviar.`
    : hasPdf ? '✓ PDF recebido e salvo.' : 'Nenhum PDF enviado.';
}
$('company-logo-file').addEventListener('change',updateCompanyFileStatus);
$('company-identity-file').addEventListener('change',updateCompanyFileStatus);
async function saveCompanyTenantHosting(companyId){
  const host=$('company-tenant-host').value.trim().toLowerCase().replace(/^https?:\/\//,'').replace(/\/.*$/,'').replace(/:\d+$/,'');
  if(!host)return;
  if(!/^(?=.{4,240}$)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])$/.test(host)||!host.includes('.'))throw new Error('Informe um subdomínio válido, ex.: nh.gestaosoftware.com.br.');
  const company=await api(`/api/platform/companies/${companyId}`);
  const h=company.hosting||{};
  // Preserva VPS, domínio principal, rede e configurações já cadastradas em Hospedagem.
  await api(`/api/platform/companies/${companyId}/hosting`,{
    method:'PUT',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({
      vpsId:h.vpsId||null,environmentName:h.environmentName||null,domain:h.domain||null,
      subdomain:host,dnsMode:h.dnsMode||'SUBDOMAIN',appUrl:h.appUrl||null,apiUrl:h.apiUrl||null,
      databaseName:h.databaseName||null,deploymentPath:h.deploymentPath||null,
      deploymentMethod:h.deploymentMethod||null,hostingNotes:h.hostingNotes||null
    })
  });
}
async function uploadCompanyFiles(companyId){
  const uploads=[['company-logo-file','LOGO'],['company-identity-file','VISUAL_IDENTITY_PDF']];
  for(const [inputId,purpose] of uploads){const input=$(inputId);const file=input?.files?.[0];if(!file)continue;const form=new FormData();form.append('file',file);await api(`/api/platform/companies/${companyId}/files/${purpose}`,{method:'POST',body:form});}
}
async function editCompany(id){
  const data=await api(`/api/platform/companies/${id}`);
  // Os inputs nativos de arquivo nunca representam anexos já salvos.
  // Limpar seleções anteriores evita reaproveitar o arquivo de outra empresa.
  $('company-logo-file').value='';
  $('company-identity-file').value='';
  currentCompanyFileStatus={files:data.files||[],logoUrl:data.logoUrl||''};
  updateCompanyFileStatus();
  populateCompanyPlanOptions();
  $('company-id').value=data.id;
  $('company-dialog-title').textContent=`Editar ${data.tradeName}`;
  $('company-group').value=data.groupName||'';
  $('company-trade').value=data.tradeName||'';
  $('company-legal').value=data.legalName||'';
  $('company-document').value=data.document||'';
  $('company-contact').value=data.contactName||'';
  $('company-email').value=data.contactEmail||'';
  $('company-phone').value=data.contactPhone||'';
  $('company-tenant-admin').value=data.tenantAdminUsername||'';
  $('company-status').value=data.status;
  // Empresas suspensas podem ser reativadas também pelo formulário de edição.
  // Cadastros arquivados continuam exigindo a ação explícita Restaurar cadastro.
  $('company-status').disabled=data.status==='CANCELLED'||!canManageCompanyLifecycle();
  $('company-plan').value=data.planCode||'CUSTOM';
  $('company-contract-term').value=data.contractTerm||'MONTHLY';
  $('company-payment-method').value=data.paymentMethod||'BOLETO';
  $('company-monthly').value=Number(data.monthlyFee||0).toFixed(2);
  $('company-due-day').value=data.billingDueDay||10;
  $('company-start').value=data.contractStartDate||today();
  $('company-end').value=data.contractEndDate||'';
  $('company-platform-display').value=data.platformDisplayName||data.tradeName||'';
  $('company-platform-legal').value=data.platformLegalName||'Gestão Software LTDA';
  $('company-logo-url').value=data.logoUrl||'';
  $('company-favicon-url').value=data.faviconUrl||'';
  setCompanyBrandColors(data.primaryColor||'#070c40',data.secondaryColor||'#f0f000',data.accentColor||'#ffffff');
  $('company-site-title').value=data.siteTitle||data.tradeName||'';
  $('company-tagline').value=data.companyTagline||'';
  $('company-login-subtitle').value=data.loginSubtitle||'';
  $('company-default-landing').value=data.defaultModuleLanding||'/colaborador/';
  $('company-tenant-host').value=data.hosting?.subdomain||'';
  $('company-notes').value=data.notes||'';
  renderModuleOptions(data.modules||[]);
  syncCompanyPricing();
  $('company-dialog').showModal();
}


async function openCompany(id){
  try{currentCompany=await api(`/api/platform/companies/${id}`);$('detail-company-name').textContent=currentCompany.tradeName;$('detail-company-subtitle').textContent=[currentCompany.groupName,currentCompany.document,currentCompany.planName].filter(Boolean).join(' · ');renderCompanyDetail();$('company-detail-dialog').showModal()}catch(e){setMessage(e.message)}
}
function renderCompanyDetail(){
  const c=currentCompany;if(!c)return;
  const h=c.hosting,inv=c.invoices||[],contracts=c.contracts||[],files=c.files||[];
  const modulesHtml=(c.modules||[]).filter(m=>m.enabled).map(m=>`<span class="badge ok">${esc(m.name)}</span>`).join('')||'<span class="muted">Nenhum módulo habilitado</span>';
  const hUrl=safeUrl(h?.appUrl),apiLink=safeUrl(h?.apiUrl),logo=c.logoUrl||GESTAO_DEFAULT_LOGO;
  const identity=files.find(f=>f.purpose==='VISUAL_IDENTITY_PDF');
  const brandPreview=`<div class="hosting-box"><div style="display:flex;gap:16px;align-items:center;flex-wrap:wrap"><img src="${esc(logo)}" alt="Marca" style="max-width:220px;max-height:96px;object-fit:contain;background:#fff;border-radius:16px;padding:10px;border:1px solid rgba(11,92,255,.12)"><div><h4>${esc(c.platformDisplayName||c.tradeName||'Empresa')}</h4><div>${esc(c.platformLegalName||'Gestão Software LTDA')}</div><div style="margin-top:6px">${c.companyTagline?esc(c.companyTagline):'Sem slogan configurado.'}</div>${identity?`<div style="margin-top:10px"><a class="button outline small" href="${esc(identity.url)}" target="_blank" rel="noopener">Abrir PDF da identidade visual</a></div>`:''}<div style="margin-top:10px;display:flex;gap:8px;flex-wrap:wrap"><span class="badge ok">Principal ${esc(c.primaryColor||'#0b5cff')}</span><span class="badge ok">Secundária ${esc(c.secondaryColor||'#050b23')}</span><span class="badge ok">Apoio ${esc(c.accentColor||'#e5f0ff')}</span></div></div></div></div>`;
  const hostingHtml=h?`<div class="hosting-box"><h4>${esc(h.vpsName||'Hospedagem sem VPS vinculada')}</h4><div class="hosting-values"><div><span>Provedor / plano</span><span>${esc([h.provider,h.planName].filter(Boolean).join(' · ')||'—')}</span></div><div><span>Ambiente</span><span>${esc(h.environmentName||'—')}</span></div><div><span>Domínio</span><span>${esc(h.domain||'—')}</span></div><div><span>Subdomínio</span><span>${esc(h.subdomain||'—')}</span></div><div><span>DNS</span><span>${esc(h.dnsMode||'SUBDOMAIN')}</span></div><div><span>Aplicação</span><span>${hUrl?`<a href="${esc(hUrl)}" target="_blank" rel="noopener noreferrer">Abrir sistema</a>`:esc(h.appUrl||'—')}</span></div><div><span>API</span><span>${apiLink?`<a href="${esc(apiLink)}" target="_blank" rel="noopener noreferrer">Abrir API</a>`:esc(h.apiUrl||'—')}</span></div><div><span>Banco</span><span>${esc(h.databaseName||'—')}</span></div><div><span>Deploy</span><span>${esc(h.deploymentMethod||'—')}</span></div><div><span>Caminho</span><span>${esc(h.deploymentPath||'—')}</span></div></div>${h.hostingNotes?`<p>${esc(h.hostingNotes)}</p>`:''}</div>`:'<div class="hosting-box">Hospedagem ainda não configurada.</div>';
  const contractsHtml=contracts.length?contracts.map(ct=>`<article class="contract-card"><div class="contract-card-head"><div><h4>${esc(ct.contractNumber)}</h4><span>${badge(ct.status)}</span></div><span>${dateOnly(ct.createdAt)}</span></div><div class="detail-grid compact"><div class="detail-item"><span>Plano</span><span>${esc(ct.planName)}</span></div><div class="detail-item"><span>Prazo</span><span>${esc(contractTermLabel(ct.contractTerm))}</span></div><div class="detail-item"><span>Mensalidade</span><span>${money(ct.monthlyFee)}</span></div><div class="detail-item"><span>Pagamento</span><span>${esc(paymentMethodLabel(ct.paymentMethod))}</span></div></div><p>${esc(ct.modulesSnapshot||'')}</p><div class="signature-grid"><div><span>Empresa-cliente</span><div>${ct.clientSignedAt?`Assinado por ${esc(ct.clientSignedBy||'responsável')} em ${new Date(ct.clientSignedAt).toLocaleString('pt-BR')}`:'Aguardando assinatura'}</div></div><div><span>Gestão Software</span><div>${ct.managementSignedAt?`Assinado por ${esc(ct.managementSignedBy||'Gestão Software')} em ${new Date(ct.managementSignedAt).toLocaleString('pt-BR')}`:'Aguardando Carlos ou Pedro'}</div></div></div><div class="form-actions"><button class="button outline small" type="button" data-copy-contract-link="${esc(ct.signatureUrl)}">Copiar link da empresa</button>${!ct.managementSignedAt&&ct.status!=='CANCELLED'?`<button class="button primary small" type="button" data-sign-management-contract="${ct.id}">Assinar pela Gestão Software</button>`:''}</div></article>`).join(''):'<div class="empty">Nenhum contrato gerado.</div>';
  $('detail-company-content').innerHTML=`<div class="detail-grid"><div class="detail-item"><span>Status</span>${badge(c.status)}</div><div class="detail-item"><span>Plano / prazo</span><span>${esc(c.planName||'Personalizado')} · ${esc(contractTermLabel(c.contractTerm))}</span></div><div class="detail-item"><span>Mensalidade</span><span>${money(c.monthlyFee)}</span></div><div class="detail-item"><span>Pagamento</span><span>${esc(paymentMethodLabel(c.paymentMethod))}</span></div><div class="detail-item"><span>Vencimento</span><span>Dia ${c.billingDueDay}</span></div><div class="detail-item"><span>Vigência</span><span>${dateOnly(c.contractStartDate)} → ${dateOnly(c.contractEndDate)}</span></div></div><div class="form-actions"><button class="button primary small" data-edit-company-detail="${c.id}" type="button">Editar empresa</button><button class="button outline small" data-hosting-company="${c.id}" type="button">Hospedagem</button><button class="button outline small" data-generate-invoice="${c.id}" type="button">Gerar mensalidade ${esc(currentMonth())}</button></div><section class="detail-section"><div class="detail-section-head"><h3>Contrato</h3></div>${contractsHtml}</section><section class="detail-section"><div class="detail-section-head"><h3>Identidade visual</h3></div>${brandPreview}</section><section class="detail-section"><div class="detail-section-head"><h3>Módulos contratados</h3>${c.pointSuperAdminEligible?'<span class="badge ok">Super Admin do Apontamento Digital habilitado para o grupo</span>':''}</div><div class="module-chips">${modulesHtml}</div></section><section class="detail-section"><div class="detail-section-head"><h3>Programa de hospedagem</h3><button class="button outline small" data-hosting-company="${c.id}" type="button">Editar hospedagem</button></div>${hostingHtml}</section><section class="detail-section"><div class="detail-section-head"><h3>Mensalidades</h3></div><div class="table-wrap"><table><thead><tr><th>Competência</th><th>Valor</th><th>Vencimento</th><th>Status</th><th>Pago em</th><th></th></tr></thead><tbody>${inv.length?inv.map(i=>`<tr><td>${monthLabel(i.referenceMonth)}</td><td>${money(i.amount)}</td><td>${dateOnly(i.dueDate)}</td><td>${badge(i.status)}</td><td>${i.paidAt?new Date(i.paidAt).toLocaleString('pt-BR'):'—'}</td><td>${i.status!=='PAID'&&i.status!=='CANCELLED'?`<button class="button outline small" data-invoice-paid="${i.id}" type="button">Marcar pago</button>`:''}${i.status==='PAID'?`<button class="button outline small" data-invoice-pending="${i.id}" type="button">Reabrir</button>`:''}</td></tr>`).join(''):emptyRow(6,'Nenhuma mensalidade gerada.')}</tbody></table></div></section>`;
}

function openHosting(company){const h=company.hosting||{};$('hosting-company-id').value=company.id;$('hosting-title').textContent=`Hospedagem · ${company.tradeName}`;$('hosting-vps').innerHTML='<option value="">Sem VPS vinculada</option>'+vps.map(v=>`<option value="${v.id}">${esc(v.name)} · ${esc(v.provider)}</option>`).join('');$('hosting-vps').value=h.vpsId||'';$('hosting-environment').value=h.environmentName||'';$('hosting-domain').value=h.domain||'';$('hosting-subdomain').value=h.subdomain||'';$('hosting-dns-mode').value=h.dnsMode||'SUBDOMAIN';$('hosting-app-url').value=h.appUrl||'';$('hosting-api-url').value=h.apiUrl||'';$('hosting-database').value=h.databaseName||'';$('hosting-path').value=h.deploymentPath||'';$('hosting-deployment-method').value=h.deploymentMethod||'';$('hosting-notes').value=h.hostingNotes||'';formMessage('hosting-message');$('hosting-dialog').showModal()}

function resetVpsForm(){ $('vps-form').reset();$('vps-id').value='';$('vps-dialog-title').textContent='Nova VPS';$('vps-cost').value='0';$('vps-max-companies').value='10';$('vps-active').checked=true;formMessage('vps-form-message') }
function editVps(id){const n=vps.find(x=>x.id===id);if(!n)return;$('vps-id').value=n.id;$('vps-dialog-title').textContent=`Editar ${n.name}`;$('vps-name').value=n.name||'';$('vps-provider').value=n.provider||'';$('vps-plan').value=n.planName||'';$('vps-hostname').value=n.hostname||'';$('vps-ip').value=n.ipAddress||'';$('vps-cost').value=Number(n.monthlyCost||0);$('vps-max-companies').value=n.maxCompanies||10;$('vps-cpu-cores').value=n.cpuCores??'';$('vps-ram-gb').value=n.ramGb??'';$('vps-storage-gb').value=n.storageGb??'';$('vps-cpu-use').value=n.cpuUsagePercent??'';$('vps-ram-use').value=n.ramUsagePercent??'';$('vps-storage-use').value=n.storageUsagePercent??'';$('vps-renewal').value=n.renewalDate||'';$('vps-active').checked=!!n.active;$('vps-notes').value=n.notes||'';formMessage('vps-form-message');$('vps-dialog').showModal()}
function vpsPayload(){return {name:$('vps-name').value.trim(),provider:$('vps-provider').value.trim(),planName:$('vps-plan').value.trim()||null,hostname:$('vps-hostname').value.trim()||null,ipAddress:$('vps-ip').value.trim()||null,monthlyCost:numberOrNull($('vps-cost').value)||0,maxCompanies:Number($('vps-max-companies').value||10),cpuCores:numberOrNull($('vps-cpu-cores').value),ramGb:numberOrNull($('vps-ram-gb').value),storageGb:numberOrNull($('vps-storage-gb').value),cpuUsagePercent:numberOrNull($('vps-cpu-use').value),ramUsagePercent:numberOrNull($('vps-ram-use').value),storageUsagePercent:numberOrNull($('vps-storage-use').value),active:$('vps-active').checked,renewalDate:$('vps-renewal').value||null,notes:$('vps-notes').value.trim()||null}}

async function refreshCompanyDetail(){if(!currentCompany)return;currentCompany=await api(`/api/platform/companies/${currentCompany.id}`);renderCompanyDetail()}

$('password-form').addEventListener('submit',async e=>{e.preventDefault();const cur=$('password-current').value,n=$('password-new').value,c=$('password-confirm').value;if(n.length<8){formMessage('password-message','A nova senha deve ter pelo menos 8 caracteres.');return}if(n!==c){formMessage('password-message','As senhas não conferem.');return}try{await api('/api/auth/change-password',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({currentPassword:cur,newPassword:n})});$('password-dialog').close();await boot()}catch(error){formMessage('password-message',error.message)}});
$('logout').addEventListener('click',()=>logout());
document.querySelectorAll('.platform-tabs button').forEach(b=>b.addEventListener('click',()=>activateTab(b.dataset.tab)));document.querySelectorAll('[data-tab-target]').forEach(b=>b.addEventListener('click',()=>activateTab(b.dataset.tabTarget)));
$('reference-month').addEventListener('change',async()=>{$('expense-month').value=currentMonth();await reloadAll()});
$('company-search').addEventListener('input',renderCompanies);$('company-status-filter').addEventListener('change',renderCompanies);
$('new-company').addEventListener('click',()=>{resetCompanyForm();$('company-dialog').showModal()});
$('company-form').addEventListener('change',e=>{if(e.target.id==='company-plan')syncCompanyPricing()});
$('new-vps').addEventListener('click',()=>{resetVpsForm();$('vps-dialog').showModal()});
document.querySelectorAll('[data-close-dialog]').forEach(b=>b.addEventListener('click',()=>$(b.dataset.closeDialog)?.close()));

$('company-form').addEventListener('submit',async e=>{e.preventDefault();const id=$('company-id').value;try{
    const previous=companies.find(company=>company.id===id);
    if(id&&previous?.status!=='SUSPENDED'&&$('company-status').value==='SUSPENDED'){
      const confirmed=await confirmCompanyAction({title:'Confirmar desativação',description:`Deseja desativar “${previous?.tradeName||'esta empresa'}”? O cadastro ficará suspenso, mas contratos, faturas e infraestrutura serão preservados.`,confirmLabel:'Sim, desativar empresa',companyName:previous?.tradeName||''});
      if(!confirmed)return;
    }
    let saved=await api(id?`/api/platform/companies/${id}`:'/api/platform/companies',{method:id?'PUT':'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(companyPayload())});$('company-id').value=saved.id;
    try { await saveCompanyTenantHosting(saved.id);await uploadCompanyFiles(saved.id); }
    catch(uploadError){ await reloadAll();formMessage('company-form-message',`Empresa salva com sucesso, mas o anexo não foi enviado: ${uploadError.message} Tente novamente em Editar empresa.`);return; }
    saved=await api(`/api/platform/companies/${saved.id}`);$('company-dialog').close();await reloadAll();if(id){await openCompany(saved.id);setMessage('Empresa atualizada com sucesso.','success')}else{setMessage('Empresa cadastrada. Agora vamos dimensionar e liberar os módulos.','success');window.dispatchEvent(new CustomEvent('gestao:company-created',{detail:{id:saved.id}}))}}catch(error){formMessage('company-form-message',error.message)}});
$('hosting-form').addEventListener('submit',async e=>{e.preventDefault();const id=$('hosting-company-id').value;try{await api(`/api/platform/companies/${id}/hosting`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({vpsId:$('hosting-vps').value||null,environmentName:$('hosting-environment').value.trim()||null,domain:$('hosting-domain').value.trim()||null,subdomain:$('hosting-subdomain').value.trim()||null,dnsMode:$('hosting-dns-mode').value||'SUBDOMAIN',appUrl:$('hosting-app-url').value.trim()||null,apiUrl:$('hosting-api-url').value.trim()||null,databaseName:$('hosting-database').value.trim()||null,deploymentPath:$('hosting-path').value.trim()||null,deploymentMethod:$('hosting-deployment-method').value.trim()||null,hostingNotes:$('hosting-notes').value.trim()||null})});$('hosting-dialog').close();await reloadAll();if(currentCompany?.id===id)await refreshCompanyDetail();setMessage('Hospedagem atualizada.','success')}catch(error){formMessage('hosting-message',error.message)}});
$('vps-form').addEventListener('submit',async e=>{e.preventDefault();const id=$('vps-id').value;try{await api(id?`/api/platform/vps/${id}`:'/api/platform/vps',{method:id?'PUT':'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(vpsPayload())});$('vps-dialog').close();await reloadAll();setMessage('VPS salva e capacidade recalculada.','success')}catch(error){formMessage('vps-form-message',error.message)}});
$('partner-form').addEventListener('submit',async e=>{e.preventDefault();try{await api('/api/platform/partners/shares',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({pedroPercent:Number($('pedro-share').value),carlosPercent:Number($('carlos-share').value)})});await reloadAll();setMessage('Divisão societária atualizada.','success')}catch(error){setMessage(error.message)}});
$('expense-form').addEventListener('submit',async e=>{e.preventDefault();const id=$('expense-id').value;try{await api(id?`/api/platform/expenses/${id}`:'/api/platform/expenses',{method:id?'PUT':'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({referenceMonth:`${$('expense-month').value}-01`,category:$('expense-category').value.trim(),description:$('expense-description').value.trim(),amount:Number($('expense-amount').value),notes:$('expense-notes').value.trim()||null})});resetExpense();await reloadAll();setMessage('Despesa salva.','success')}catch(error){setMessage(error.message)}});
function resetExpense(){$('expense-form').reset();$('expense-id').value='';$('expense-month').value=currentMonth();$('cancel-expense').hidden=true}
$('cancel-expense').addEventListener('click',resetExpense);

document.addEventListener('click',async e=>{
  const open=e.target.closest('[data-open-company]');if(open){await openCompany(open.dataset.openCompany);return}
  const editRow=e.target.closest('[data-edit-company-row]');if(editRow){await editCompany(editRow.dataset.editCompanyRow);return}
  const deactivate=e.target.closest('[data-deactivate-company]');if(deactivate){
    const company=companies.find(c=>c.id===deactivate.dataset.deactivateCompany);
    if(await confirmCompanyAction({title:'Desativar empresa',description:`Desativar “${company?.tradeName||'esta empresa'}” no Gestão? A empresa ficará suspensa e sairá da capacidade ativa, mas contratos, mensalidades, bancos de dados e recursos de hospedagem serão preservados.`,confirmLabel:'Desativar empresa',companyName:company?.tradeName||''})){
      try{await api(`/api/platform/companies/${deactivate.dataset.deactivateCompany}/deactivate`,{method:'POST'});await reloadAll();setMessage('Empresa desativada. O histórico e a infraestrutura foram preservados.','success')}catch(error){setMessage(error.message)}
    }
    return;
  }
  const reactivate=e.target.closest('[data-reactivate-company]');if(reactivate){
    const company=companies.find(c=>c.id===reactivate.dataset.reactivateCompany),archived=company?.status==='CANCELLED';
    if(await confirmCompanyAction({title:archived?'Restaurar empresa excluída':'Reativar empresa',description:archived?`Restaurar “${company?.tradeName||'esta empresa'}” ao cadastro ativo? O cadastro e seu histórico serão mantidos, mas serviços na VPS devem ser verificados separadamente.`:`Reativar “${company?.tradeName||'esta empresa'}” no Gestão? O cadastro volta ao status anterior à suspensão. Confira também o estado real da aplicação e da VPS.`,confirmLabel:archived?'Restaurar cadastro':'Reativar empresa'})){
      try{await api(`/api/platform/companies/${reactivate.dataset.reactivateCompany}/reactivate`,{method:'POST'});await reloadAll();setMessage(archived?'Cadastro restaurado com auditoria.':'Empresa reativada com auditoria.','success')}catch(error){setMessage(error.message)}
    }
    return;
  }
  const delCompany=e.target.closest('[data-delete-company]');if(delCompany){
    const company=companies.find(c=>c.id===delCompany.dataset.deleteCompany),name=company?.tradeName||'';
    if(await confirmCompanyAction({title:'Excluir empresa do cadastro',description:`Você está prestes a excluir “${name||'esta empresa'}” da lista ativa do Gestão. Esta operação arquiva o cadastro com registro do responsável; não apaga faturas, contratos, bancos de dados ou serviços da VPS. Para restaurar, use o filtro “Excluídas (arquivo)”.`,confirmLabel:'Excluir do cadastro',companyName:name,requireName:true,danger:true})){
      try{await api(`/api/platform/companies/${delCompany.dataset.deleteCompany}`,{method:'DELETE'});if(currentCompany?.id===delCompany.dataset.deleteCompany){currentCompany=null;$('company-detail-dialog')?.close()}await reloadAll();setMessage('Empresa excluída do cadastro ativo com auditoria. Consulte o arquivo para restaurá-la.','success')}catch(error){setMessage(error.message)}
    }
    return;
  }
  const edit=e.target.closest('[data-edit-company-detail]');if(edit){$('company-detail-dialog').close();await editCompany(edit.dataset.editCompanyDetail);return}
  const signContract=e.target.closest('[data-sign-management-contract]');if(signContract){if(confirm('Assinar este contrato digitalmente pela Gestão Software?'))try{await api(`/api/platform/companies/${currentCompany.id}/contracts/${signContract.dataset.signManagementContract}/sign-management`,{method:'POST'});await refreshCompanyDetail();setMessage('Contrato assinado pela Gestão Software.','success')}catch(error){setMessage(error.message)}return}
  const copyContract=e.target.closest('[data-copy-contract-link]');if(copyContract){const path=copyContract.dataset.copyContractLink;const url=new URL(path,location.origin).href;await navigator.clipboard.writeText(url);setMessage('Link de assinatura copiado.','success');return}
  const host=e.target.closest('[data-hosting-company]');if(host){const data=currentCompany?.id===host.dataset.hostingCompany?currentCompany:await api(`/api/platform/companies/${host.dataset.hostingCompany}`);openHosting(data);return}
  const gen=e.target.closest('[data-generate-invoice]');if(gen){try{await api(`/api/platform/companies/${gen.dataset.generateInvoice}/invoices`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({referenceMonth:`${currentMonth()}-01`,amount:null,dueDate:null,notes:null})});await reloadAll();await refreshCompanyDetail();setMessage('Mensalidade gerada/atualizada.','success')}catch(error){setMessage(error.message)}return}
  const paid=e.target.closest('[data-invoice-paid]');if(paid){try{await api(`/api/platform/invoices/${paid.dataset.invoicePaid}`,{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({status:'PAID'})});await reloadAll();await refreshCompanyDetail()}catch(error){setMessage(error.message)}return}
  const pending=e.target.closest('[data-invoice-pending]');if(pending){try{await api(`/api/platform/invoices/${pending.dataset.invoicePending}`,{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({status:'PENDING'})});await reloadAll();await refreshCompanyDetail()}catch(error){setMessage(error.message)}return}
  const editExp=e.target.closest('[data-edit-expense]');if(editExp){const x=expenses.find(v=>v.id===editExp.dataset.editExpense);if(x){activateTab('finance');$('expense-id').value=x.id;$('expense-month').value=String(x.referenceMonth).slice(0,7);$('expense-category').value=x.category;$('expense-description').value=x.description;$('expense-amount').value=Number(x.amount||0);$('expense-notes').value=x.notes||'';$('cancel-expense').hidden=false;$('expense-category').focus()}return}
  const delExp=e.target.closest('[data-delete-expense]');if(delExp){if(confirm('Excluir esta despesa operacional?'))try{await api(`/api/platform/expenses/${delExp.dataset.deleteExpense}`,{method:'DELETE'});await reloadAll()}catch(error){setMessage(error.message)}return}
  const editNode=e.target.closest('[data-edit-vps]');if(editNode){editVps(editNode.dataset.editVps);return}
});


function clampNumber(value,min=0,max=Number.MAX_SAFE_INTEGER){const n=Number(value);if(!Number.isFinite(n))return min;return Math.max(min,Math.min(max,n))}
function selectedCalcModules(){return [...document.querySelectorAll('[data-calc-module]')].filter(x=>x.checked).map(x=>x.dataset.calcModule)}
function planFor(users,sales){const plans=calculatorReference?.plans||[];return plans.find(p=>users<=Number(p.maxUsers)&&sales<=Number(p.maxSales))||plans[plans.length-1]}
function processingPriceFor(payments){const tiers=calculatorReference?.processingTiers||[];const tier=tiers.find(t=>payments<=Number(t.max));return Number(tier?.price||0)}
function moduleFactor(count){if(count<=0)return 0;const base=Number(calculatorReference?.moduleBaseFactor??.40),inc=Number(calculatorReference?.moduleIncrement??.15);return Math.min(1,base+(count*inc))}
function autoAwsCost(plan,profile){const base=Number(plan.awsCost||0);if(profile==='SHARED')return Math.max(250,base*.85);if(profile==='RESERVED')return base*1.25;if(profile==='DEDICATED')return Math.max(120000,base);if(profile==='HYBRID')return Math.max(160000,base*1.2);return base}
function calcPartnerCards(profit){const partners=dashboard?.partners||[];if(!partners.length)return '<div class="partner-card"><span>Resultado</span><strong>'+money(profit)+'</strong><span class="share">Divisão ainda não configurada</span></div>';return partners.map(p=>{const share=Number(p.profitSharePercent||0),amount=profit*(share/100);return `<div class="partner-card"><span>${esc(p.name)}</span><strong>${money(amount)}</strong><span class="share">${pct.format(share)}% do resultado estimado</span></div>`}).join('')}
function resetPlanCalculator(){
  if(!$('plan-calculator-form'))return;
  $('plan-calculator-form').reset();$('calc-users').value='250';$('calc-sales').value='7500';$('calc-payments').value='7500';$('calc-term').value='MONTHLY';$('calc-asaas-cost').value=String(calculatorReference?.defaultAsaasPaymentFee??2.00);$('calc-average-payment').value='100.00';$('calc-withdrawals').value='0';$('calc-withdrawal-cost').value=String(calculatorReference?.defaultAsaasWithdrawalFee??2.00);$('calc-other-costs').value='0';$('calc-target-margin').value='55';$('calc-infra-profile').value='AUTO';
  document.querySelectorAll('[data-calc-module]').forEach(x=>x.checked=true);calcAwsManual=false;
}
function renderPlanCalculator(){
  if(!$('plan-calculator-form')||!calculatorReference||!PLAN_CALCULATOR_USERS.has(String(currentUser?.username||'').toLowerCase()))return;
  const users=Math.round(clampNumber($('calc-users').value,1,10000000));const sales=Math.round(clampNumber($('calc-sales').value,0,100000000));const payments=Math.round(clampNumber($('calc-payments').value,0,100000000));const withdrawals=Math.round(clampNumber($('calc-withdrawals').value,0,10000000));
  $('calc-users').value=users;$('calc-sales').value=sales;$('calc-payments').value=payments;$('calc-withdrawals').value=withdrawals;
  const mods=selectedCalcModules();const hasFinance=mods.includes('GESTAO_FINANCEIRA_COMERCIAL');const plan=planFor(users,sales);if(!plan)return;const terms=calculatorReference.terms||{};const term=terms[$('calc-term').value]||terms.MONTHLY||{discount:0,months:1,label:'mensal'};const factor=moduleFactor(mods.length);
  const listPrice=Number(plan.listPrice||0);const license=listPrice*factor*(1-Number(term.discount||0));
  const profile=$('calc-infra-profile').value;const suggestedAws=autoAwsCost(plan,profile);if(!calcAwsManual)$('calc-aws-cost').value=Math.round(suggestedAws);
  const aws=clampNumber($('calc-aws-cost').value,0,100000000);const paymentFee=hasFinance?clampNumber($('calc-asaas-cost').value,0,1000):0;const withdrawalFee=hasFinance?clampNumber($('calc-withdrawal-cost').value,0,1000):0;const averagePayment=clampNumber($('calc-average-payment').value,0,100000000);const otherCosts=clampNumber($('calc-other-costs').value,0,100000000);const targetMargin=clampNumber($('calc-target-margin').value,10,90)/100;
  const paymentFeesRepassed=hasFinance?payments*paymentFee:0;const withdrawalFeesRepassed=hasFinance?withdrawals*withdrawalFee:0;const asaasPassthrough=paymentFeesRepassed+withdrawalFeesRepassed;
  const totalCost=aws+otherCosts;const minForMargin=totalCost/(1-targetMargin);const recommended=Math.max(license,minForMargin);const grossProfit=recommended-totalCost;const grossMargin=recommended>0?grossProfit/recommended:0;
  const capacityOk=plan.code!=='HYBRID';const company=String($('calc-company-name').value||'').trim();
  $('calc-plan-name').textContent=plan.name;$('calc-plan-capacity').textContent=plan.capacity;$('calc-plan-status').className=`badge ${capacityOk?'ok':'warn'}`;$('calc-plan-status').textContent=capacityOk?'Dentro da capacidade':'Dimensionamento personalizado';
  $('calc-recommended-price').textContent=money(recommended);$('calc-price-note').textContent=`por mês${company?` · ${company}`:''}`;$('calc-license-price').textContent=money(license);$('calc-processing-revenue').textContent=money(asaasPassthrough);$('calc-total-cost').textContent=money(totalCost);$('calc-gross-profit').textContent=money(grossProfit);$('calc-margin-label').textContent=`Margem bruta ${pct.format(grossMargin*100)}%`;
  $('calc-list-price').textContent=money(listPrice);$('calc-discount').textContent=`${pct.format(Number(term.discount||0)*100)}%`;$('calc-aws-result').textContent=money(aws);$('calc-asaas-result').textContent=money(paymentFeesRepassed);$('calc-withdrawal-fees-result').textContent=money(withdrawalFeesRepassed);$('calc-cost-per-user').textContent=money(totalCost/users);$('calc-price-per-user').textContent=money(recommended/users);$('calc-contract-total').textContent=money(recommended*Number(term.months||1));$('calc-margin').textContent=`${pct.format(grossMargin*100)}%`;$('calc-payment-example').textContent=`${money(averagePayment)} + ${money(paymentFee)} = ${money(averagePayment+paymentFee)}`;$('calc-withdrawal-example').textContent=`${money(100)} disponível → ${money(Math.max(0,100-withdrawalFee))} líquido`;$('calc-partners').innerHTML=calcPartnerCards(grossProfit);
  const alert=$('calc-margin-alert');if(mods.length===0){alert.className='capacity-banner warn';alert.textContent='Selecione pelo menos um módulo para formar a proposta.';formMessage('calc-validation','Selecione pelo menos um módulo.');return}
  formMessage('calc-validation','');
  if(recommended>license+0.01){alert.className='capacity-banner warn';alert.innerHTML=`<strong>Mensalidade ajustada para proteger a margem.</strong><div>A licença pelos módulos/prazo ficaria em ${money(license)}. Para manter ${pct.format(targetMargin*100)}% de margem bruta sobre os custos diretos, a referência mínima é ${money(minForMargin)} por mês. As tarifas Asaas são repassadas e não entram neste cálculo.</div>`}
  else{alert.className='capacity-banner ok';alert.innerHTML=`<strong>Proposta saudável.</strong><div>A mensalidade mantém margem bruta estimada de ${pct.format(grossMargin*100)}% considerando AWS e custos diretos informados. As tarifas Asaas são adicionadas ao boleto/Pix ou descontadas do saque.</div>`}
}

if($('plan-calculator-form')){
  $('plan-calculator-form').addEventListener('input',e=>{if(e.target.id==='calc-aws-cost')calcAwsManual=true;renderPlanCalculator()});
  $('plan-calculator-form').addEventListener('change',e=>{if(e.target.id==='calc-infra-profile')calcAwsManual=false;renderPlanCalculator()});
  $('calc-reset').addEventListener('click',()=>{resetPlanCalculator();renderPlanCalculator()});
}

boot();
