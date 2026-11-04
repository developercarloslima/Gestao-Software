const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const root=path.resolve(__dirname,'..');
const read=p=>fs.readFileSync(path.join(root,p),'utf8');
const backend=p=>fs.readFileSync(path.resolve(root,'../backend/src/main/java/br/com/nh/cotacao',p),'utf8');

test('ADM exibe Gerar boleto para mensalidades de associados não emitidas',()=>{
  const web=read('shared/receivables.js');
  assert.match(web,/data-issue="[^"]*/);
  assert.match(web,/Gerar boleto/);
  assert.match(web,/window\.open\('about:blank','_blank'\)/);
  assert.match(web,/\/issue-boleto/);
  assert.match(backend('controller/ReceivablesController.java'),/issueBoleto\(/);
  assert.match(backend('service/ReceivablesService.java'),/finance\.issueAssociateBoleto\(/);
});

test('ADM exibe Gerar boleto em faturas SaaS registradas sem link',()=>{
  const web=read('shared/company-subscription.js');
  assert.match(web,/data-issue-invoice/);
  assert.match(web,/Gerar boleto/);
  assert.match(web,/window\.open\('about:blank','_blank'\)/);
  assert.match(web,/\/issue-boleto/);
  assert.match(backend('controller/CompanySubscriptionController.java'),/issueCompanyInvoiceBoleto/);
});

test('interfaces mantêm faturas pagas e previsões sem emissão automática',()=>{
  const web=read('shared/company-subscription.js');
  assert.match(web,/const scheduled = state === 'SCHEDULED'/);
  assert.match(web,/const paid = \['PAID', 'PAID_IN_CASH', 'CONFIRMED'\]/);
  const finance=backend('service/CommercialFinanceService.java');
  assert.match(finance,/!autoIssueBatches \|\| !asaas\.enabled\(\)/);
});
