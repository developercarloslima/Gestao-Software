const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname,'../..');
const read = (file)=>fs.readFileSync(path.join(root,file),'utf8');
test('FIPE é serviço interno no Compose e não publica porta externa',()=>{
  const yml = read('deployment/compose.tenant.yaml');
  assert.match(yml,/fipe-api:\s*\n\s*profiles: \[fipe\]/);
  assert.match(yml,/fipe-updater:/);
  assert.match(yml,/FIPE_API_BASE_URL:\s*http:\/\/fipe-api:8000/);
  assert.match(yml,/tenant_fipe_data:/);
});
test('Tela Envios não contém o card de sincronização legado',()=>{
  const html=read('web/admin/index.html');
  assert.doesNotMatch(html,/fipe-sync-card\.js/);
  assert.doesNotMatch(html,/fipe-dataset-card/);
  assert.equal(fs.existsSync(path.join(root,'web/admin/fipe-sync-card.js')),false);
});
test('Java usa API independente e preserva valores selecionados',()=>{
  const svc=read('backend/src/main/java/br/com/nh/cotacao/service/FipeDatasetCatalogService.java');
  assert.match(svc,/\/buscar-modelos/);
  assert.match(svc,/\/metadados/);
  assert.match(svc,/cacheSelection/);
  assert.match(svc,/canonicalVehicleType/);
  assert.equal(fs.existsSync(path.join(root,'backend/src/main/java/br/com/nh/cotacao/service/FipeDatasetSyncService.java')),false);
  const pom=read('backend/pom.xml');
  assert.doesNotMatch(pom,/<artifactId>hadoop-common<\/artifactId>/);
  assert.doesNotMatch(pom,/<artifactId>parquet-hadoop<\/artifactId>/);
});
test('Gestão de Apontamento separa abas férias e feriados',()=>{
  const ui=read('nh-ponto/apps/web/src/components/Management.tsx');
  assert.match(ui,/\['ferias','Férias'\]/);
  assert.match(ui,/\['feriados','Feriados'\]/);
  assert.match(ui,/<TimeOff mode="vacations"\/>/);
});
test('Regra de férias é revalidada e bloqueia marcações e ajustes no servidor',()=>{
  const routes=read('nh-ponto/apps/api/src/routes/time-off.ts');
  assert.match(routes,/validateVacationPeriod/);
  assert.match(routes,/pg_advisory_xact_lock/);
  assert.match(read('nh-ponto/apps/api/src/services/punch.service.ts'),/VACATION_LOCK/);
  assert.match(read('nh-ponto/apps/api/src/routes/adjustments.ts'),/VACATION_LOCK/);
});
