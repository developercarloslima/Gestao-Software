const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const read = p => fs.readFileSync(path.join(root, p), 'utf8');

test('o analista acompanha os tres aceites, sem nova aprovação antes de 7 dias', () => {
  const svc = read('backend/src/main/java/br/com/nh/cotacao/service/AdminActivityService.java');
  const analyst = read('web/analise/analise.js');
  assert.match(svc, /stage == InspectionAnalysisStage\.FINISHED[\s\S]*item\.getStatus\(\) == InspectionRequestStatus\.APPROVED/);
  assert.match(svc, /markDecisionMessageSentForSupervision[\s\S]*item\.getStatus\(\) == InspectionRequestStatus\.APPROVED/);
  assert.match(analyst, /Aguardando aceite/);
  assert.match(analyst, /allThreeAcceptancesCompleted/);
  assert.match(analyst, /Aceites realizados/);
  assert.match(analyst, /\/api\/analysis\/inspections\/\$\{encodeURIComponent\(item\.id\)\}\/decision-message-sent/);
});

test('a Supervisão pode redistribuir somente para analista ativo e sem aceite final', () => {
  const svc = read('backend/src/main/java/br/com/nh/cotacao/service/AdminActivityService.java');
  const controller = read('backend/src/main/java/br/com/nh/cotacao/controller/SupervisionAnalysisController.java');
  const ui = read('web/supervisao/supervisao.js');
  assert.match(svc, /reassignInspectionAnalyst\([\s\S]*?assertSupervisionInspectionAccess\([\s\S]*?assertDigitalAcceptanceNotFinalized/);
  assert.match(svc, /consultantService\.findActiveAnalyst\(newAnalystId\)/);
  assert.match(svc, /INSPECTION_ANALYST_ASSIGNMENT/);
  assert.match(controller, /@PatchMapping\("\/\{id\}\/analyst"\)/);
  assert.match(ui, /\/api\/supervision\/inspections\/\$\{id\}\/analyst/);
});

test('termo de peças é separado do termo de avarias e do dossiê', () => {
  const migration = read('backend/src/main/resources/db/tenant/V8__separate_terms_and_webauthn_recovery.sql');
  const service = read('backend/src/main/java/br/com/nh/cotacao/service/InspectionPartsDelayTermService.java');
  const pdf = read('backend/src/main/java/br/com/nh/cotacao/service/PartsDelayTermPdfService.java');
  assert.match(migration, /CREATE TABLE IF NOT EXISTS inspection_parts_delay_terms/);
  assert.match(migration, /CREATE TABLE IF NOT EXISTS parts_delay_term_acceptance_attempts/);
  assert.match(service, /\/ciencia-pecas\/\?token=/);
  assert.match(service, /parts-delay-science-term-user:/);
  assert.doesNotMatch(service, /refreshFinalDossierWithAcceptedTerm/);
  assert.match(pdf, /TENANT_PARTS_DELAY_CLAUSES|legalClausesSnapshot/);
  assert.doesNotMatch(pdf, /38\.078\.339\/0001-83/);
  const snapshot=read('backend/src/main/resources/db/tenant/V11__immutable_parts_term_legal_snapshot.sql');
  assert.match(snapshot, /legal_clauses_snapshot/);
  assert.match(pdf, /independente do Termo de Responsabilidade/);
});

test('tela pública tem aceite exclusivo via WebAuthn', () => {
  const script = read('web/ciencia-pecas/ciencia-pecas.js');
  const page = read('web/ciencia-pecas/index.html');
  const nginx = read('web/nginx.conf');
  assert.match(script, /navigator\.credentials\.create/);
  assert.match(script, /navigator\.credentials\.get/);
  assert.match(script, /\/api\/public\/parts-delay-terms/);
  assert.match(page, /aceite.*WebAuthn/i);
  assert.match(nginx, /location \/ciencia-pecas\//);
});
