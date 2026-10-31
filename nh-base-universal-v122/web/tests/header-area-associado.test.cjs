const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const web = path.resolve(__dirname, '..');
const index = fs.readFileSync(path.join(web, 'index.html'), 'utf8');
const headerCss = fs.readFileSync(path.join(web, 'shared/nh-home-fixes.css'), 'utf8');

test('o site público exibe a Área do Associado com a rota correta', () => {
  assert.match(index, /class="header-associate" href="\/associado\/"/);
  assert.match(index, /class="associate-menu-link" href="\/associado\/"/);
  assert.equal((index.match(/Área do Associado/g) || []).length, 2);
  assert.match(index, /nh-home-fixes\.css\?v=20261031-associado-v5/);
});

test('o cabeçalho desktop mantém o botão do associado visível', () => {
  const desktop = headerCss.slice(headerCss.indexOf('/* v5 —'));
  assert.ok(desktop.startsWith('/* v5 —'), 'regra atual de desktop presente');
  assert.match(desktop, /@media \(min-width:1100px\)/);
  const rule = desktop.match(/\.site-header \.nav-wrapper > \.header-associate\s*\{([^}]+)\}/);
  assert.ok(rule, 'regra específica do botão presente');
  assert.match(rule[1], /display:inline-flex !important/);
  assert.match(rule[1], /visibility:visible !important/);
  assert.match(rule[1], /order:6 !important/);
  assert.doesNotMatch(rule[1], /display:none/);
});

test('a navegação compacta continua com o acesso ao associado', () => {
  assert.match(headerCss, /\.site-header \.nav-wrapper > \.main-menu\.open \.associate-menu-link/);
  assert.match(index, /class="menu-toggle"/);
});
