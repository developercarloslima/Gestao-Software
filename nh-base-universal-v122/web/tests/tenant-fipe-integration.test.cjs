const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname,'../..');
const read = f => readFileSync(path.join(root,f),'utf8');
const java = f => read(`backend/src/main/java/br/com/nh/cotacao/${f}`);
const sql = f => read(`backend/src/main/resources/db/tenant/${f}`);
test('importação segura preservada: nada de mover CSV de clientes para banco central',()=> {
 assert.match(sql('V4__secure_customer_import.sql'),/tenant_customer_import_batches/);
 assert.match(java('service/SecureCustomerImportService.java'),/AES\/GCM|AES\/GCM\/NoPadding/);
 assert.match(java('controller/SecureCustomerImportController.java'),/RequestMapping|PostMapping/);
});
test('histórico FIPE gravado só após verificar preço atual e aceite',()=>{
 const service=java('service/InspectionFipeSelectionService.java');
 assert.match(service,/catalog\.verifiedPrice\(priceId\)/);
 assert.match(service,/expected\.compareTo\(requestedValue\)/);
 assert.match(service,/void stage\(/);
 assert.match(service,/boolean resolve\(/);
 assert.match(java('service/AdminActivityService.java'),/fipeSelectionService\.verify/);
 assert.match(java('service/InspectionContractChangeService.java'),/fipeSelectionService\.resolve/);
});
test('endpoints FIPE só para usuários autenticados e sincronização opt-in por tenant',()=>{
 const security=java('config/SecurityConfig.java');
 assert.match(security,/"\/api\/analysis\/\*\*"\)\.hasAnyRole\("ANALYST", "ADMIN"\)/);
 assert.match(security,/"\/api\/admin\/\*\*"\)\.hasRole\("ADMIN"\)/);
 assert.match(read('backend/src/main/resources/application-tenant.yml'),/FIPE_DATASET_SYNC_ENABLED:false/);
});
test('apenas V5/V6 FIPE foram adicionadas após a importação segura, nenhuma carga NH',()=>{
 const catalog=sql('V5__fipe_dataset_snapshots.sql');
 const selection=sql('V6__inspection_fipe_version_selection.sql');
 assert.match(catalog,/fipe_dataset_prices/);
 assert.match(selection,/inspection_pending_fipe_selection/);
 for(const content of [catalog,selection]) {
  assert.doesNotMatch(content,/\bINSERT\s+INTO\b/i);
  assert.doesNotMatch(content,/Novo Horizonte/i);
 }
});
test('interface inclui seletor sem adivinhar FIPE e campos auditáveis',()=>{
 const html=read('web/analise/index.html'), ui=read('web/analise/analise.js');
 for(const id of ['edit-fipe-version','edit-fipe-code','edit-fipe-display','analysis-fipe-version-status']) assert.ok(html.includes(`id="${id}"`));
 assert.match(ui,/fipeVersionChoices\.length === 1/);
 assert.match(ui,/fipeRecordId: selectedFipeRecordId/);
 assert.match(java('service/RetratoPdfService.java'),/Código FIPE/);
});
