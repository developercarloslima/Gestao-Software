const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const read = p => fs.readFileSync(path.join(root, p), 'utf8');

test('v99 adiciona prazo de renovacao sem reescrever o registro de criação', () => {
  const sql = read('backend/src/main/resources/db/tenant/V9__acceptance_link_lifetimes.sql');
  assert.match(sql, /ADD COLUMN IF NOT EXISTS link_available_at/);
  assert.doesNotMatch(sql, /DROP TABLE|UPDATE inspection_responsibility_terms|DELETE FROM/i);
  for(const f of ['InspectionResponsibilityTermService','InspectionPartsDelayTermService']) {
    const svc = read(`backend/src/main/java/br/com/nh/cotacao/service/${f}.java`);
    assert.match(svc, /coalesce\(link_available_at,created_at\) as link_available_at/);
    assert.match(svc, /offset\(rs, "link_available_at"\)/);
  }
});
test('renovação somente pela Supervisão, mantém aceites confirmados', () => {
  const service = read('backend/src/main/java/br/com/nh/cotacao/service/InspectionAcceptanceWorkflowService.java');
  assert.match(service, /role != PortalRole.ADMIN && role != PortalRole.SUPERVISION_ANALYSIS/);
  assert.match(service, /!before.expired\(\)/);
  assert.match(service, /accepted_at is null/);
  assert.match(service, /before.allAccepted\(\)/);
  assert.match(service, /public_token=\?,link_available_at=\?/);
  const entity = read('backend/src/main/java/br/com/nh/cotacao/entity/InspectionRequest.java');
  assert.match(entity, /renewPendingAcceptance/);
  assert.match(entity, /invalidatePendingDigitalAcceptance\(\)/);
});
test('panéis separam pendentes e realizados quando os tres aceites concluidos', () => {
  for (const panel of ['analise','supervisao']) {
    const js = read(`web/${panel}/${panel}.js`);
    const html = read(`web/${panel}/index.html`);
    assert.match(html, /data-analysis-queue="acceptance"/);
    assert.match(html, /data-analysis-queue="accepted"/);
    assert.match(js, /allThreeAcceptancesCompleted/);
    assert.match(js, /acceptanceCompletedCount/);
  }
});
test('tenant não vincula automaticamente novos consultores a usuária de outro cliente', () => {
  const service = read('backend/src/main/java/br/com/nh/cotacao/service/ConsultantService.java');
  assert.doesNotMatch(service, /analiselarissa|LARISSA_USERNAME/i);
  assert.equal(fs.existsSync(path.join(root, 'scripts/VINCULAR-CONSULTORES-ANALISELARISSA.sql')), false);
});
