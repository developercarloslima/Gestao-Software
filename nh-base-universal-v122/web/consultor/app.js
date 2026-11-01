const T='nhPortalToken',R='nhPortalRole';
const $=id=>document.getElementById(id);
let me=null,dashboard=null,finance=null,portfolio=[],financeDocs=[];
const api=p=>window.NH_API?.backend?window.NH_API.backend(p):p;
const money=v=>Number(v||0).toLocaleString('pt-BR',{style:'currency',currency:'BRL'});
const dt=v=>v?new Date(v).toLocaleString('pt-BR'):'—';
const date=v=>v?new Date(v+'T12:00:00').toLocaleDateString('pt-BR'):'—';

async function readErr(r){
  if([502,503,504].includes(r.status))return `Serviço NH indisponível (HTTP ${r.status}). Verifique se o backend está ativo: docker compose ps e docker compose logs --tail=100 backend.`;
  const body=await r.text().catch(()=>'');
  if(r.status===413)return 'O arquivo excedeu o tamanho aceito pelo servidor (HTTP 413).';
  try{const j=JSON.parse(body);return j.message||j.detail||j.error||`Erro HTTP ${r.status}.`}catch{return body && !/^\s*<!?doctype|^\s*<html/i.test(body)?body.slice(0,240):`Erro HTTP ${r.status}.`}
}
async function req(path,opt={}){
  const h={...(opt.headers||{})}; const token=localStorage.getItem(T); if(token)h.Authorization=`Bearer ${token}`;
  if(opt.body&&!(opt.body instanceof FormData))h['Content-Type']='application/json';
  const r=await fetch(api(path),{...opt,headers:h});
  if(r.status===401){const reason=await readErr(r);if(path!='/api/auth/login')logout('Sessão expirada. Entre novamente.');throw new Error(reason);}
  // HTTP 403 significa acesso negado, mas não invalida o token de login.
  // No primeiro acesso, apenas /api/auth/me e /api/auth/change-password são autorizados.
  if(r.status===403)throw new Error(await readErr(r));
  if(!r.ok)throw new Error(await readErr(r)); return r.status===204?null:r.json();
}
function msg(id,t,err=true){const e=$(id);if(!e)return;e.textContent=t||'';e.className=t?`message ${err?'error':'success'}`:''}
function logout(text=''){if($('password-dialog')?.open)$('password-dialog').close();me=null;localStorage.removeItem(T);localStorage.removeItem(R);localStorage.removeItem('nhSelectedConsultant');$('app').hidden=true;$('login').hidden=false;$('logout').hidden=true;$('change-pass-button').hidden=true;if(text)msg('login-msg',text)}

