// A partir da v83.124, o CEP existe somente na análise da vistoria.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const app = fs.readFileSync(path.join(__dirname,'../analise/analise.js'),'utf8');
const source = app.slice(app.indexOf('// CEP exclusivo do cadastro pelo analista'), app.indexOf('let analysisPlansRequest'));
function setup(fetch) {
  const ids = ['edit-residence-cep','edit-residence-street','edit-residence-number','edit-residence-complement',
    'edit-residence-neighborhood','edit-residence-city','edit-residence-state','analysis-cep-status','analysis-find-cep',
    'edit-residence-address','analysis-legacy-address-wrap'];
  const fields=Object.fromEntries(ids.map(id=>[id,{value:'',disabled:false,hidden:false,textContent:'',dataset:{},
    options:['AL','SP','RJ'].map(value=>({value})),addEventListener:()=>{}}]));
  const ctx={AbortController,setTimeout,clearTimeout,fetch,$:id=>fields[id]};
  vm.createContext(ctx); vm.runInContext(source,ctx);
  return {fields,eval:expression=>vm.runInContext(expression,ctx)};
}
test('máscara de CEP e endereço completo ficam na análise',()=>{
 const {fields,eval:run}=setup(()=>{});
 assert.equal(run("cepMask('57020100')"),'57020-100');
 fields['edit-residence-cep'].value='57020-100';
 for(const [k,v] of Object.entries({'edit-residence-street':'Rua Exemplo','edit-residence-number':'125',
 'edit-residence-neighborhood':'Centro','edit-residence-city':'Maceió','edit-residence-state':'AL'}))fields[k].value=v;
 assert.equal(run('analysisAddressPayload()'),'Rua Exemplo, nº 125, Centro, Maceió/AL, CEP 57020-100');
 fields['edit-residence-number'].value='';
 assert.throws(()=>run('analysisAddressPayload()'),/rua, número/);
});
test('OpenCEP preenche sem inventar número e complemento',async()=>{
 const {fields,eval:run}=setup(async()=>({ok:true,json:async()=>({logradouro:'Rua A',bairro:'Centro',localidade:'Maceió',uf:'AL'})}));
 fields['edit-residence-cep'].value='57020100';
 assert.equal(await run('lookupAnalysisCep(true)'),true);
 assert.equal(fields['edit-residence-street'].value,'Rua A');
 assert.equal(fields['edit-residence-state'].value,'AL');
 assert.equal(fields['edit-residence-number'].value,'');
 assert.equal(fields['edit-residence-complement'].value,'');
});
test('ViaCEP é alternativa ao OpenCEP',async()=>{
 const calls=[];
 const {fields,eval:run}=setup(async(url)=>{
   calls.push(url);
   if(url.includes('opencep')) throw new Error('Sem resposta');
   return {ok:true,json:async()=>({logradouro:'Rua Reserva',bairro:'A',localidade:'São Paulo',uf:'SP'})};
 });
 fields['edit-residence-cep'].value='01001000';
 assert.equal(await run('lookupAnalysisCep(true)'),true);
 assert.equal(fields['edit-residence-street'].value,'Rua Reserva');
 assert.equal(calls.length,2);
});
test('CEP indisponível permite preenchimento manual',async()=>{
 const {fields,eval:run}=setup(async()=>({ok:false,json:async()=>({})}));
 fields['edit-residence-cep'].value='99999999';
 assert.equal(await run('lookupAnalysisCep(true)'),false);
 assert.match(fields['analysis-cep-status'].textContent,/manualmente/);
});
test('consulta antiga não altera CEP novo',async()=>{
 let release;
 const {fields,eval:run}=setup(url=>new Promise(resolve=>{
  if(url.includes('57020100')) release=()=>resolve({ok:true,json:async()=>({logradouro:'Antiga',bairro:'A',localidade:'Maceió',uf:'AL'})});
  else resolve({ok:true,json:async()=>({logradouro:'Nova',bairro:'B',localidade:'São Paulo',uf:'SP'})});
 }));
 fields['edit-residence-cep'].value='57020100';
 const old=run('lookupAnalysisCep(true)');
 await new Promise(r=>setTimeout(r,1));
 fields['edit-residence-cep'].value='01001000';
 const next=run('lookupAnalysisCep(true)');
 release();await Promise.all([old,next]);
 assert.equal(fields['edit-residence-street'].value,'Nova');
});
