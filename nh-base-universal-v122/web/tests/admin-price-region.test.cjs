const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'admin/index.html'), 'utf8');
const js = fs.readFileSync(path.join(root, 'admin/admin.js'), 'utf8');
const dto = fs.readFileSync(path.resolve(root, '../backend/src/main/java/br/com/nh/cotacao/dto/AdminDtos.java'), 'utf8');

test('formulário FIPE exige a região de precificação aceita pela API', () => {
  assert.match(html, /id="price-region"\s+required/);
  assert.match(html, /value="NORTHEAST"/);
  assert.match(html, /value="OUTSIDE_NORTHEAST"/);
  assert.match(dto, /record CreatePriceRangeRequest\([\s\S]*?@NotNull PricingRegion pricingRegion/);
  assert.match(dto, /record UpdatePriceRangeRequest\([\s\S]*?@NotNull PricingRegion pricingRegion/);
});

test('criação e edição da faixa enviam região e validam intervalo FIPE', () => {
  assert.match(js, /\$\('price-region'\)\.value = item\?\.pricingRegion \|\| 'NORTHEAST'/);
  assert.match(js, /const pricingRegion = \$\('price-region'\)\.value/);
  assert.match(js, /base = \{\s*pricingRegion,/);
  assert.match(js, /base\.maxValue < base\.minValue/);
  assert.match(js, /const payload = id \? base : \{ \.\.\.base, planId \}/);
  assert.match(js, /pricingRegionLabel\(item\.pricingRegion\)/);
});
