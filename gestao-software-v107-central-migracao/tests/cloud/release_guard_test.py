#!/usr/bin/env python3
"""Guards static from confirmed v105 regressions; complements, never replaces DB/Maven tests."""
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[2]
C=ROOT/'backend/src/main/java/br/com/gestaosoftware/control'
def check(message, condition):
    if not condition: raise AssertionError(message)
    print('PASS:',message)

def main():
    security=(C/'config/SecurityConfig.java').read_text()
    check('rotas não previstas têm acesso negado', '.anyRequest().denyAll()' in security and '.anyRequest().permitAll()' not in security)
    origin=(ROOT/'backend/src/main/resources/application.yml').read_text()
    check('CORS administrativo sem coringa de clientes', 'https://*.gestaosoftware.com.br' not in origin)
    cloud=(C/'service/CloudInfrastructureService.java').read_text()
    auto=cloud[cloud.index('public Map<String,Object> autoReserve'):cloud.index('public Map<String,Object> reserve')]
    manual=cloud[cloud.index('public Map<String,Object> reserve'):cloud.index('public void validateCloudDeployment')]
    check('reserva automática: lock por empresa antes das VPS', auto.index('SELECT id FROM platform_companies WHERE id=? FOR UPDATE') < auto.index('SELECT id FROM platform_vps WHERE active=TRUE ORDER BY id FOR UPDATE'))
    check('reserva manual: lock por empresa antes das VPS', manual.index('SELECT id FROM platform_companies WHERE id=? FOR UPDATE') < manual.index('SELECT id FROM platform_vps WHERE id=? FOR UPDATE'))
    check('reservas com falhas mantidas e não realocadas automaticamente', "status='HELD_FAILED'" in manual and "status='ERROR'" in manual)
    mgmt=(C/'service/PlatformManagementService.java').read_text()
    deletion=mgmt[mgmt.index('public void deleteCompany'):mgmt.index('public Map<String, Object> saveCompanyFile')]
    check('exclusão arquiva sem apagar histórico financeiro, contratos, deploys ou cadastro',
          "status='CANCELLED'" in deletion and 'auditCompanyLifecycle' in deletion
          and 'DELETE FROM' not in deletion and 'DELETE FROM platform_companies' not in mgmt)
    migrations=ROOT/'backend/src/main/resources/db/migration'
    check('migrações originais v1-v9 e V10 aditiva presentes',
          sorted(int(f.name.split('__')[0][1:]) for f in migrations.glob('V*.sql'))==list(range(1,11))
          and (migrations/'V9__disable_legacy_bootstrap_credentials.sql').exists()
          and 'status_before_suspend' in (migrations/'V10__company_lifecycle_audit.sql').read_text())
    controller=(C/'controller/PlatformManagementController.java').read_text()
    check('ciclo de vida restrito aos sócios e auditado',
          all(x in controller for x in ('companyLifecycleOwner(authentication)', '/reactivate', 'carlos.lima', 'pedro.henrique'))
          and 'COMPANY_REACTIVATED' in mgmt and 'COMPANY_RESTORED' in mgmt and 'COMPANY_DEACTIVATED' in mgmt)
    admin=(ROOT/'web/admin/app.js').read_text()
    actions=admin[admin.index("const deactivate=e.target.closest"):admin.index("const edit=e.target.closest")]
    check('empresa usa modal Gestão no lugar de confirmação nativa',
          'confirmCompanyAction' in actions and 'if(confirm(' not in actions
          and 'data-reactivate-company' in admin and 'company-action-dialog' in admin)
    print('RESULT: 9 checks of static release safety (not transactional integration tests).')

if __name__=='__main__':main()
