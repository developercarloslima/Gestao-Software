#!/usr/bin/env python3
"""Agente confiável para VPS gerenciada; executa somente compose de template aprovado.

Instalar SEPARADAMENTE da aplicação pública, com conta restrita e Docker configurado.
Requer: Python 3.11+, Docker Compose, rede pública Traefik e imagens publicadas.
A Gestão Software valida o pagamento e cria a fila; este processo lê a fila pela chave privada.
"""
import json
import os
import pathlib
import re
import secrets
import base64
import shutil
import subprocess
import sys
import time
import argparse
import fcntl
import urllib.request
import urllib.error
from uuid import UUID

CONTROL = os.environ.get('GESTAO_API_URL', '').rstrip('/')
TOKEN = os.environ.get('PROVISIONER_WORKER_TOKEN', '')
ROOT_SETTING = pathlib.Path(os.environ.get('TENANT_WORKDIR', '/var/lib/gestao/tenants'))
ROOT = ROOT_SETTING.resolve()
TEMPLATE = pathlib.Path(__file__).with_name('compose.yaml')
SUFFIX = os.environ.get('TENANT_ALLOWED_HOST_SUFFIX', 'gestaosoftware.com.br').lower().strip('.')
WORKER_VPS_ID = os.environ.get('WORKER_VPS_ID','')
# A base universal só pode receber módulos suportados e publicados pelo catálogo central.
TENANT_MODULES = frozenset({'SITE_PRINCIPAL','COTA','VISTORIA_EVENTOS','APONTAMENTO_DIGITAL','GESTAO_FINANCEIRA_COMERCIAL'})
RESERVED_HOSTS = frozenset({'www','api','admin','painel','mail','smtp','ftp','root','gestao','status','monitor','dev','staging','localhost','ns1','ns2','cloud','suporte','help'})

required = [
 'GESTAO_CONTROL_URL','GESTAO_CONTROL_PUBLIC_URL','TRAEFIK_PUBLIC_NETWORK','TRAEFIK_CERTRESOLVER',
 'NH_BACKEND_IMAGE','NH_WEB_IMAGE','NH_PONTO_API_IMAGE','NH_PONTO_WEB_IMAGE','NH_HINOVA_IMAGE','NH_FACE_IMAGE','NH_FIPE_IMAGE'
]


def config_check(allow_missing_activation=False):
    if not CONTROL.startswith('https://') or not CONTROL or len(TOKEN) < 32:
        raise RuntimeError('Configure GESTAO_API_URL HTTPS e PROVISIONER_WORKER_TOKEN >= 32 caracteres.')
    if not allow_missing_activation and os.environ.get('ALLOW_RESOURCE_CREATION') != 'yes':
        raise RuntimeError('Implantação desativada: ALLOW_RESOURCE_CREATION deve ser yes após revisão.')
    for key in required:
        value = os.environ.get(key, '')
        if not value or ('example.com' in value and key.endswith('_IMAGE')):
            raise RuntimeError(f'Configuração obrigatória não definida: {key}')
        if key.endswith('_IMAGE') and not re.fullmatch(r'[a-zA-Z0-9_./:-]+@sha256:[a-fA-F0-9]{64}',value):
            raise RuntimeError(f'Imagem sem digest imutável em {key}. Publique e homologue a imagem no registry antes do deploy.')
    if not re.fullmatch(r'[a-z0-9]+(?:[.-][a-z0-9]+)*', SUFFIX):
        raise RuntimeError('TENANT_ALLOWED_HOST_SUFFIX inválido.')
    UUID(WORKER_VPS_ID) # VPS autorizada cadastrada no painel Gestão.
    if shutil.which('docker') is None:
        raise RuntimeError('Docker Compose indisponível.')
    if not allow_missing_activation:
        ROOT.mkdir(parents=True, exist_ok=True)


def req(path, body):
    data = json.dumps(body).encode('utf-8')
    request = urllib.request.Request(
        CONTROL + path, data=data,
        headers={'Content-Type':'application/json','X-Provisioner-Key':TOKEN,'X-Provisioner-Vps-Id':WORKER_VPS_ID,'User-Agent':'GS-Provisioner/1.0'},
        method='POST'
    )
    with urllib.request.urlopen(request, timeout=35) as response:
        return json.loads(response.read(512*1024))


