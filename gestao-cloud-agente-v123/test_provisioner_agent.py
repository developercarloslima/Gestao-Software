"""Offline, non-destructive validation of tenant deployment jobs."""
import unittest
from unittest.mock import patch
import uuid
import provisioner_agent as agent

WORKER=str(uuid.uuid4())
COMPANY=str(uuid.uuid4())

def job():
    return {
        'company_id':COMPANY,
        'target_vps_id':WORKER,
        'requested_host':'exemplo.gestaosoftware.com.br',
        'provider':'MANAGED_VPS',
        'assessment':{'estimate':{'tier':'START','requiresArchitectureReview':False}},
        'branding':{'document':'11.222.333/0001-81','displayName':'Empresa Exemplo'},
        'tenantAdminUsername':'admin@exemplo.com.br',
        'modules':['COTA']
    }

class AgentValidationTest(unittest.TestCase):
    def setUp(self):
        self.worker=patch.object(agent,'WORKER_VPS_ID',WORKER)
        self.suffix=patch.object(agent,'SUFFIX','gestaosoftware.com.br')
        self.worker.start(); self.suffix.start()
        self.addCleanup(self.worker.stop); self.addCleanup(self.suffix.stop)
    def test_valid_cnpj(self):
        self.assertTrue(agent.valid_cnpj('11.222.333/0001-81'))
        self.assertFalse(agent.valid_cnpj('11.222.333/0001-80'))
        self.assertFalse(agent.valid_cnpj('00.000.000/0000-00'))
    def test_accepted_job_does_not_install(self):
        cid,host,document=agent.validate_job(job())
        self.assertEqual(str(cid),COMPANY)
        self.assertEqual(host,'exemplo.gestaosoftware.com.br')
        self.assertEqual(document,'11222333000181')
    def test_reject_other_worker(self):
        x=job(); x['target_vps_id']=str(uuid.uuid4())
        with self.assertRaises(ValueError):agent.validate_job(x)
    def test_reject_host_outside_scope(self):
        x=job();x['requested_host']='exemplo.example.net'
        with self.assertRaises(ValueError):agent.validate_job(x)
    def test_reject_false_suffix(self):
        x=job();x['requested_host']='gestaosoftware.com.br.attacker.com'
        with self.assertRaises(ValueError):agent.validate_job(x)
    def test_reject_provider(self):
        x=job();x['provider']='AWS'
        with self.assertRaises(ValueError):agent.validate_job(x)
    def test_reject_business_tier(self):
        x=job();x['assessment']['estimate']['tier']='BUSINESS'
        with self.assertRaises(ValueError):agent.validate_job(x)
    def test_reject_invalid_document(self):
        x=job();x['branding']['document']='99999999999999'
        with self.assertRaises(ValueError):agent.validate_job(x)
    def test_refuse_env_injection(self):
        for v in ['bad\nPASSWORD=abc','some$value','cmd`id`']:
            with self.assertRaises(ValueError):agent.value(v)

    def test_reject_unsupported_modules(self):
        for mods in ([],['UNKNOWN_MODULE'],['COTA','COTA'],['COTA','UNSUPPORTED']):
            j=job();j['modules']=mods
            with self.subTest(mods=mods),self.assertRaises(ValueError):agent.validate_job(j)
    def test_reject_nested_or_reserved_subdomains(self):
        for host in ('x.y.gestaosoftware.com.br','admin.gestaosoftware.com.br','a--b.gestaosoftware.com.br'):
            j=job();j['requested_host']=host
            with self.subTest(host=host),self.assertRaises(ValueError):agent.validate_job(j)
    def test_accept_all_supported_modules(self):
        for m in agent.TENANT_MODULES:
            j=job();j['modules']=[m]
            with self.subTest(module=m):self.assertEqual(agent.validate_job(j)[1],'exemplo.gestaosoftware.com.br')
    def test_rejects_missing_or_legacy_tenant_admin(self):
        for username in (None, '', 'admin@nh.local', 'in valido'):
            j=job()
            j['tenantAdminUsername']=username
            with self.subTest(username=username), self.assertRaises(ValueError):
                agent.validate_job(j)

    def test_dotenv_escapes_branding_names(self):
        import json
        for raw in ('Cliente #1','Empresa "Teste"',"Cliente's Brasil",'C:\\temp'):
            with self.subTest(value=raw):
                self.assertEqual(json.loads(agent.dotenv_value(raw)),raw)

if __name__=='__main__':unittest.main(verbosity=2)
