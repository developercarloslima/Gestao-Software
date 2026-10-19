const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const read = rel => fs.readFileSync(path.join(root,rel),'utf8');
const source = read('web/analise/analise.js');
const start = source.indexOf('// Consulta local ao dataset validado.');
const end = source.indexOf('function openInspection(id)',start);
assert.ok(start>0 && end>start);
const snippet=source.slice(start,end);
function setup(rows) {
 const elements = {};
 function element(id) {
  if(!elements[id]) {
   elements[id]={value:'',disabled:false,style:{},textContent:'', listeners:{},options:[],
    replaceChildren(...args){this.options=args;this.value='';}, add(x){this.options.push(x)},
    addEventListener(type,fn){this.listeners[type]=fn;}};
  }
  return elements[id];
 }
 const money = {format(x){return Number(x).toLocaleString('pt-BR',{minimumFractionDigits:2,maximumFractionDigits:2})},refresh(){},parse(x){return Number(String(x).replace(/\./g,'').replace(',','.'))}};
 const inspection={id:'id-1',requestType:'NEW_INSPECTION',vehicleType:'FOUR_WHEELS_OR_MORE'};
 element('inspection-id').value='id-1';
 element('edit-vehicle-model').value='Corolla';
 element('edit-model-year').value='2022';
 element('edit-zero-km').value='false';
 const ctx={document:{getElementById:element},$, window:{NHMoney:money},
  brl:new Intl.NumberFormat('pt-BR',{style:'currency',currency:'BRL'}),
  ANALYSIS_COMMERCIAL_CONFIG:{},inspections:[inspection],
  registrationLocked:()=>false, Option:function(label,value){this.label=label;this.value=value;},
  URLSearchParams, Number,String,Array,Set,
  fipeVersionChoices:[],selectedFipeRecordId:null,selectedFipeRecordValueCents:null,selectedFipeModel:null,
  fipeVersionSearchSequence:0,fipeVersionSearchTimer:null,
  clearTimeout(){},setTimeout(){},refreshCommercialRules(){}, markCommercialMonthlyAutomatic(){},scheduleCommercialPricingPreview(){},
  api:async(_url)=>rows};
 function $(id){return element(id)}
 vm.runInNewContext(snippet,ctx);
 return {ctx,element};
}
const fixture = (id,model,price) => ({id,model,brand:'Toyota',fuel:'Flex',valueCents:price,codeFipe:`001${id}`,referenceMonth:10,referenceYear:2026});
test('várias versões exigem seleção, sem escolher valor arbitrário',async()=>{
 const {ctx,element}=setup([fixture(1,'Corolla GLi',10000000),fixture(2,'Corolla XEi',12000000)]);
 await vm.runInNewContext('lookupAnalysisFipeVersions()',ctx);
 assert.equal(element('edit-fipe-code').value,'');
 assert.equal(element('edit-contract-fipe').value,'');
 assert.equal(element('edit-fipe-version').options.length,3);
 element('edit-fipe-version').value='2';
 element('edit-fipe-version').listeners.change();
 assert.equal(element('edit-fipe-code').value,'0012');
 assert.equal(element('edit-fipe-display').value,'R$ 120.000,00');
 assert.equal(element('edit-contract-fipe').value,'120.000,00');
 assert.equal(ctx.selectedFipeRecordId,2);
});
test('uma única versão preenche código e valor automaticamente',async()=>{
 const {ctx,element}=setup([fixture(3,'Corolla XEi',12345678)]);
 await vm.runInNewContext('lookupAnalysisFipeVersions()',ctx);
 assert.equal(ctx.selectedFipeRecordId,3);
 assert.equal(element('edit-fipe-code').value,'0013');
 assert.equal(element('edit-contract-fipe').value,'123.456,78');
});
test('sem FIPE não inventa código e mantém correção manual disponível',async()=>{
 const {ctx,element}=setup([]);
 await vm.runInNewContext('lookupAnalysisFipeVersions()',ctx);
 assert.equal(ctx.selectedFipeRecordId,null);
 assert.equal(element('edit-fipe-code').value,'');
 assert.match(element('analysis-fipe-version-status').textContent,/Nenhuma correspondência/);
});
test('migrações preservam termo de ciência de peças e incluem tabela FIPE separada',()=>{
 const term=read('backend/src/main/resources/db/tenant/V3__term_acceptance_audit_columns.sql');
 const catalog=read('backend/src/main/resources/db/tenant/V5__fipe_dataset_snapshots.sql');
 const record=read('backend/src/main/resources/db/tenant/V6__inspection_fipe_version_selection.sql');
 assert.match(term,/ALTER TABLE inspection_responsibility_terms/);
 assert.match(catalog,/CREATE TABLE fipe_dataset_prices/);
 assert.match(record,/CREATE TABLE inspection_pending_fipe_selection/);
 assert.match(read('backend/src/main/java/br/com/nh/cotacao/service/InspectionFipeSelectionService.java'),/catalog\.verifiedPrice/);
});
