const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const base = path.resolve(__dirname, '../..');
const read = name => fs.readFileSync(path.join(base, name), 'utf8');
const service = read('backend/src/main/java/br/com/nh/cotacao/service/SalesTeamAnalyticsService.java');
const memberships = read('backend/src/main/java/br/com/nh/cotacao/service/SalesTeamService.java');
const financeBi = read('backend/src/main/java/br/com/nh/cotacao/service/CommercialSalesBiService.java');
const html = read('web/admin/index.html');
const js = read('web/admin/sales-teams.js');

test('vendas por cooperativa exigem tanto aceite final como adesão recebida', () => {
  assert.match(service, /f\.charge_type='ADHESION' AND f\.status='RECEIVED'/);
  assert.match(service, /i\.status='APPROVED'/);
  assert.match(service, /GREATEST\(i\.accepted_at,p\.paid_at\)/);
  assert.match(service, /s\.paymentMethod\(\)/);
  assert.match(financeBi, /GREATEST\(paid_at,accepted_at\)/);
  assert.match(financeBi, /accepted_at IS NOT NULL/);
});

test('o painel mostra venda, boleto, Pix, aceite, cadastro e pagamento pendente separadamente', () => {
  for (const label of ['Vendas por boleto','Vendas por Pix','Adesão pendente','Pendentes de aceite','Em cadastro','Vendas efetivadas']) {
    assert.ok(js.includes(label), `Painel sem ${label}`);
  }
  assert.match(html, /data-tab="sales-teams"/);
  assert.match(html, /\/admin\/sales-teams\.js/);
  assert.match(js, /\/api\/admin\/sales-teams\/analytics/);
  assert.match(js, /\/analytics\/export\//);
});

test('equipes preservam histórico e não separam artificialmente a empresa em tenants', () => {
  const migration = read('backend/src/main/resources/db/migration/V98__sales_teams_and_membership_history.sql');
  const tenant = read('backend/src/main/resources/db/tenant/V21__sales_teams_and_membership_history.sql');
  for (const sql of [migration,tenant]) {
    assert.match(sql, /sales_team_memberships/);
    assert.match(sql, /joined_at/);
    assert.match(sql, /left_at/);
    assert.match(sql, /uq_sales_teams_active_consultant/);
    assert.doesNotMatch(sql, /CREATE TABLE tenants/i);
  }
  assert.match(memberships, /deleted_at=now\(\)/);
  assert.match(memberships, /closeMembership/);
  assert.match(memberships, /replaceMembers/);
});

test('veículos ativos herdados só permanecem ativos quando aceite e adesão estão confirmados', () => {
  for (const file of [
    'backend/src/main/resources/db/migration/V99__reconcile_legacy_active_vehicle_payment_gate.sql',
    'backend/src/main/resources/db/tenant/V22__reconcile_legacy_active_vehicle_payment_gate.sql'
  ]) {
    const sql = read(file);
    assert.match(sql, /status='PENDING_PAYMENT'/);
    assert.match(sql, /NOT EXISTS \(\s*SELECT 1 FROM financial_charges/);
    assert.match(sql, /NOT EXISTS \(\s*SELECT 1 FROM inspection_requests/);
    assert.doesNotMatch(sql, /DELETE FROM/);
  }
});
