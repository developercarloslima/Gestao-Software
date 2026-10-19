'use strict';
// Apenas navegação: a Gestão não recebe ou guarda os dados pessoais dos associados.
(()=>{
const choose=document.getElementById('migration-company');
const state=document.getElementById('migration-state');
const link=document.getElementById('migration-open');
const tab=document.querySelector('[data-tab="migration"]');
if(!choose || !tab)return;
function clear(){link.hidden=true;link.removeAttribute('href');}
function populate(){const previous=choose.value;choose.replaceChildren(new Option('Escolha a empresa',''));for(const company of companies){choose.add(new Option(company.tradeName,company.id));}choose.value=previous;}
async function load(){clear();if(!choose.value){state.textContent='Escolha uma empresa para ver o endereço seguro de importação.';return;}
state.textContent='Conferindo a implantação da empresa...';
try{const c=await api(`/api/platform/companies/${encodeURIComponent(choose.value)}`);const app=c.hosting?.appUrl;const url=app?safeUrl(app):null;
if(!url){state.textContent='Esta empresa ainda não tem URL de hospedagem cadastrada. Conclua a implantação e configure o endereço em Hospedagem.';return;}
const parsed=new URL(url);if(parsed.protocol!=='https:' && !['localhost','127.0.0.1'].includes(parsed.hostname)){state.textContent='O endereço precisa usar HTTPS antes de receber dados reais de clientes.';return;}
if(c.status==='SUSPENDED'||c.status==='CANCELLED'){state.textContent='A empresa está suspensa ou cancelada. Regularize o cadastro antes de enviar informações.';return;}
link.href=parsed.origin+'/admin/importacao.html';link.hidden=false;
state.textContent='A importação é feita diretamente no ambiente isolado desta empresa. Entre com o administrador da empresa; sua conta da Gestão não dá acesso automático aos dados dela.';
}catch(e){state.textContent='Não foi possível conferir a empresa: '+e.message;}}
tab.addEventListener('click',()=>{populate();load();});choose.addEventListener('change',load);
})();