async function boot(){
  if(!localStorage.getItem(T))return;
  try{
    me=await req('/api/auth/me');
    if(me.role!=='CONSULTANT')return logout('Esta área é exclusiva para consultores.');
    // O login compartilhado Colaborador deve escolher a pessoa no portal, nunca aqui.
    if(!me.consultantId){location.replace('/colaborador/');return;}
    $('change-pass-button').hidden=false;
    localStorage.setItem('nhSelectedConsultant',JSON.stringify({id:me.consultantId,name:me.consultantName||me.displayName}));
    $('login').hidden=true;$('app').hidden=false;$('logout').hidden=false;$('welcome').textContent=`Olá, ${me.consultantName||me.displayName}`;
    if(me.passwordChangeRequired){
      // O filtro BearerTokenFilter bloqueia os demais endpoints até a troca de senha.
      if(!$('password-dialog').open)$('password-dialog').showModal();
      return;
    }
    await loadAll();
  }catch(e){
    if(/módulo não contratado|módulo desativado/i.test(e.message)){
      $('app').hidden=true;
      $('login').hidden=false;
      msg('login-msg','A Área do Consultor individual pertence ao módulo Financeiro, que não está habilitado para esta empresa. Use a Área do Colaborador para cotações e vistorias.');
      return;
    }
    logout(e.message);
  }
}
async function loadAll(){
  // A área individual é um benefício exclusivo do módulo Financeiro.
  // Validar a licença primeiro: sem ela nenhum histórico individual é exibido aqui.
  finance=await req('/api/commercial/me/finance');
  [dashboard,portfolio,financeDocs]=await Promise.all([
    req(`/api/consultant-dashboard/${me.consultantId}`),
    req('/api/commercial/me/portfolio'),
    req('/api/commercial/me/documents')
  ]);
  render();
}
function render(){
  $('kpi-sales').textContent=finance.salesThisMonth||0;$('kpi-base').textContent=finance.activePayingBase||0;$('kpi-commission').textContent=money(finance.commissionThisMonth);$('kpi-balance').textContent=money(finance.availableBalance);$('kpi-career').textContent=finance.careerLevel||'BRONZE';$('kpi-vehicles').textContent=portfolio.length;
  const b=finance.commissionBreakdown||{};
  $('commission-breakdown').innerHTML=[['Régua/Base',b.ladderOrBaseCommission],['Motos',b.motorcycleSalesBonus],['Leves',b.lightVehicleSalesBonus],['Util./Caminhões',b.utilityTruckSalesBonus],['Indicados',b.referralBonus],['Rankings',b.rankingBonus],['Combustível',b.fuelAllowance],['Adesões',b.adhesionCommission]].map(([l,v])=>`<div class="mini-kpi"><span>${l}</span><strong>${money(v)}</strong></div>`).join('')+`<div class="mini-kpi"><span>Próxima meta</span><strong style="font-size:1rem">${b.nextCareerMilestone||'—'}</strong></div>`;
  $('quotes').innerHTML=(dashboard.quotes||[]).map(q=>`<tr><td>${q.quoteNumber||'—'}</td><td>${q.customerName||'—'}</td><td>${q.plate||''} ${q.model||''}</td><td>${q.selectedPlanName||'—'}</td><td>${money(q.monthlyValue)}</td><td><span class="status">${q.expired?'EXPIRADA':q.status}</span></td><td>${q.pdfUrl?`<a href="${q.pdfUrl}" target="_blank">PDF</a>`:''}</td></tr>`).join('')||'<tr><td colspan="7">Nenhuma cotação.</td></tr>';
  $('inspections').innerHTML=(dashboard.inspections||[]).map(i=>`<tr><td>${i.associateName||'—'}</td><td>${i.plate||'—'}</td><td><span class="status">${i.status}</span></td><td>${dt(i.createdAt)}</td><td>${i.publicUrl?`<a href="${i.publicUrl}" target="_blank">Abrir</a>`:''}</td></tr>`).join('')||'<tr><td colspan="5">Nenhuma vistoria.</td></tr>';
  const byStatus={ACTIVE:0,PAYMENT_PENDING:0,DELINQUENT:0,DISABLED:0};
  const assoc=new Map();
  portfolio.forEach(v=>{if(v.businessStatus==='ACTIVE')byStatus.ACTIVE++;else if(v.businessStatus==='PAYMENT_PENDING')byStatus.PAYMENT_PENDING++;else if(v.businessStatus==='DELINQUENT')byStatus.DELINQUENT++;else if(String(v.businessStatus||'').startsWith('DISABLED'))byStatus.DISABLED++;const a=assoc.get(v.associateId)||{name:v.associateName||'—',vehicles:0,active:0,pending:0,late:0,disabled:0};a.vehicles++;if(v.businessStatus==='ACTIVE')a.active++;else if(v.businessStatus==='PAYMENT_PENDING')a.pending++;else if(v.businessStatus==='DELINQUENT')a.late++;else a.disabled++;assoc.set(v.associateId,a)});
  $('status-active').textContent=byStatus.ACTIVE;$('status-pending').textContent=byStatus.PAYMENT_PENDING;$('status-delinquent').textContent=byStatus.DELINQUENT;$('status-disabled').textContent=byStatus.DISABLED;
  $('associates').innerHTML=[...assoc.values()].map(a=>`<tr><td>${a.name}</td><td>${a.vehicles}</td><td>${a.active}</td><td>${a.pending}</td><td>${a.late}</td><td>${a.disabled}</td></tr>`).join('')||'<tr><td colspan="6">Nenhum associado vinculado.</td></tr>';
  $('portfolio').innerHTML=portfolio.map(v=>`<tr><td>${v.associateName||'—'}</td><td>${v.plate||'—'}</td><td>${v.model||'—'}</td><td>${v.planName||'—'}</td><td><span class="status">${v.businessStatusLabel}</span></td><td>${v.paidCharges}</td><td>${v.pendingCharges}</td><td>${v.overdueCharges}</td><td><button class="outline" data-vehicle="${v.id}">Ver cobranças</button></td></tr>`).join('')||'<tr><td colspan="9">Nenhum veículo.</td></tr>';
  document.querySelectorAll('[data-vehicle]').forEach(b=>b.onclick=()=>openVehicle(b.dataset.vehicle));
  $('fin-available').textContent=money(finance.availableBalance);$('fin-total').textContent=money(finance.totalReceivable);$('fin-paid').textContent=money(finance.paidWithdrawals);$('fin-closing').textContent=`${finance.paidClosings||0} / ${finance.requiredClosings||10}`;
  $('pix-current').textContent=finance.maskedPayoutPixKey?`Chave atual: ${finance.payoutPixKeyType} ${finance.maskedPayoutPixKey}`:'Nenhuma chave Pix cadastrada.';
  $('unlock-msg').textContent=finance.withdrawalUnlocked?'Saque liberado. Toda solicitação vai primeiro para o Financeiro. Pix será processado pelo Asaas após aprovação; TED será pago e comprovado pelo Financeiro.':`Saque será liberado após ${finance.requiredClosings||10} boletos de fechamento pagos.`;
  $('withdraw-form').querySelector('button').disabled=!finance.withdrawalUnlocked;
  $('withdrawals').innerHTML=(finance.withdrawals||[]).map(w=>`<tr><td>${dt(w.createdAt)}</td><td>${w.payoutMethod}</td><td>${money(w.amount)}</td><td>${money(w.transactionFee)}</td><td>${money(w.netAmount)}</td><td>${labelWithdrawal(w.status)}</td></tr>`).join('')||'<tr><td colspan="6">Nenhum saque solicitado.</td></tr>';
  $('finance-docs').innerHTML=(financeDocs||[]).map(x=>`<tr><td>${x.documentType==='CONSULTANT_INVOICE'?'Nota fiscal':'Comprovante de pagamento'}</td><td>${date(x.referenceMonth)}</td><td>${x.fileName}</td><td>${dt(x.createdAt)}</td><td><button class="outline" data-fdoc="${x.id}">Baixar</button></td></tr>`).join('')||'<tr><td colspan="5">Nenhum documento.</td></tr>';
  document.querySelectorAll('[data-fdoc]').forEach(b=>b.onclick=()=>downloadDoc(b.dataset.fdoc));
}
function labelWithdrawal(s){return ({REQUESTED:'Aguardando Financeiro',PROCESSING:'Em processamento',PAID:'Pago',FAILED:'Falhou',CANCELLED:'Cancelado'})[s]||s}

