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
    check('exclusão de empresa proíbe apagar histórico financeiro, contratos e deploys', all(s in deletion for s in ('platform_company_invoices','platform_company_contracts','gs_deployments','gs_cloud_domains')) and 'DELETE FROM platform_company_invoices' not in deletion)
    check('migrações de banco v1-v8 permanecem sem novas substituições', len(list((ROOT/'backend/src/main/resources/db/migration').glob('V*.sql')))==8)
    print('RESULT: 7 checks of static release safety (not transactional integration tests).')

if __name__=='__main__':main()
