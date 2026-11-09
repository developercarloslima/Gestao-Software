const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const file = name => fs.readFileSync(path.join(root, name), 'utf8');
const finance = file('backend/src/main/java/br/com/nh/cotacao/service/CommercialFinanceService.java');
const guide = file('backend/src/main/java/br/com/nh/cotacao/service/ConsultantCommissionGuideService.java');
const bi = file('backend/src/main/java/br/com/nh/cotacao/service/CommercialSalesBiService.java');
const activation = file('backend/src/main/java/br/com/nh/cotacao/service/AssociatePortalAccessActivationListener.java');
const admin = file('web/admin/admin.js');
const html = file('web/admin/index.html');

test('aceite não ativa o veículo sem uma adesão realmente recebida', () => {
  assert.match(finance, /v\.awaitAdhesionPayment\(\)/);
  assert.match(finance, /boolean accepted = vehicles\.hasAcceptedContract/);
  assert.match(finance, /boolean received = isAdhesionSettled\(vehicle\)/);
  assert.match(finance, /if \(accepted && received\)/);
  assert.match(finance, /FinancialChargeStatus\.RECEIVED/);
  assert.match(activation, /reconcileAcceptedInspection/);
});

test('dinheiro em mãos só é contabilizado mediante confirmação explícita', () => {
  assert.match(finance, /public ChargeResponse confirmCashAdhesion/);
  assert.match(finance, /charge\.markReceived\(OffsetDateTime\.now\(\)\)/);
  assert.match(file('backend/src/main/java/br/com/nh/cotacao/controller/CommercialFinanceController.java'), /PortalRole\.BUYER/);
});

test('BI distingue boletos emitidos de vendas de adesão por boleto e Pix', () => {
  assert.match(bi, /issued_at>=\?/);
  assert.match(bi, /provider_payment_id IS NOT NULL/);
  assert.match(bi, /charge_type='ADHESION' AND status='RECEIVED'/);
  assert.match(bi, /payment_method='BOLETO'/);
  assert.match(bi, /payment_method='PIX'/);
  assert.match(guide, /charge_type='ADHESION' and status='RECEIVED'/);
  assert.doesNotMatch(html, /data-tab="sales-bi"/);
  assert.doesNotMatch(html, /id="tab-sales-bi"/);
  assert.match(html, /data-tab="sales-teams"/);
  assert.match(file('web/admin/sales-teams.js'), /\/api\/admin\/sales-teams/);
});

test('aba Associados identifica placas e usa situação financeira apurada no servidor', () => {
  assert.match(html, /data-tab="associates"/);
  assert.match(admin, /\/api\/admin\/commercial\/associates/);
  assert.match(admin, /financial\?\.businessStatusLabel/);
  assert.match(file('backend/src/main/java/br/com/nh/cotacao/controller/AdminCommercialFinanceController.java'), /@GetMapping\("\/associates"\)/);
});

test('migrations global e tenant suportam pendência de adesão e dados de emissão', () => {
  for (const sql of [
    file('backend/src/main/resources/db/migration/V97__vehicle_pending_adhesion_payment.sql'),
    file('backend/src/main/resources/db/tenant/V20__vehicle_pending_adhesion_payment.sql')
  ]) {
    assert.match(sql, /'PENDING_PAYMENT'/);
    assert.match(sql, /ADD COLUMN IF NOT EXISTS payment_method/);
    assert.match(sql, /ADD COLUMN IF NOT EXISTS issued_at/);
    assert.doesNotMatch(sql, /UPDATE\s+commercial_vehicles\s+SET\s+status/i);
  }
});
