const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const script=fs.readFileSync(path.join(__dirname,'..','app.js'),'utf8');
test('exclusão de boleto emitido não pago exige recurso da API e autorização de sócio',()=>{
  assert.match(script,/canManageCompanyLifecycle\(\)\&\&c\.invoiceDeletionAvailable===true/);
  assert.match(script,/!i\.paymentVerified/);
  assert.match(script,/\['PENDING','OVERDUE'\]\.includes\(i\.status\)/);
  assert.match(script,/data-delete-invoice/);
  assert.match(script,/method:'DELETE'/);
  assert.match(script,/confirmCompanyAction\(\{title:'Excluir mensalidade'/);
  assert.match(script,/response\.status===405/);
});
