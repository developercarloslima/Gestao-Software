#!/usr/bin/env python3
"""Auditoria bloqueante para publicar uma imagem de produto genérico.
Aprovação exige ZERO ocorrências de legado em arquivos runtime.
Nunca executa ou altera o sistema; não lê imagens como texto.
"""
from pathlib import Path
import argparse, json, re, sys
PATTERNS={
 'MARCA_CLIENTE_LEGADO':re.compile(r'novo\s+horizonte|nh\s*prote[cç][aã]o|nhprotecao',re.I),
 'USUARIO_LEGADO':re.compile(r'genival[. ]santos|leandro(?:@|\b)|pedro\s+henrique',re.I),
 'SEGREDO_INICIAL_LEGADO':re.compile(r'nh2027|@Aguias2025|00000000000191',re.I),
}
RUNTIME=['backend/src/main','nh-ponto/apps/api/src','nh-ponto/apps/web/src','web','nh-ponto/prisma']
EXTS={'.java','.sql','.js','.jsx','.ts','.tsx','.html','.css','.json','.yml','.yaml','.properties','.env','.txt'}
EXCLUDES={'node_modules','dist','build','target','.git','coverage','__pycache__','test','tests','e2e'}
def scan(root, tenant_runtime=False):
    if tenant_runtime:
        pom=(root/'backend/pom.xml').read_text('utf-8')
        if '<id>tenant</id>' not in pom or '<exclude>db/migration/**</exclude>' not in pom:
            raise RuntimeError('O perfil Maven tenant deve excluir as migrações legadas do artefato.')
    rows=[]
    for area in RUNTIME:
        base=root/area
        if not base.exists():continue
        for file in base.rglob('*'):
            if tenant_runtime and base == root/'backend/src/main' and file.is_relative_to(root/'backend/src/main/resources/db/migration'):
                continue  # História NH mantida para legado, mas excluída do JAR tenant (-Ptenant).
            if file.suffix.lower() not in EXTS or not file.is_file() or set(file.relative_to(base).parts)&EXCLUDES:continue
            try:text=file.read_text('utf-8')
            except (OSError, UnicodeError):continue
            for kind,pat in PATTERNS.items():
                matches=list(pat.finditer(text))
                if matches:
                    rows.append({'file':str(file.relative_to(root)).replace('\\','/'),'type':kind,'count':len(matches),'lines':sorted(set(text.count('\n',0,m.start())+1 for m in matches))[:15]})
    # Audit binary/static assets by filename as text scanning never catches embedded logos/PDF.
    forbidden_asset_names={'simbolo-fundo-azul.png','regulamento-ppv-novo-horizonte-2026.pdf',
                           'guia-vistoria-carro.png','guia-vistoria-moto.png','app-phone-mockup.png'}
    for base in [root/'web/assets',root/'nh-ponto/apps/web/public']:
        if not base.exists(): continue
        for file in base.rglob('*'):
            if file.is_file() and (file.name.lower() in forbidden_asset_names or re.search(r'novo[-_ ]?horizonte|nhprotecao',file.name,re.I)):
                rows.append({'file':str(file.relative_to(root)).replace('\\','/'),'type':'MARCA_BINARIA_LEGADA','count':1,'lines':[]})
    return sorted(rows,key=lambda x:(-x['count'],x['file']))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',required=True);p.add_argument('--json');p.add_argument('--tenant-runtime',action='store_true');a=p.parse_args();root=Path(a.root)
    result=scan(root,a.tenant_runtime)
    total=sum(r['count'] for r in result)
    payload={'certified':not result,'files_with_findings':len(result),'findings':total,'entries':result,'message':'BLOQUEADO: dados/identidade legados nos módulos; não publicar imagens white-label.' if result else 'Sem os termos bloqueados. Ainda requer testes funcionais e jurídicos.'}
    if a.json:Path(a.json).write_text(json.dumps(payload,indent=2,ensure_ascii=False))
    print(f"AUDITORIA WHITE-LABEL: {'APROVADO ESTATICAMENTE' if not result else 'BLOQUEADO'}; {len(result)} arquivos; {total} ocorrências.")
    for row in result[:25]:print(row['file'],row['type'],row['count'],'linhas',row['lines'][:5])
    if result:sys.exit(2)
