const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');

test('cotação mantém CPF opcional e remove seleção manual de associado', () => {
  const page = read('cota/index.html');
  const script = read('cota/app.js');
  assert.match(page, /id="customerCpf"[^>]+placeholder="CPF \(opcional\)"/);
  assert.doesNotMatch(page, /Associado existente \(opcional\)|id="associateLookup"/);
  assert.doesNotMatch(script, /payload\.associateId/);
  assert.match(script, /checkAssociateCpfIdentity\(\)/);
});

test('Retrato e vistoria exigem CPF inclusive quando vêm de cotação aceita', () => {
  const page = read('colaborador/retrato.html');
  const script = read('colaborador/retrato-create.js');
  assert.match(page, /id="cpf" required/);
  assert.match(script, /\$\('cpf'\)\.required = true/);
  assert.doesNotMatch(script, /\$\('cpf'\)\.required = false/);
  assert.match(script, /if \(!typedCpf\)/);
  assert.match(script, /checkAssociateCpfIdentity\(\)/);
});

test('ambos os formulários exibem o aviso de compatibilidade CPF e nome', () => {
  for (const path of ['cota/index.html', 'colaborador/retrato.html']) {
    assert.match(read(path), /id="cpf-associate-feedback"/);
  }
  assert.match(read('cota/app.js'), /NAME_CONFLICT/);
  assert.match(read('colaborador/retrato-create.js'), /NAME_CONFLICT/);
});


test('migração de vínculo de associado existe no banco padrão e no banco tenant', () => {
  const migration = fs.readFileSync(path.resolve(root,
    '../backend/src/main/resources/db/tenant/V16__inspection_associate_cpf_link.sql'), 'utf8');
  const legacy = fs.readFileSync(path.resolve(root,
    '../backend/src/main/resources/db/migration/V94__inspection_associate_cpf_link.sql'), 'utf8');
  for (const script of [migration, legacy]) {
    assert.match(script, /ALTER TABLE inspection_requests/);
    assert.match(script, /ADD COLUMN IF NOT EXISTS associate_id UUID/);
    assert.match(script, /REFERENCES associates\(id\)/);
  }
});
