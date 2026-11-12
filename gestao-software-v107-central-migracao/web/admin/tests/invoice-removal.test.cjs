const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const script = fs.readFileSync(path.join(root, 'app.js'), 'utf8');
test('exclusão fica restrita aos sócios e a faturas não pagas nem vinculadas', () => {
  assert.match(script, /canManageCompanyLifecycle\(\).*?!i\.providerLinked/);
  assert.match(script, /\['PENDING','OVERDUE'\]\.includes\(i\.status\)/);
  assert.match(script, /data-delete-invoice/);
  assert.match(script, /method:'DELETE'/);
  assert.match(script, /confirmCompanyAction\(\{title:'Excluir mensalidade'/);
});
