'use strict';
const API='/api/admin/implantacao';
const steps={
 BRAND_REVIEWED:['1','Confira a identidade visual','Veja o logotipo, a cor e o nome que aparecem nos portais e documentos.','/admin/','Abrir personalização'],
 ADMIN_PASSWORD_ROTATED:['2','Proteja o acesso administrativo','Entre com o administrador inicial, troque a senha e teste um novo login.','/admin/','Abrir painel'],
 BUSINESS_CATALOG_REVIEWED:['3','Cadastre planos, preços e coberturas','Nenhum preço foi importado de outra empresa. Configure os planos contratados e revise os valores.','/admin/','Configurar planos'],
 REGULATION_PUBLISHED:['4','Publique o regulamento da empresa','Envie o PDF jurídico aprovado pela empresa; não use o regulamento de outro cliente.','/admin/','Enviar regulamento'],
 PAYMENT_WEBHOOK_VERIFIED:['5','Verifique a integração financeira','Teste o retorno real do provedor de pagamentos com o ambiente de homologação.','/admin/','Ver configurações'],
 PONTO_SETUP_REVIEWED:['7','Configure o Apontamento Digital','Revise as jornadas, tolerâncias, exigência de biometria, selfie e regras de aprovação antes da primeira marcação.','/ponto/','Abrir Apontamento Digital'],
 BACKUP_RESTORE_VERIFIED:['6','Teste o backup e a restauração','Confirme que o banco e os arquivos podem ser recuperados em um ambiente isolado.','/admin/','Voltar ao painel']
};
const $=id=>document.getElementById(id);
let report=null;let approving=null;
function el(tag,cls,text){const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)n.textContent=text;return n;}
function token(){return localStorage.getItem('nhPortalToken')||'';}
async function request(path='',options={}){
 const auth=token();if(!auth){location.replace('/admin/');throw Error('Faça login como administrador para configurar a empresa.');}
 const response=await fetch(API+path,{...options,headers:{Authorization:'Bearer '+auth,...(options.body?{'Content-Type':'application/json'}:{}),...(options.headers||{})}});
 if(response.status===401){location.replace('/admin/');throw Error('Sessão expirada. Entre novamente.');}
 let payload=await response.json().catch(()=>({}));
 if(!response.ok)throw Error(payload.message||payload.detail||'Não foi possível realizar a operação. Verifique a etapa e tente novamente.');
 return payload;
}
function buildStep(key,information,index){
 const approved=Boolean(report.requirements?.[key]?.approved);
 const card=el('article','step'+(approved?' complete':''));
 card.append(el('span','number',approved?'✓':String(index+1)));
 const body=el('div');body.append(el('h2','',information[1]),el('p','',information[2]));
 const row=el('div','actions');const link=el('a','btn',information[4]+' ↗');link.href=information[3];row.append(link);
 const action=el('button','btn'+(approved?'':' primary'),approved?'Revisar aprovação':'Confirmar verificação');action.type='button';action.addEventListener('click',()=>openDialog(key,approved));row.append(action);
 body.append(row);
 if(approved){const details=report.requirements[key];const evidence=el('small','',`Conferido por ${details.approvedBy||'administrador'} em ${details.approvedAt?new Date(details.approvedAt).toLocaleString('pt-BR'):'data não informada'}.`);body.append(evidence);}
 card.append(body,el('span','tag'+(approved?' ok':''),approved?'Concluído':'Pendente'));return card;
}
function render(data){report=data;let keys=Object.keys(steps);if(!data.commercialModuleEnabled)keys=keys.filter(k=>!['BUSINESS_CATALOG_REVIEWED','REGULATION_PUBLISHED'].includes(k));if(!data.paymentModuleEnabled)keys=keys.filter(k=>k!=='PAYMENT_WEBHOOK_VERIFIED');if(!data.pontoModuleEnabled)keys=keys.filter(k=>k!=='PONTO_SETUP_REVIEWED');
 const completed=keys.filter(k=>data.requirements?.[k]?.approved).length;const pct=keys.length?Math.round(completed/keys.length*100):0;
 $('progress').textContent=`${completed} de ${keys.length}`;$('plans').textContent=String(data.activePlans??0);$('coverages').textContent=String(data.coverageLinks??0);
 $('progressbar').setAttribute('aria-valuenow',String(pct));$('fill').style.width=pct+'%';
 const overall=$('overall');overall.className='notice'+(data.readyForCustomers?'':' alert');overall.replaceChildren();
 const symbol=el('span','',data.readyForCustomers?'✓':'⏳');symbol.setAttribute('aria-hidden','true');overall.append(symbol);
 const body=el('div');body.append(el('strong','',data.readyForCustomers?'Configuração concluída':'Faltam verificações antes da liberação'),el('span','',data.readyForCustomers?'A Gestão ainda verificará HTTPS, disponibilidade e identidade antes de liberar o endereço.':'Finalize as etapas pendentes. Planos ou regulamento ausentes também impedem a liberação.'));
 overall.append(body);
 const container=$('steps');container.replaceChildren(...keys.map((k,index)=>buildStep(k,steps[k],index)));
 if(data.commercialModuleEnabled && (!data.activePlans||!data.coverageLinks)){
  container.prepend(el('div','notice alert','Ainda não existem planos ativos e coberturas vinculadas. Cadastre os produtos comerciais antes de aprovar essa etapa.'));
 }
 if(data.missing?.includes('RESPONSIBILITY_TERM_CLAUSES_MISSING')){
  container.append(el('div','notice alert','O Termo de Responsabilidade ainda precisa das cláusulas jurídicas próprias da empresa. Solicite ao responsável técnico a configuração de TENANT_RESPONSIBILITY_TERM_CLAUSES antes de liberar a operação.'));
 }
 if(data.commercialModuleEnabled && !data.regulationUploaded){
  container.append(el('div','notice alert','Regulamento não enviado. Abra o Painel Administrativo para enviar um PDF próprio da empresa.'));
 }
}
function openDialog(key,approved){approving={key,approved};$('approval-title').textContent=approved?'Revisar verificação':'Registrar verificação';$('approval-description').textContent=steps[key][2]+(approved?' Para revogar a aprovação, use o botão abaixo.':'');
 $('evidence').value=report.requirements?.[key]?.evidenceUri||'';$('confirm').textContent=approved?'Revogar aprovação':'Confirmar verificação';$('evidence').required=!approved;$('approval').showModal();}
$('cancel').addEventListener('click',()=>$('approval').close());
$('approval-form').addEventListener('submit',async event=>{
 event.preventDefault();if(!approving)return;
 $('confirm').disabled=true;$('message').textContent='';
 try{
  const data=await request('/'+encodeURIComponent(approving.key),{method:'PUT',body:JSON.stringify({approved:!approving.approved,evidenceUri:approving.approved?null:$('evidence').value.trim()})});
  $('approval').close();render(data);$('message').textContent='A verificação foi atualizada.';
 }catch(error){$('message').textContent=error.message;}finally{$('confirm').disabled=false;}
});
request().then(render).catch(e=>{$('message').textContent=e.message;$('overall').replaceChildren(el('strong','', 'Não foi possível conferir a implantação.'));});
