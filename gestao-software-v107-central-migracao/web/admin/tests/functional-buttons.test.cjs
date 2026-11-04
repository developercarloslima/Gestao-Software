'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const markup = read('index.html');
const app = read('app.js');
const onboarding = read('onboarding.js');
const sources = [app, onboarding, read('cloud.js'), read('migracao.js')].join('\n');

test('botões estáticos do Control Plane possuem controle de interação', () => {
  const tags = [...markup.matchAll(/<button\b[^>]*>/g)].map(match => match[0]);
  assert.ok(tags.length > 40, 'Inventário de botões foi reduzido inesperadamente');
  for (const tag of tags) {
    const id = tag.match(/\bid="([^"]+)"/)?.[1];
    if (!id) {
      // Botões sem ID usam formulário, guias, delegação ou tema global.
      assert.match(tag, /type="submit"|data-tab(?:-target)?=|data-close-dialog=|data-gestao-theme-toggle/);
      continue;
    }
    if (tag.includes('type="submit"')) continue;
    assert.ok(sources.includes("'" + id + "'") || sources.includes('"' + id + '"'),
      'Botão sem referência no código: #' + id);
  }
});

test('geração de mensalidade tem formulário, feedback visível e fluxo para cobrança', () => {
  for (const id of ['company-detail-message','invoice-dialog','invoice-form','invoice-month',
    'invoice-amount','invoice-due-date','invoice-form-message','invoice-save']) {
    assert.ok(markup.includes('id="' + id + '"'), 'Elemento ausente: ' + id);
  }
  assert.match(app, /\$\('invoice-form'\)\.addEventListener\('submit'/);
  assert.match(app, /gestao:invoice-updated/);
  assert.match(app, /gestao:invoice-selected/);
  assert.match(onboarding, /gestao:invoice-selected/);
  assert.match(onboarding, /gestao:invoice-updated/);
  assert.match(app, /formMessage\('invoice-form-message',error\.message\)/);
});

test('vencimento usa fim do mês em vez de criar datas impossíveis', () => {
  const start = app.indexOf('function defaultInvoiceDueDate(');
  const end = app.indexOf('async function openInvoiceDialog(', start);
  assert.ok(start >= 0 && end > start);
  const fn = vm.runInNewContext(app.slice(start, end) + '\ndefaultInvoiceDueDate', { Date });
  assert.equal(fn('2026-11', 31), '2026-11-30');
  assert.equal(fn('2027-02', 31), '2027-02-28');
  assert.equal(fn('2028-02', 31), '2028-02-29');
  assert.equal(fn('2026-12', 15), '2026-12-15');
});

test('ações do modal possuem retorno de sucesso ou erro visível', () => {
  assert.match(app, /const signContract=.*?company-detail-message/s);
  assert.match(app, /const copyContract=.*?company-detail-message/s);
  assert.match(app, /const gen=.*?openInvoiceDialog/s);
});

test('subconta Asaas expõe o motivo sem permitir reenvio em resultado incerto', async () => {
  const start = app.indexOf('async function loadAsaasSubaccountStatus(');
  const end = app.indexOf('function openAsaasSubaccountDialog(', start);
  assert.ok(start >= 0 && end > start);
  let response = {state:'REJECTED',lastHttpStatus:400,lastErrorCode:'invalid_cpfCnpj',
    lastErrorMessage:'Documento inválido <script>alert(1)</script>',attemptCount:2,parentConfigured:true};
  const box = {isConnected:true,innerHTML:'',textContent:''};
  const context = {
    $: () => box, api: async () => response,
    currentCompany:{id:'company-1'},
    canManageCompanyLifecycle:() => true,
    esc:value => String(value??'').replace(/[&<>"']/g, char =>
      ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'}[char])),
  };
  const render = vm.runInNewContext(app.slice(start,end)+'\nloadAsaasSubaccountStatus',context);
  await render('company-1');
  assert.match(box.innerHTML,/Documento inválido &lt;script&gt;/);
  assert.match(box.innerHTML,/Corrigir dados e tentar novamente/);
  response={...response,state:'REVIEW_REQUIRED'};
  await render('company-1');
  assert.doesNotMatch(box.innerHTML,/data-asaas-retry="true"/);
  assert.match(box.innerHTML,/evitar duplicidade/);
});
