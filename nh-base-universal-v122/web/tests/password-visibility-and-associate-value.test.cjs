const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const repo = path.resolve(__dirname, '../../..');
const read = (relativePath) => fs.readFileSync(path.join(repo, relativePath), 'utf8');

function htmlFiles(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const absolute = path.join(directory, entry.name);
    return entry.isDirectory() ? htmlFiles(absolute) : entry.name.endsWith('.html') ? [absolute] : [];
  });
}

test('formulários HTML com senha carregam o botão mostrar/ocultar inclusive em modais', () => {
  const sites = ['gestao-software-v107-central-migracao/web', 'nh-base-universal-v122/web'];
  let count = 0;
  for (const site of sites) {
    for (const file of htmlFiles(path.join(repo, site))) {
      const html = fs.readFileSync(file, 'utf8');
      if (!/type=["']password["']/i.test(html)) continue;
      count++;
      assert.match(html, /\/shared\/password-visibility\.js/, file);
    }
    const behavior = read(`${site}/shared/password-visibility.js`);
    assert.match(behavior, /aria-pressed/);
    assert.match(behavior, /MutationObserver/);
    assert.match(behavior, /button\.type = 'button'/);
  }
  assert.ok(count >= 16, 'esperado cobrir os formulários do Gestão e NH');
});

test('todas as telas de senha do Ponto compartilham componente React acessível', () => {
  const src = 'nh-base-universal-v122/nh-ponto/apps/web/src/components/';
  const component = read(`${src}PasswordField.tsx`);
  assert.match(component, /aria-label=\{visible \? 'Ocultar senha' : 'Mostrar senha'\}/);
  assert.match(component, /type=\{visible \? 'text' : 'password'\}/);
  assert.match(component, /type="button"/);
  for (const file of ['Login.tsx', 'FirstAccessOnboarding.tsx', 'SuperAdminPanel.tsx', 'management/Employees.tsx', 'management/Supervisors.tsx']) {
    const content = read(`${src}${file}`);
    assert.match(content, /import PasswordField from/);
    assert.match(content, /<PasswordField/);
    assert.doesNotMatch(content, /<input[^\n]*type="password"/, file);
  }
});

test('área do associado mostra somente o valor final, sem colunas separadas de taxa e total', () => {
  const html = read('nh-base-universal-v122/web/associado/index.html');
  const script = read('nh-base-universal-v122/web/associado/app.js');
  assert.match(html, /<th>Tipo<\/th><th>Vencimento<\/th><th>Valor<\/th><th>Status<\/th><th>Pagamento<\/th>/);
  assert.doesNotMatch(html, /<th>Taxa<\/th>|<th>Total<\/th>/);
  const charges = script.match(/\$\('charges'\)\.innerHTML=list\.map\(c=>.+/);
  assert.ok(charges, 'renderização das cobranças deve existir');
  assert.match(charges[0], /money\(c\.totalCharged\)/);
  assert.doesNotMatch(charges[0], /money\(c\.(value|providerFee)\)/);
  assert.match(charges[0], /colspan="5"/);
});

test('painel de sincronização FIPE também oferece mostrar/ocultar a chave', () => {
  const api = read('nh-base-universal-v122/api-fipe/app/main.py');
  assert.match(api, /id="mostrar-chave"/);
  assert.match(api, /campo\.type=mostrar\?'text':'password'/);
});
