const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const read = name => fs.readFileSync(path.join(root, name), 'utf8');

test('fila manual v179 nao modifica provas digitais e exige supervisor ou ADM', () => {
  const inspection = read('backend/src/main/java/br/com/nh/cotacao/entity/InspectionRequest.java');
  const service = read('backend/src/main/java/br/com/nh/cotacao/service/AdminActivityService.java');
  const controller = read('backend/src/main/java/br/com/nh/cotacao/controller/SupervisionAnalysisController.java');
  const migration = read('backend/src/main/resources/db/tenant/V12__supervision_manual_acceptance_queue.sql');
  const dto = read('backend/src/main/java/br/com/nh/cotacao/dto/AdminDtos.java');
  assert.match(migration, /acceptance_queue_manual_at TIMESTAMPTZ/);
  assert.match(inspection, /markManualAcceptanceQueueMove\([\s\S]*?acceptanceQueueManualAt = OffsetDateTime.now\(\)/);
  assert.match(inspection, /invalidatePendingDigitalAcceptance\(\)\s*\{[\s\S]*?clearManualAcceptanceQueueMove\(\)/);
  assert.match(service, /moveAcceptanceQueueManually\([\s\S]*?PortalRole.SUPERVISION_ANALYSIS/);
  assert.match(service, /assertSupervisionInspectionAccess\(username, actorRole, id\)/);
  assert.match(service, /INSPECTION_ACCEPTANCE_QUEUE_OVERRIDE/);
  const body=service.match(/public AdminInspectionResponse moveAcceptanceQueueManually\([\s\S]*?private AdminInspectionResponse updateInspectionStatus\(/)?.[0];
  assert.ok(body);
  assert.doesNotMatch(body, /markDigitalAccepted|setAcceptedAt|setAcceptanceProofHash|completeDigitalAcceptance/);
  assert.match(controller, /acceptance\/queue\/mark-completed/);
  assert.match(controller, /acceptance\/queue\/undo/);
  assert.match(dto, /OffsetDateTime acceptanceQueueManualAt/);
});

test('fila da Supervisao distingue aceites digitais e estado manual', () => {
  const js = read('web/supervisao/supervisao.js');
  const html=read('web/supervisao/index.html');
  assert.match(js, /item\.allThreeAcceptancesCompleted \|\| item\.acceptanceQueueManualAt/);
  assert.match(js, /Mover para Aceites realizados/);
  assert.match(js, /Voltar para Aguardando aceite/);
  assert.match(js, /nenhum contrato ou termo pendente será considerado assinado/);
  assert.match(html, /20261030-v83-179-aceites-manuais-tenant/);
});
