const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const html = fs.readFileSync(path.resolve(__dirname, '../colaborador/index.html'), 'utf8');

test('salvar chave Pix e solicitar saque têm a mesma altura fixa', () => {
  assert.match(html, /id="finance-save-pix"/);
  assert.match(html, /id="finance-withdraw"/);
  assert.match(html, /#finance-save-pix,\s*#finance-withdraw\s*\{[^}]*align-self:\s*end;[^}]*height:\s*46px;[^}]*min-height:\s*46px;[^}]*max-height:\s*46px;/);
});
