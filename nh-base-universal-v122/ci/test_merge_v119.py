#!/usr/bin/env python3
"""Regressões estáticas dos portais e das migrações v119; não substituem integração real."""
from pathlib import Path
import re,subprocess,sys
ROOT=Path(__file__).resolve().parents[1]
passed=0
def check(name, ok):
    global passed
    assert ok,name
    passed+=1
    print('PASS',name)
def read(path):return (ROOT/path).read_text(encoding='utf-8')
for area,prefix,func in [('analise','analysis','syncAnalysisResponsibilityTermBox'),('supervisao','supervision','syncSupervisionResponsibilityTermBox')]:
    html=read(f'web/{area}/index.html')
    js=read(f'web/{area}/{area}.js')
    for suffix in ('send','copy','open','download'):
        check(f'{area} botão {suffix}',f'id="{prefix}-{suffix}-responsibility-term"' in html and f"$('{prefix}-{suffix}-responsibility-term')" in js)
    check(f'{area} status na abertura',f'{func}(item)' in js and f'async function {func}(item)' in js)
    check(f'{area} prévias com concorrência limitada','Math.min(3, queue.length)' in js and 'for (let attempt = 0; attempt < 3;' in js)
    check(f'{area} fallback sem erro técnico','Prévia indisponível' in js)
admin_js=read('web/admin/admin.js')
check('admin financeiro preservado', 'renderConsultantFinance' in admin_js and 'saveConsultantCommission' in admin_js)
check('admin pode baixar termo', 'admin-download-responsibility-term' in admin_js and 'admin-download-responsibility-term' in read('web/admin/index.html'))
check('admin previews com limite','Math.min(3, queue.length)' in admin_js)
accept=read('web/aceite-termo/aceite-termo.js')
check('aceite WebAuthn verificação obrigatória', "userVerification:'required'" in accept and "residentKey:'discouraged'" in accept)
check('aceite não se apresenta como login facial', 'Novo Horizonte' not in accept and 'nh2027' not in accept)
check('backend grava somente aceite real', 'accepted_at=?' in read('backend/src/main/java/br/com/nh/cotacao/service/InspectionResponsibilityTermService.java'))
check('status refletido em DTO', 'String acceptanceSource' in read('backend/src/main/java/br/com/nh/cotacao/dto/ResponsibilityTermDtos.java'))
check('migração V3 apenas coluna nova', 'ADD COLUMN IF NOT EXISTS acceptance_source' in read('backend/src/main/resources/db/tenant/V3__term_acceptance_audit_columns.sql'))
check('nenhum backfill de outra empresa', not re.search(r'\b(?:INSERT|UPDATE|DELETE)\b\s+(?:INTO\s+|FROM\s+)?(?:inspection_requests|inspection_responsibility_terms)',read('backend/src/main/resources/db/tenant/V3__term_acceptance_audit_columns.sql'),re.I))
check('direitos específicos por empresa', 'TENANT_RESPONSIBILITY_TERM_CLAUSES' in read('backend/src/main/java/br/com/nh/cotacao/service/ResponsibilityTermPdfService.java') and 'RESPONSIBILITY_TERM_CLAUSES_MISSING' in read('backend/src/main/java/br/com/nh/cotacao/service/TenantLaunchService.java'))
check('não substitui financeiro por versão do cliente', 'CommercialFinanceService' in read('backend/src/main/java/br/com/nh/cotacao/service/QuoteService.java'))
check('não copia senhas antigas no NH Ponto', 'randomBytes(24)' in read('nh-ponto/apps/api/src/routes/super-admin.ts'))
print(f'RESULT: {passed} verificações positivas; integração real pendente.')
