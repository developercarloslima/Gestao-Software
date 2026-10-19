const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const root=path.resolve(__dirname,'../..');
const read=p=>fs.readFileSync(path.join(root,p),'utf8');
function env(){
 const elements={};
 const el=id=>elements[id] ||= {value:'',disabled:false,options:[],handlers:{},
   replaceChildren(...children){this.options=children;this.value='';},
   add(child){this.options.push(child)},
   addEventListener(name,cb){this.handlers[name]=cb;}};
 const calls=[]; let changed=0,status='';
 const rows={types:['Motos','Caminhões','Carros'],brands:['Yamaha','Honda','BMW'],models:['CG 160 TITAN','CG 160 FAN','CG 125']};
 const api=async url=>{
  calls.push(url);
  if(url.includes('/types'))return rows.types;
  if(url.includes('/brands'))return rows.brands;
  if(url.includes('/models'))return rows.models;
  throw Error('Endpoint inesperado: '+url);
 };
 const ctx={window:{},Option:function(label,value){this.label=label;this.value=value;},
   setTimeout:fn=>{fn();return 1},clearTimeout:()=>{},URLSearchParams};
 vm.runInNewContext(read('web/shared/fipe-picker.js'),ctx);
 const p=ctx.window.NHFipePicker.create({get:el,api,typeId:'type',brandId:'brand',brandListId:'brands',
   modelId:'model',modelListId:'models',onFilterChange:()=>changed++,onStatus:s=>status=s});
 return {el,calls,ctx,p,changed:()=>changed,status:()=>status};
}
test('marcas e modelos são ordenados, tipo motos é selecionado e marca antiga é recuperada',async()=>{
 const {p,el,calls,changed}=env();
 await p.initialize({vehicleType:'MOTORCYCLE',vehicleModel:'Honda CG 160'},false);
 assert.equal(el('type').value,'Motos');
 assert.deepEqual(el('type').options.map(o=>o.label),['Motos']);
 assert.deepEqual(el('brands').options.map(o=>o.label),['BMW','Honda','Yamaha']);
 assert.equal(el('brand').value,'Honda');
 assert.equal(el('model').value,'CG 160');
 assert.deepEqual(el('models').options.map(o=>o.label),['CG 125','CG 160 FAN','CG 160 TITAN']);
 assert.equal(p.queryModel(),'CG 160');
 assert.equal(p.persistedModel(),'Honda CG 160');
 assert.ok(calls.some(c=>c.includes('type=Motos')));
 assert.ok(changed()>0);
});
test('marca/modelo podem ser preenchidos manualmente; troca de tipo remove filtros antigos',async()=>{
 const {p,el}=env();
 await p.initialize({vehicleType:'FOUR_WHEELS_OR_MORE',vehicleModel:'Corolla'},false);
 assert.equal(el('type').value,'Carros');
 el('brand').value='Toyota';el('brand').handlers.change();
 el('model').value='Corolla XEi';
 assert.equal(p.persistedModel(),'Toyota Corolla XEi');
 assert.equal(p.queryModel(),'Corolla XEi');
 el('type').value='Caminhões';el('type').handlers.change();
 assert.equal(el('brand').value,'');
 assert.equal(el('model').value,'');
});
test('catálogo existe na Análise e no ADM; rota com marca filtra a tabela original',()=>{
 for(const area of ['analise','admin']){
   const html=read(`web/${area}/index.html`);
   assert.match(html,/fipe-picker\.js\?v=20261016-v83-137/);
   for(const name of ['fipe-type','fipe-brand','fipe-brand-list','fipe-model-list'])
     assert.ok(html.includes(name),`${area}: ${name}`);
 }
 const ctrl=read('backend/src/main/java/br/com/nh/cotacao/controller/AnalysisFipeController.java');
 for(const endpoint of ['/types','/brands','/models'])assert.ok(ctrl.includes(`@GetMapping("${endpoint}")`));
 assert.match(ctrl,/catalog\.versionsByBrand\(brand,model,year/);
 const svc=read('backend/src/main/java/br/com/nh/cotacao/service/FipeDatasetCatalogService.java');
 assert.match(svc,/"\/consulta\?marca="\+q\(brand\)\+"&modelo="\+q\(model\)/);
 assert.match(svc,/mapVehicleRows\(get\(url\)/);
});