def valid_cnpj(document):
    """Aceita CNPJ numérico com dois dígitos verificadores. Outras jurisdições requerem novo validador."""
    doc=re.sub(r'\D','',str(document))
    if len(doc)!=14 or len(set(doc))==1:return False
    digits=list(map(int,doc))
    def dv(values,weights):
        rem=sum(x*y for x,y in zip(values,weights))%11
        return 0 if rem<2 else 11-rem
    return dv(digits[:12],[5,4,3,2,9,8,7,6,5,4,3,2])==digits[12] and dv(digits[:13],[6,5,4,3,2,9,8,7,6,5,4,3,2])==digits[13]

def validate_job(job):
    cid = UUID(str(job['company_id']))
    if str(job.get('target_vps_id'))!=WORKER_VPS_ID:
        raise ValueError('Implantação não atribuída a esta VPS.')
    host = str(job['requested_host']).lower().strip()
    # A Gestão reserva exatamente um rótulo sob o domínio autorizado, sem sub-subdomínios.
    prefix = host[:-(len(SUFFIX)+1)] if host.endswith('.'+SUFFIX) else ''
    if (not prefix or len(host)>240 or not re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{1,46}[a-z0-9])',prefix)
        or '--' in prefix or prefix in RESERVED_HOSTS):
        raise ValueError('Subdomínio inválido ou reservado; escolha outro endereço autorizado.')
    modules=job.get('modules')
    if (not isinstance(modules,list) or not modules or any(not isinstance(m,str) or m not in TENANT_MODULES for m in modules)
        or len(modules)!=len(set(modules))):
        raise ValueError('Lista de módulos vazia, repetida ou não suportada pelo template universal.')
    admin_username = str(job.get('tenantAdminUsername') or '').strip().lower()
    if (not re.fullmatch(r'[a-z0-9][a-z0-9._@-]{2,119}', admin_username)
        or admin_username == 'admin@nh.local'):
        raise ValueError('Usuário administrador do tenant inválido ou ausente.')
    if job['provider'] != 'MANAGED_VPS':
        raise ValueError('Este agente aceita somente VPS gerenciada.')
    assessment = job.get('assessment', {}).get('estimate', {})
    if assessment.get('tier') != 'START' or assessment.get('requiresArchitectureReview'):
        raise ValueError('Perfil Enterprise/Business exige revisão técnica antes da implantação.')
    doc = re.sub(r'[^0-9]', '', str(job.get('branding',{}).get('document') or ''))
    if not valid_cnpj(doc):
        raise ValueError('CNPJ real e válido da empresa é obrigatório para esta base. Não criar CNPJ fictício.')
    return cid, host, doc


def value(s):
    text=str(s or '')
    if any(ch in text for ch in ('\r','\n','\0','$','`')):
        raise ValueError('Valor de configuração inválido (controle ou interpolação).')
    return text

def dotenv_value(s):
    # Quoting evita truncar nomes empresariais com #, aspas ou apóstrofo.
    # Docker Compose aceita aspas duplas e escapes de barra invertida e aspas.
    text=value(s)
    return '"'+text.replace('\\','\\\\').replace('"','\\"')+'"'


def install(job):
    cid, host, doc = validate_job(job)
    if ROOT_SETTING.is_symlink():
        raise ValueError('Diretório de tenants não pode ser link simbólico.')
    ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    # Exclusão mútua por empresa, inclusive com dois agentes acidentais na mesma VPS.
    lockfile=ROOT/('c'+cid.hex+'.lock')
    fd=os.open(str(lockfile),os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'r+') as lock:
        fcntl.flock(lock.fileno(),fcntl.LOCK_EX)
        return _install_locked(job,cid,host,doc)


