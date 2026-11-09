const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'admin/index.html'), 'utf8');
const js = fs.readFileSync(path.join(root, 'admin/admin.js'), 'utf8');
const css = fs.readFileSync(path.join(root, 'admin/associates.css'), 'utf8');

test('aba exclusiva BI de vendas removida sem eliminar BI de Equipes / Cooperativas', () => {
  assert.doesNotMatch(html, /data-tab="sales-bi"|id="tab-sales-bi"/);
  assert.match(html, /data-tab="sales-teams"/);
  assert.match(html, /id="teams-metrics"/);
  assert.match(html, /Vendas pagas por cooperativa/);
  assert.doesNotMatch(html, /continuam na aba BI de vendas/);
});

test('quatro indicadores de associados usam grade horizontal responsiva', () => {
  assert.match(html, /class="associates-summary-grid"/);
  for (const id of ['associates-total', 'associates-active', 'associates-pending', 'associates-overdue']) {
    assert.match(html, new RegExp(`id="${id}"`));
  }
  assert.match(css, /grid-template-columns:\s*repeat\(4,\s*minmax\(0,\s*1fr\)\)/);
  assert.match(css, /@media \(max-width: 1040px\)/);
  assert.match(css, /@media \(max-width: 700px\)/);
});

test('ver detalhes abre modal acessível com dados e lista de veículos', () => {
  assert.match(html, /<dialog id="associates-details"[^>]*aria-labelledby="associates-details-name"/);
  assert.match(html, /id="associates-details-close"/);
  for (const id of ['associates-details-cpf', 'associates-details-whatsapp',
    'associates-details-consultants', 'associates-details-plates',
    'associates-details-active', 'associates-details-monthly', 'associates-details-body']) {
    assert.match(html, new RegExp(`id="${id}"`));
  }
  assert.match(js, /\$\('associates-details'\)\.showModal\(\)/);
  assert.match(js, /\$\('associates-details-close'\).*\.close\(\)/);
  assert.match(js, /financial\?\.businessStatusLabel/);
  assert.match(js, /data-associate-inspection-plate/);
  assert.doesNotMatch(js, /\$\('associates-details'\)\.scrollIntoView/);
});
