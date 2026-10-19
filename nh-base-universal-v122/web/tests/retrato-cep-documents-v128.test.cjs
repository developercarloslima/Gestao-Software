const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.join(__dirname, '..');
function load(file) {
  const window = {};
  const fields = Object.fromEntries(['cep','street','number','complement','neighborhood','city','state'].map(x=>[`residence-${x}`,{value:'',required:false,options:[{value:'AL'},{value:'SP'}]}]));
  fields['residence-address']={value:'',dispatchEvent(){}};
  fields['residence-cep-status']={textContent:'',dataset:{}};
  const doc = {getElementById:id=>fields[id] || null};
  const context={window,document:doc,console,AbortController,fetch:async()=>({ok:false}),setTimeout,clearTimeout,Event: class Event{}};
  vm.runInNewContext(fs.readFileSync(path.join(root,file),'utf8'),context,{filename:file});
  return {window,fields,context};
}
test('CEP requerido — monta endereço padronizado com código postal e número',()=>{
  const {window}=load('shared/retrato-address.js');
  const value=window.NHResidenceAddress.compose({cep:'57010-000',street:'Rua das Flores',number:'125',complement:'Apto 2',neighborhood:'Centro',city:'Maceió',state:'AL'});
  assert.equal(value,'Rua das Flores, nº 125, Apto 2, Centro, Maceió/AL, CEP 57010-000');
  const restored=window.NHResidenceAddress.parse(value);
  assert.equal(restored.cep,'57010-000');
  assert.equal(restored.number,'125');
  assert.equal(restored.city,'Maceió');
});
test('não aceita CEP incompleto ou endereço sem número',()=>{
  const {window}=load('shared/retrato-address.js');
  assert.throws(()=>window.NHResidenceAddress.compose({cep:'570100',street:'Rua das Flores',number:'125',neighborhood:'Centro',city:'Maceió',state:'AL'}),/CEP/);
  assert.throws(()=>window.NHResidenceAddress.compose({cep:'57010000',street:'Rua das Flores',number:'',neighborhood:'Centro',city:'Maceió',state:'AL'}),/Número/);
});
test('consulta o OpenCEP e preenche rua, bairro, cidade e UF sem inventar número',async()=>{
  const {window,fields,context}=load('shared/retrato-address.js');
  fields['residence-cep'].value='57010-000';
  context.fetch=async()=>({ok:true,json:async()=>({logradouro:'Rua A',bairro:'Centro',localidade:'Maceió',uf:'AL'})});
  const found=await window.NHResidenceAddress.lookup();
  assert.equal(found,true);
  assert.equal(fields['residence-street'].value,'Rua A');
  assert.equal(fields['residence-city'].value,'Maceió');
  assert.equal(fields['residence-state'].value,'AL');
  assert.equal(fields['residence-number'].value,'');
  assert.equal(fields['residence-address'].value,'');
});
test('termos não ficam assinados apenas porque houve envio',()=>{
  const {window}=load('shared/inspection-documents.js');
  assert.equal(window.NHInspectionDocuments.stateLabel(false,'2026-10-15T15:00:00Z'),'Pendente');
  assert.match(window.NHInspectionDocuments.stateLabel(true,'2026-10-15T15:00:00Z'),/Termo assinado digitalmente/);
});
test('painéis têm central de documentos com os mesmos arquivos e fluxos',()=>{
  for(const panel of ['admin','analise','supervisao']){
    const html=fs.readFileSync(path.join(root,panel,'index.html'),'utf8');
    assert.match(html, /inspection-documents\.js\?v=/);
    assert.match(html, /-document-center/);
  }
  const analysis=fs.readFileSync(path.join(root,'analise','analise.js'),'utf8');
  assert.ok(!analysis.includes('/api/analise/inspections/'));
});
