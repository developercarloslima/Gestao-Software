const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const base = path.resolve(__dirname, '..', '..');
const web = path.resolve(__dirname, '..');

test('legacy boleto endpoints cannot expose financial data by CPF alone', () => {
  for (const file of [
    path.join(web, 'nginx.conf'),
    path.join(web, 'nginx.render.conf.template'),
    path.join(web, 'nginx.render-public.conf.template'),
  ]) {
    const config = fs.readFileSync(file, 'utf8');
    for (const endpoint of ['consultar-boletos-associado', 'baixar-boleto']) {
      assert.match(config, new RegExp(`location = /api/${endpoint} \\{ return 410; \\}`));
    }
  }
  const server = fs.readFileSync(path.join(base, 'hinova-api', 'server.js'), 'utf8');
  assert.match(server, /app\.all\('\/api\/consultar-boletos-associado'.*status\(410\)/);
  assert.match(server, /app\.all\('\/api\/baixar-boleto'.*status\(410\)/);
  for (const file of ['consultar-boletos-associado.js', 'baixar-boleto.js']) {
    const endpoint = fs.readFileSync(path.join(web, 'api', file), 'utf8');
    assert.match(endpoint, /status\(410\)/);
    assert.doesNotMatch(endpoint, /proxyToHinova/);
  }
});
