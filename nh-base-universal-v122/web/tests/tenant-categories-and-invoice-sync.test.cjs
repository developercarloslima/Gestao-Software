const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const migration = fs.readFileSync(path.join(root, 'backend/src/main/resources/db/tenant/V17__standard_vehicle_categories.sql'), 'utf8');
const admin = fs.readFileSync(path.join(root, 'web/admin/admin.js'), 'utf8');
const cota = fs.readFileSync(path.join(root, 'web/cota/app.js'), 'utf8');
const monthly = fs.readFileSync(path.join(root, 'web/shared/company-subscription.js'), 'utf8');

test('catálogo estrutural do tenant cobre as categorias do ADM e da cotação', () => {
  for (const code of [
    'MOTORCYCLE_PROMO_2026', 'MOTORCYCLE_UP_TO_300', 'MOTORCYCLE_OVER_300',
    'SCOOTER_ELECTRIC', 'CAR_NATIONAL', 'CAR_IMPORTED', 'UTILITY', 'TRUCK'
  ]) {
    assert.match(migration, new RegExp("'"+code+"'"), code + ' ausente na migration');
    assert.match(admin, new RegExp("'"+code+"'"), code + ' ausente no ADM');
  }
  assert.match(migration, /ON CONFLICT\s*\(code\)\s*DO NOTHING/i);
  assert.doesNotMatch(migration, /INSERT\s+INTO\s+(?:plans|price_ranges|plan_coverages)/i);
  assert.match(cota, /api\/public\/quotes\/categories/);
});

test('mensalidades são conciliadas com Asaas ao abrir e ao atualizar a aba', () => {
  assert.match(monthly, /button\.addEventListener\('click',\s*\(\)\s*=>\s*load\(true\)\)/);
  assert.match(monthly, /if \(!tab \|\| !tab\.hidden\) load\(true\)/);
  assert.match(monthly, /if \(!loaded\) load\(true\)/);
  assert.match(monthly, /Sincronização parcial/);
  assert.match(monthly, /synchronize \? 'POST' : 'GET'/);
});
