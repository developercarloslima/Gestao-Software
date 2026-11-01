#!/usr/bin/env python3
"""Somente testes de coerência estrutural; não substituir Maven/PostgreSQL/Docker."""
from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]
def read(p):return (root/p).read_text(encoding='utf-8')
checks={
'15 migrações exclusivas do tenant, sem repetir versão': sorted(int(f.name.split('__')[0][1:]) for f in (root/'backend/src/main/resources/db/tenant').glob('V*.sql'))==list(range(1,16)),
'sem importação automática de plano comercial histórico': 'INSERT INTO' not in read('backend/src/main/resources/db/tenant/V7__regional_configuration_without_prices.sql').upper(),
'desconto só no valor base': bool(re.search(r'base\.multiply\(factor\)\s*\.add\(mandatory\)\s*\.add\(optionals\)',read('backend/src/main/java/br/com/nh/cotacao/entity/Quotation.java'))),
'regra de terceiros configurável por tenant': 'PENDING_CONFIGURATION' in read('backend/src/main/java/br/com/nh/cotacao/service/ThirdPartyRuleSettingsService.java'),
'aceites vinculados a cláusulas imutáveis': 'legal_clauses_snapshot' in read('backend/src/main/resources/db/tenant/V11__immutable_parts_term_legal_snapshot.sql') and 'legalClausesSnapshot' in read('backend/src/main/java/br/com/nh/cotacao/service/InspectionPartsDelayTermService.java'),
'dossiê incorpora termo aceito real': 'appendAcceptedStandaloneResponsibilityTerm' in read('backend/src/main/java/br/com/nh/cotacao/service/RetratoPdfService.java'),
'supervisor pode redistribuir na UI e API': "'/api/supervision/inspections/analysts'" in read('web/supervisao/supervisao.js') and 'reassignInspectionAnalyst' in read('backend/src/main/java/br/com/nh/cotacao/service/AdminActivityService.java'),
'fipecode consulta API independente': '/buscar-modelos' in read('backend/src/main/java/br/com/nh/cotacao/service/FipeDatasetCatalogService.java'),
'FIPE isolada com perfil e sem porta externa no template': bool(re.search(r'fipe-api:\n\s+profiles: \[fipe\]',read('deployment/compose.tenant.yaml'))) and 'tenant_fipe_data' in read('deployment/compose.tenant.yaml') and 'ports:' not in read('deployment/compose.tenant.yaml').split('  fipe-api:',1)[1].split('  fipe-updater:',1)[0],
'sem atribuição fixa a analista da NH': 'analiselarissa' not in read('backend/src/main/java/br/com/nh/cotacao/service/ConsultantService.java').lower(),
'backup e migração testáveis em banco descartável': 'tmpfs:' in read('docker-compose.tenant-homologacao.yml') and '127.0.0.1:3188:8080' in read('docker-compose.tenant-homologacao.yml'),
'bootstrap NH Ponto não executa seed legado': 'throw new Error' in read('nh-ponto/prisma/seed.ts') and 'seed-tenant.ts' in read('nh-ponto/entrypoint-nh.sh'),
'web /ciência com rota explícita': 'location /ciencia-pecas/' in read('web/nginx.conf'),
'base não pronta até receber termos próprios': 'TENANT_PARTS_DELAY_CLAUSES' in read('backend/src/main/java/br/com/nh/cotacao/service/TenantLaunchService.java'),
'perfil de Maven tenant exclui histórico NH': '<exclude>db/migration/**</exclude>' in read('backend/pom.xml'),
}
for name,ok in checks.items():
 if not ok:print('FAIL',name)
 else:print('PASS',name)
assert all(checks.values()),'Verificações estruturais falharam'
print(f'RESULT: {len(checks)} verificações estruturais aprovadas; integração real pendente.')
