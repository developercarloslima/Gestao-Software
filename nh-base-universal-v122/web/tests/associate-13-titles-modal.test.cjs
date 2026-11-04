const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const web = path.resolve(__dirname, '..');
const backend = path.resolve(web, '../backend/src/main/java/br/com/nh/cotacao');
const readWeb = file => fs.readFileSync(path.join(web, file), 'utf8');
const readJava = file => fs.readFileSync(path.join(backend, file), 'utf8');

test('o CPF do associado fica fora da lista de funcionários do ADM', () => {
  const service = readJava('service/PortalUserService.java');
  assert.match(service, /public List<PortalUserResponse> list\(\)[\s\S]*?\.filter\(user -> user\.getRole\(\) != PortalRole\.ASSOCIATE\)/);
  assert.match(service, /activateAssociateAccountAfterAcceptance/);
});

test('a adesão é cadastrada localmente sem depender de Asaas ativo e o lote contém 12 mensalidades', () => {
  const finance = readJava('service/CommercialFinanceService.java');
  assert.match(finance, /FinancialCharge adhesion = FinancialCharge\.create\(v, FinancialChargeType\.ADHESION/);
  assert.match(finance, /charges\.save\(adhesion\)/);
  assert.match(finance, /for \(int i=0; i<12; i\+\+\)/);
  assert.match(finance, /existingAdhesion\.getProviderPaymentId\(\) == null/);
});

test('clicar na placa abre modal de cobranças com adesão e 12 parcelas', () => {
  const html = readWeb('associado/index.html');
  const script = readWeb('associado/app.js');
  assert.match(html, /<dialog id="vehicle-modal"/);
  assert.match(html, /Adesão e 12 mensalidades/);
  assert.match(html, /id="billing-summary"/);
  assert.match(script, /async function openVehicle\(id\)/);
  assert.match(script, /\$\('vehicle-modal'\)\.showModal\(\)/);
  assert.match(script, /\$\('vehicle-modal'\)\.close\(\)/);
  assert.doesNotMatch(html, /data-billing="BOLETO"/);
});

test('cada boleto emitido tem atalho individual de abertura para impressão', () => {
  const js = readWeb('associado/app.js');
  const fragment = js.slice(js.indexOf('function paymentAction(c){'), js.indexOf('function showPayment(c){'));
  const action = vm.runInNewContext(fragment + '; paymentAction;');
  const boleto = action({status:'PENDING',billingType:'BOLETO',bankSlipUrl:'https://example.org/boleto.pdf'});
  assert.match(boleto, /Abrir \/ imprimir boleto/);
  assert.match(boleto, /example\.org\/boleto\.pdf/);
  assert.match(boleto, /target="_blank"/);
  const aguardando = action({status:'PENDING',billingType:'BOLETO',id:'abc'});
  assert.match(aguardando, /Atualizar/);
  assert.doesNotMatch(aguardando, /href=/);
});