$('login-form').addEventListener('submit',async e=>{e.preventDefault();msg('login-msg','');try{const d=await req('/api/auth/login',{method:'POST',body:JSON.stringify({username:$('username').value,password:$('password').value})});if(!d.token)throw new Error('Este acesso exige validação adicional. Use a Área do Colaborador para concluir a biometria/passkey e depois retorne.');if(d.role!=='CONSULTANT')throw new Error('Este usuário não é consultor.');localStorage.setItem(T,d.token);localStorage.setItem(R,d.role);await boot()}catch(x){msg('login-msg',x.message)}});
$('password-form').addEventListener('submit',async e=>{e.preventDefault();try{const result=await req('/api/auth/change-password',{method:'POST',body:JSON.stringify({currentPassword:$('current-pass').value,newPassword:$('new-pass').value})});
  if(result.passwordChangeRequired)throw new Error('A troca de senha ainda não foi concluída. Tente novamente.');
  $('password-dialog').close();
  $('password-form').reset();
  msg('password-msg','');
  await boot()}catch(x){msg('password-msg',x.message)}});
$('pix-form').addEventListener('submit',async e=>{e.preventDefault();try{finance=await req('/api/commercial/me/finance/pix',{method:'PUT',body:JSON.stringify({type:$('pix-type').value,key:$('pix-key').value})});msg('pix-msg','Chave Pix salva.',false);$('pix-key').value='';render()}catch(x){msg('pix-msg',x.message)}});
$('withdraw-method').addEventListener('change',()=>{$('ted-fields').hidden=$('withdraw-method').value!=='TED'});
$('withdraw-form').addEventListener('submit',async e=>{e.preventDefault();try{const amount=Number($('withdraw-amount').value);const payoutMethod=$('withdraw-method').value;const bankDetails=$('bank-details').value.trim()||null;if(payoutMethod==='TED'&&!bankDetails)throw new Error('Informe os dados bancários para TED.');const fee=payoutMethod==='PIX'?Number(finance.withdrawalFee||0):0;if(!confirm(`Enviar solicitação ao Financeiro?\nValor solicitado: ${money(amount)}\nTaxa: ${money(fee)}\nValor líquido estimado: ${money(amount-fee)}`))return;await req('/api/commercial/me/finance/withdrawals',{method:'POST',body:JSON.stringify({amount,payoutMethod,bankDetails})});msg('withdraw-msg','Solicitação enviada ao Financeiro.',false);e.target.reset();$('ted-fields').hidden=true;await loadAll()}catch(x){msg('withdraw-msg',x.message)}});
async function openVehicle(id){try{const x=await req(`/api/commercial/vehicles/${id}`),v=x.vehicle;$('vehicle-dialog-title').textContent=`${v.plate||'Sem placa'} · ${v.model||''}`;$('vehicle-dialog-summary').innerHTML=`<p>${v.associateName||''} · ${v.planName||''} · ${money(v.monthlyValue)}/mês</p>`;$('vehicle-charges').innerHTML=(x.charges||[]).map(c=>`<tr><td>${c.type}</td><td>${date(c.dueDate)}</td><td>${money(c.totalCharged||c.value)}</td><td>${c.status}</td><td>${c.invoiceUrl?`<a href="${c.invoiceUrl}" target="_blank">Abrir</a>`:'—'}</td></tr>`).join('')||'<tr><td colspan="5">Nenhuma cobrança.</td></tr>';$('vehicle-dialog').showModal()}catch(e){alert(e.message)}}
$('vehicle-dialog-close').onclick=()=>$('vehicle-dialog').close();
async function downloadDoc(id){const r=await fetch(api(`/api/commercial/me/documents/${id}/file`),{headers:{Authorization:`Bearer ${localStorage.getItem(T)}`}});if(!r.ok)throw new Error('Não foi possível baixar o documento.');const b=await r.blob(),u=URL.createObjectURL(b),a=document.createElement('a');a.href=u;a.download='documento';a.click();URL.revokeObjectURL(u)}
$('invoice-form').addEventListener('submit',async e=>{e.preventDefault();try{const file=$('invoice-file').files[0];if(!file)throw new Error('Selecione a nota fiscal.');const f=new FormData();if($('invoice-month').value)f.append('referenceMonth',$('invoice-month').value);f.append('file',file);await req('/api/commercial/me/documents/invoice',{method:'POST',body:f});msg('invoice-msg','Nota fiscal enviada ao Financeiro.',false);e.target.reset();await loadAll()}catch(x){msg('invoice-msg',x.message)}});
document.querySelectorAll('[data-tab]').forEach(b=>b.addEventListener('click',()=>{document.querySelectorAll('[data-tab]').forEach(x=>x.classList.toggle('active',x===b));['resumo','comercial','carteira','financeiro'].forEach(t=>{const el=$(`tab-${t}`);if(el)el.hidden=t!==b.dataset.tab})}));
$('change-pass-button').addEventListener('click',()=>{if(!$('password-dialog').open)$('password-dialog').showModal()});
$('password-dialog').addEventListener('cancel',e=>{if(me?.passwordChangeRequired)e.preventDefault()});
$('logout').addEventListener('click',()=>logout());
boot();
