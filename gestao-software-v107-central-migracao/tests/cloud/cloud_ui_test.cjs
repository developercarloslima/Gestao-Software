const fs = require('fs'), vm = require('vm'), assert = require('assert');
const file=require('path').join(__dirname,'../../web/admin/cloud.js');
const handlers={}; const elements={};
const companies=[{id:'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',tradeName:'Empresa Cliente'}];
const vpsId='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const model={baseDomain:'gestaosoftware.com.br',registeredServers:1,onlineServers:1,domainCount:0,
  servers:[{id:vpsId,name:'VPS 01',provider:'Teste',status:'AVAILABLE',active:true,healthyForStart:true,
  agentOnline:true,remainingSlots:3,maxCompanies:4,usedCompanies:1,cpuPercent:15,memoryPercent:20,diskPercent:30}],domains:[]};
function el(id){return elements[id] ||= {id,value:'',textContent:'',innerHTML:'',className:'',style:{},disabled:false,
 addEventListener(name,fn){handlers[id+':'+name]=fn;},classList:{contains(){return true;}}};}
let requests=[];
async function api(path,options={}){
 requests.push({path,options});
 if(path.endsWith('/overview'))return model;
 if(path.includes('/disponibilidade'))return {available:true,hostname:'cliente.gestaosoftware.com.br'};
 if(path.includes('/endereco'))return {status:'RESERVED',hostname:'cliente.gestaosoftware.com.br'};
 throw Error('unexpected API '+path);
}
class FakeObserver{observe(){}}
vm.runInNewContext(fs.readFileSync(file,'utf8'),{
 document:{readyState:'complete',getElementById:el},window:{},companies,api,MutationObserver:FakeObserver,console
},{filename:file});
(async()=>{
  await handlers['cloud-refresh:click']();
  assert.equal(el('cloud-count-online').textContent,1);
  assert(el('cloud-servers').innerHTML.includes('VPS 01'));
  assert(el('cloud-domains').innerHTML.includes('Nenhum endereço'));
  el('cloud-company').value=companies[0].id;
  el('cloud-server').value=vpsId;
  el('cloud-slug').value='cliente';
  handlers['cloud-slug:input']();
  assert.equal(el('cloud-domain-preview').textContent,'cliente.gestaosoftware.com.br');
  await handlers['cloud-check:click']();
  assert.equal(el('cloud-form-message').className,'form-message success');
  await handlers['cloud-domain-form:submit']({preventDefault(){}});
  assert(el('cloud-form-message').textContent.includes('reservado'));
  const put=requests.find(x=>x.options.method==='PUT');
  assert.equal(JSON.parse(put.options.body).vpsId,vpsId);
  assert.equal(JSON.parse(put.options.body).slug,'cliente');
  console.log('PASS: carga, capacidade, disponibilidade, reserva e payload seguro do painel');
})().catch(e=>{console.error(e);process.exit(1)});
