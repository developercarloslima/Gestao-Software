const test=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
const root=path.resolve(__dirname,'../..');
const get=p=>fs.readFileSync(path.join(root,p),'utf8');
const src=get('web/admin/admin.js');
const start=src.indexOf('// V83.127 — o ADM possui');
const end=src.indexOf('function openInspectionAnalysis(id)',start);
assert.ok(start>=0&&end>start,'Trecho operacional do ADM existe');
function setup(data) {
 const elements={};let calls=[];
 function $(id){return elements[id]??(elements[id]={value:'',disabled:false,hidden:false,options:[],style:{},dataset:{},textContent:'',listeners:{},replaceChildren(...a){this.options=[...a];this.value='';},add(option){this.options.push(option)},addEventListener(event,fn){this.listeners[event]=fn;}})}
 const money={format:v=>Number(v).toLocaleString('pt-BR',{minimumFractionDigits:2,maximumFractionDigits:2}),parse:v=>Number(String(v).replace(/\./g,'').replace(',','.')),refresh(){}};
 const item={id:'example-id',requestType:'NEW_INSPECTION',vehicleType:'FOUR_WHEELS_OR_MORE',status:'UNDER_REVIEW',fipeValue:80000,modelYear:2022,vehicleModel:'Corolla'};
 $('inspection-analysis-id').value=item.id;
 $('inspection-edit-model').value='Corolla';$('inspection-edit-model-year').value='2022';$('inspection-edit-zero-km').value='false';
 const ctx={$,document:{getElementById:$},window:{NHMoney:money,confirm:()=>true},navigator:{clipboard:{writeText:async()=>{}}},
   adminFipeChoices:[],adminFipeRecordId:null,adminFipeRecordValueCents:null,adminFipeSequence:0,adminFipeTimer:null,
   adminPlansSequence:0,adminAnalystsSequence:0,adminCepSequence:0,adminCepTimer:null,adminCepAbort:null,adminCepLast:'',adminCepAutofill:{},
   ADMIN_ADDRESS_FIELDS:{street:'admin-edit-street',number:'admin-edit-number',complement:'admin-edit-complement',neighborhood:'admin-edit-neighborhood',city:'admin-edit-city',state:'admin-edit-state'},
   inspections:[item],brl:new Intl.NumberFormat('pt-BR',{style:'currency',currency:'BRL'}),Option:function(label,value){this.label=label;this.value=value;},
   URLSearchParams,encodeURIComponent,Number,String,Boolean,Object,Array,Set,AbortController,clearTimeout(){},setTimeout(){},
   ADMIN_COMMERCIAL_CONFIG:{},refreshCommercialRules(){},markCommercialMonthlyAutomatic(){},scheduleCommercialPricingPreview(){},
   message:()=>{},renderInspections(){},openInspectionAnalysis(){},date:v=>v,api:async(url,options)=>{calls.push({url,options});
    if(url.includes('fipe/versions'))return data;
    if(url.includes('/plans'))return [{name:'Econômico',code:'ECON'},{name:'Completo',code:'COMP'}];
    if(url.endsWith('/analysts'))return [{name:'Analista de teste',id:'aa-uuid'}];
    return item;
   }};
 vm.runInNewContext(src.slice(start,end),ctx);
 return{ctx,$,calls,item};
}
const row=(id,model,valueCents)=>({id,brand:'Toyota',model,fuel:'Flex',valueCents,codeFipe:`FIP${id}`,referenceMonth:10,referenceYear:2026});
test('ADM: quando há várias versões, exige escolha explícita; a seleção preenche preço e código',async()=>{
 const {ctx,$,calls}=setup([row(1,'Corolla GLi',10000000),row(2,'Corolla XEi',12000000)]);
 await vm.runInNewContext('lookupAdminFipeVersions()',ctx);
 assert.equal(ctx.adminFipeRecordId,null);
 assert.equal($('admin-fipe-version').options.length,3);
 assert.ok(calls.some(c=>c.url.startsWith('/api/analysis/fipe/versions?')));
 $('admin-fipe-version').value='2';$('admin-fipe-version').listeners.change();
 assert.equal(ctx.adminFipeRecordId,2);
 assert.equal($('admin-fipe-code').value,'FIP2');
 assert.equal($('admin-contract-fipe').value,'120.000,00');
});
test('ADM: versão única é selecionada; edição manual remove vínculo FIPE',async()=>{
 const {ctx,$}=setup([row(3,'Corolla GLi',11050000)]);
 await vm.runInNewContext('lookupAdminFipeVersions()',ctx);
 assert.equal(ctx.adminFipeRecordId,3);
 $('admin-contract-fipe').value='120.000,00';$('admin-contract-fipe').listeners.input();
 assert.equal(ctx.adminFipeRecordId,null);assert.equal($('admin-fipe-code').value,'');
});
test('ADM: não há atribuição de código inexistente',async()=>{
 const {ctx,$}=setup([]);await vm.runInNewContext('lookupAdminFipeVersions()',ctx);
 assert.equal(ctx.adminFipeRecordId,null);assert.equal($('admin-fipe-code').value,'');
});
test('ADM: plano, analista, CEP e termo independente existem no próprio painel',()=>{
 const html=get('web/admin/index.html');
 for(const id of ['admin-contract-plan-select','admin-save-contract-plan','admin-inspection-analyst','admin-save-inspection-analyst',
  'admin-edit-cep','admin-edit-street','admin-edit-number','admin-edit-state','admin-parts-delay-term-box','admin-fipe-version','admin-fipe-code'])
   assert.match(html,new RegExp(`id="${id}"`));
 for(const url of ['/api/admin/inspections/${encodeURIComponent(id)}/plan',
  '/api/admin/inspections/${encodeURIComponent(id)}/analyst', '/api/admin/inspections/${encodeURIComponent(id)}/parts-delay-term'])
   assert.ok(src.includes(url),`Endpoint utilizado: ${url}`);
 const controller=get('backend/src/main/java/br/com/nh/cotacao/controller/AdminInspectionController.java');
 assert.match(controller,/@GetMapping\("\/\{id\}\/plans"\)/);
 assert.match(controller,/@PatchMapping\("\/\{id\}\/plan"\)/);
 assert.match(controller,/@PatchMapping\("\/\{id\}\/analyst"\)/);
 assert.match(src,/fipeRecordId: adminFipeRecordId/);
 const service=get('backend/src/main/java/br/com/nh/cotacao/service/AdminActivityService.java');
 assert.match(service,/vehicleFipeIdentityChanged/);
 assert.match(service,/quotation\.updateFipeMetadata\(null, null, null\)/);
});