def _install_locked(job,cid,host,doc):
    slug='c'+cid.hex[:16]  # Compatibilidade com workspaces v120; identidade completa é conferida em secrets.env.
    workspace=ROOT/slug
    if workspace.is_symlink() or (workspace/'secrets.env').is_symlink():
        raise ValueError('Workspace do tenant não pode ser um link simbólico.')
    if workspace.exists() and (workspace/'secrets.env').exists():
        # Idempotência: retomar o MESMO tenant no MESMO domínio sem redefinir credenciais.
        old={}
        for row in (workspace/'secrets.env').read_text(encoding='utf-8').splitlines():
            if '=' in row:
                k,v=row.split('=',1)
                if k in ('GESTAO_COMPANY_ID','TENANT_HOST'):
                    old[k]=v[1:-1] if len(v)>=2 and v[0]==v[-1]=='\"' else v
        if old.get('GESTAO_COMPANY_ID') != str(cid) or old.get('TENANT_HOST') != host:
            raise ValueError('Já existe workspace para outra identidade ou outro domínio. Migração manual necessária.')
    else:
        workspace.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(workspace,0o700)
        config={
            'GESTAO_COMPANY_ID':str(cid),'TENANT_HOST':host,'TENANT_SLUG':slug,
            'TENANT_DOCUMENT':doc,'TENANT_EMAIL':'','TENANT_WHATSAPP':'',
            'TENANT_DISPLAY_NAME':value(job.get('branding',{}).get('displayName') or job.get('branding',{}).get('tradeName')),
            'TENANT_LEGAL_NAME':value(job.get('branding',{}).get('legalName') or job.get('branding',{}).get('displayName') or job.get('branding',{}).get('tradeName')),
            'ADMIN_USERNAME':value(job['tenantAdminUsername'].strip().lower()),
            'CONSULTANT_USERNAME':'consultor','ANALYST_USERNAME':'analista','TOW_USERNAME':'guincho',
            'WORKSHOP_USERNAME':'oficina','EVENT_USERNAME':'eventos','BUYER_USERNAME':'financeiro',
            'PONTO_TENANT_ADMIN_USERNAME':'admin@'+host,
            'PONTO_SUPER_ADMIN_USERNAME':'superadmin@'+host,
        }
        modules=set(job.get('modules') or [])
        # The flow must not require quotations/catalogue if only Apontamento was purchased.
        config['TENANT_REQUIRES_COMMERCIAL_CATALOG']='true' if modules & {'COTA','VISTORIA_EVENTOS','GESTAO_FINANCEIRA_COMERCIAL'} else 'false'
        config['TENANT_REQUIRES_PAYMENT_INTEGRATION']='true' if 'GESTAO_FINANCEIRA_COMERCIAL' in modules else 'false'
        config['TENANT_REQUIRES_PONTO_SETUP']='true' if 'APONTAMENTO_DIGITAL' in modules else 'false'
        profiles=[]
        if 'APONTAMENTO_DIGITAL' in modules: profiles.append('ponto')
        if modules & {'COTA','VISTORIA_EVENTOS'}: profiles.append('fipe')
        if 'GESTAO_FINANCEIRA_COMERCIAL' in modules: profiles.append('hinova')
        config['COMPOSE_PROFILES']=','.join(profiles)
        for k in required: config[k]=value(os.environ[k])
        for k in ['POSTGRES_PASSWORD','PONTO_POSTGRES_PASSWORD','AUTH_TOKEN_SECRET',
            'ADMIN_PASSWORD','CONSULTANT_PASSWORD','ANALYST_PASSWORD','TOW_PASSWORD','WORKSHOP_PASSWORD',
            'EVENT_PASSWORD','BUYER_PASSWORD','INITIAL_PORTAL_PASSWORD','ADMIN_BULK_DELETE_PASSWORD','PONTO_DEFAULT_PASSWORD','PONTO_SUPER_ADMIN_PASSWORD','PONTO_JWT_SECRET',
            'PONTO_AUDIT_HASH_KEY','PONTO_PRESENCE_SECRET']:
            config[k]=secrets.token_hex(24)
        config['BIOMETRIC_ENCRYPTION_KEY']=secrets.token_hex(32)
        # Chave exclusiva de cada empresa para cifrar arquivos temporários/originais de importação.
        config['IMPORT_AES_KEY_BASE64']=base64.b64encode(secrets.token_bytes(32)).decode('ascii')
        env_file=workspace/'secrets.env'
        # escrita atômica para evitar secrets.env parcialmente preenchido em queda de energia
        temp=workspace/('secrets.env.tmp-'+secrets.token_hex(8))
        fd=os.open(str(temp),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        try:
            with os.fdopen(fd,'w',encoding='utf-8') as dst:
                dst.write(''.join(k+'='+dotenv_value(v)+'\n' for k,v in config.items()))
                dst.flush(); os.fsync(dst.fileno())
            os.replace(temp,env_file)
        except Exception:
            if temp.exists():temp.unlink()
            raise
    target=workspace/'compose.yaml'
    if target.is_symlink():raise ValueError('Arquivo compose do tenant não pode ser link simbólico.')
    if not target.exists():
        shutil.copyfile(TEMPLATE,target)
    proc=subprocess.run(['docker','compose','--project-directory',str(workspace),'--env-file',str(workspace/'secrets.env'),'-f',str(target),'up','-d','--pull','always'],
                        stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=900,check=False)
    if proc.returncode:
        raise RuntimeError('Docker Compose falhou (código '+str(proc.returncode)+'). Inspecione logs locais do servidor de provisionamento.')
    # A URL pública só será validada pela Gestão Software (TLS + identidade do tenant).
    return 'https://'+host, 'https://'+host+'/api',slug


def linux_metrics():
    """CPU/RAM/disco observados no servidor Linux, sem dependências Python de terceiros.
    A medição é operacional; não é garantia de capacidade ou de integridade do agente.
    """
    def cpu_snapshot():
        cols=pathlib.Path('/proc/stat').read_text(encoding='utf-8').splitlines()[0].split()[1:]
        vals=[int(v) for v in cols]
        return sum(vals), vals[3]+(vals[4] if len(vals)>4 else 0)
    start_total,start_idle=cpu_snapshot()
    time.sleep(0.15)
    end_total,end_idle=cpu_snapshot()
    diff=max(1,end_total-start_total)
    cpu=max(0.,min(100.,100.*(1-(end_idle-start_idle)/diff)))
    mem={}
    for line in pathlib.Path('/proc/meminfo').read_text(encoding='utf-8').splitlines():
        if ':' in line:
            key,value=line.split(':',1)
            if key in ('MemTotal','MemAvailable'):mem[key]=int(value.strip().split()[0])
    if mem.get('MemTotal',0)<=0:raise RuntimeError('Não foi possível medir memória no Linux.')
    ram=max(0.,min(100.,100.*(1-mem['MemAvailable']/mem['MemTotal'])))
    disk=shutil.disk_usage(ROOT if ROOT.exists() else pathlib.Path('/'))
    disk_percent=100.0*disk.used/max(1,disk.total)
    # Conta tenants cujos contêineres realmente estão em execução (não apenas instalados).
    ps=subprocess.run(['docker','ps','--format','{{.Label "com.docker.compose.project"}}'],
                      capture_output=True,text=True,timeout=8,check=True)
    count=len({name for name in ps.stdout.splitlines() if name.startswith('gs-')})
    return {'agentVersion':'gs-cloud-1.0','cpuPercent':round(cpu,2), 'memoryPercent':round(ram,2),
            'diskPercent':round(disk_percent,2),'runningTenants':count}


def send_heartbeat():
    result=req('/api/agent/cloud/heartbeat',linux_metrics())
    if not result.get('accepted'):raise RuntimeError('Heartbeat recusado pela Gestão Software.')
    return result


def run_once():
    job = req('/api/agent/implantacao/proximo',{})
    if not job.get('id'):return False
    jid = str(UUID(job['id']))
    try:
        app, api, external = install(job)
        req('/api/agent/implantacao/jobs/'+jid+'/resultado',{
            'status':'VERIFYING','appUrl':app,'apiUrl':api,'externalId':external,
            'message':'Contêineres lançados; aguardando DNS, SSL e verificação da identidade do tenant.'})
    except Exception as exc:
        # Nunca retornar segredos ao control plane.
        text=str(exc)[:250].replace('\n',' ')
        req('/api/agent/implantacao/jobs/'+jid+'/resultado',{'status':'FAILED','message':text})
    return True


def main():
    parser=argparse.ArgumentParser(description='Agente restrito de implantação em VPS gerenciada')
    parser.add_argument('--check',action='store_true',help='Validar configuração sem habilitar ou criar recursos')
    parser.add_argument('--validate-job',help='Conferir JSON de job salvo em disco, sem instalar recursos')
    parser.add_argument('--heartbeat-only',action='store_true',help='Medir recursos e registrar presença da VPS, sem criar recursos')
    args=parser.parse_args()
    config_check(allow_missing_activation=args.check or bool(args.validate_job) or args.heartbeat_only)
    if args.check:
        print('Pré-requisitos básicos e tags imutáveis validados. Nenhuma infraestrutura foi criada; ainda falta testar DNS, TLS, registry, backup e isolamento.');return
    if args.validate_job:
        job=json.loads(pathlib.Path(args.validate_job).read_text(encoding='utf-8'))
        company,host,document=validate_job(job)
        print(f'Job válido para tenant {company} e domínio {host}. Nenhum serviço criado.');return
    if args.heartbeat_only:
        result=send_heartbeat()
        print('Métricas recebidas pela Gestão Cloud para VPS '+result['vpsId']+'. Nenhuma implantação iniciada.');return
    last_heartbeat=0.0
    while True:
        try:
            if time.monotonic()-last_heartbeat>=45:
                send_heartbeat()
                last_heartbeat=time.monotonic()
            if run_once():continue
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            print('Não foi possível comunicar com o control plane:',type(e).__name__,file=sys.stderr)
        except Exception as e:
            print('Erro no agente:',type(e).__name__,file=sys.stderr)
        time.sleep(20)


if __name__=='__main__': main()
