#!/usr/bin/env python3
"""Release audit without Docker or a database. Real integration is separate and mandatory."""
from pathlib import Path
import sys,re,subprocess,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]

def check(title,condition):
    assert condition,title
    print('PASS',title)

def main():
    tenant=ROOT/'backend/src/main/resources/db/tenant'
    original=ROOT/'backend/src/main/resources/db/migration'
    files=sorted(tenant.glob('V*.sql'))
    check('tenant SQL versioned independently', len(files)==11 and sorted(int(f.name.split('__')[0][1:]) for f in files) == list(range(1,12)) and len(set(f.name for f in files)) == 11)
    check('tenant V3 contains no customer history DML',not re.search(r'(?m)^\s*(?:INSERT\s+INTO|UPDATE\s+\w+|DELETE\s+FROM|TRUNCATE\s+\w+)',(tenant/'V3__term_acceptance_audit_columns.sql').read_text(),re.I))
    check('term structure independent', 'inspection_parts_delay_terms' in (tenant/'V8__separate_terms_and_webauthn_recovery.sql').read_text())
    check('new links are seven-day scoped without legacy backfill', 'link_available_at' in (tenant/'V9__acceptance_link_lifetimes.sql').read_text() and 'UPDATE inspection_responsibility_terms' not in (tenant/'V9__acceptance_link_lifetimes.sql').read_text())
    check('regional catalogs start without prices', 'INSERT INTO' not in (tenant/'V7__regional_configuration_without_prices.sql').read_text().upper())
    check('terms store immutable legal snapshot', 'legal_clauses_snapshot' in (tenant/'V11__immutable_parts_term_legal_snapshot.sql').read_text())
    check('FIPE schema isolated', 'fipe_dataset_prices' in (tenant/'V5__fipe_dataset_snapshots.sql').read_text() and 'inspection_pending_fipe_selection' in (tenant/'V6__inspection_fipe_version_selection.sql').read_text())
    check('tenant FIPE sync defaults disabled', 'FIPE_DATASET_SYNC_ENABLED:false' in (ROOT/'backend/src/main/resources/application-tenant.yml').read_text())
    ddl=(tenant/'V1__universal_structure.sql').read_text()
    check('structural migration includes core entities',all(re.search(r'CREATE TABLE (?:IF NOT EXISTS )?'+name+r'\b',ddl,re.I) for name in ['plans','coverages','portal_users','associates']))
    check('no historical commercial DML in tenant schema',not re.search(r'(?m)^\s*(?:INSERT\s+INTO|UPDATE\s+\w+|DELETE\s+FROM|TRUNCATE\s+\w+)',ddl,re.I))
    check('commercial tables not preloaded in configuration migration',not re.search(r'INSERT\s+INTO\s+(plans|plan_coverages|coverages|consultant_commission_rules)',(tenant/'V2__tenant_configuration.sql').read_text(),re.I))
    check('original SQL migrations untouched by new tenant baseline',len(list(original.glob('V*.sql')))>80)
    p=ET.parse(ROOT/'backend/pom.xml')
    pom=(ROOT/'backend/pom.xml').read_text()
    check('tenant Maven profile excludes historic migration resources','<id>tenant</id>' in pom and '<exclude>db/migration/**</exclude>' in pom)
    check('tenant Docker image distinct from legacy', '-Ptenant' in (ROOT/'backend/Dockerfile.tenant').read_text())
    check('separate Spring profile migration location','classpath:db/tenant' in (ROOT/'backend/src/main/resources/application-tenant.yml').read_text())
    point_entry=(ROOT/'nh-ponto/entrypoint-nh.sh').read_text()
    check('no db push or unsafe schema synchronization on tenant', 'prisma migrate deploy' in point_entry and 'prisma db push' not in point_entry)
    seed=(ROOT/'nh-ponto/prisma/seed-tenant.ts').read_text()
    check('no fake CNPJ in system tenant','99999999999999' not in seed)
    check('biometrics fail-closed by default in point seed','requireFaceMatch:true' in seed and 'blockWithoutEnrollment:true' in seed)
    check('neutral logos and legal documents in site bundle',not any((ROOT/'web/assets'/n).exists() for n in ['simbolo-fundo-azul.png','regulamento-ppv-novo-horizonte-2026.pdf','guia-vistoria-carro.png','app-phone-mockup.png']))
    check('tenant readiness prevents automatic URL release', 'readyForCustomers' in (ROOT/'backend/src/main/java/br/com/nh/cotacao/service/TenantLaunchService.java').read_text())
    check('Ponto requirement tied to contracted modules', 'TENANT_REQUIRES_PONTO_SETUP' in (ROOT/'backend/src/main/resources/application.yml').read_text())
    # These checks do not prove full Maven or PostgreSQL compatibility.
    js=list(ROOT.glob('web/**/*.js'))
    for f in js:
        res=subprocess.run(['node','--check',str(f)],capture_output=True,text=True)
        check('JavaScript syntax: '+str(f.relative_to(ROOT)),res.returncode==0)
    print(f'RESULT: {len(js)} frontend JS files parsed by Node, plus structural/security checks.')

if __name__=='__main__':main()
