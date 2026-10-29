"""Segurança do agente: sem conexão com VPS real e sem executar Docker."""
import os
import pathlib
import tempfile
import unittest
import uuid
from unittest.mock import patch
import provisioner_agent as agent

WORKER=str(uuid.uuid4())
COMPANY=str(uuid.uuid4())
VALID={
    'company_id':COMPANY,'target_vps_id':WORKER,
    'requested_host':'cliente.gestaosoftware.com.br','provider':'MANAGED_VPS',
    'assessment':{'estimate':{'tier':'START','requiresArchitectureReview':False}},
    'branding':{'document':'11.222.333/0001-81','displayName':'Empresa Cliente'},
        'tenantAdminUsername':'admin@exemplo.com.br',
    'modules':['COTA']
}

class AgentDeploymentTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.patches=[patch.object(agent,'ROOT',pathlib.Path(self.tmp.name)),
                      patch.object(agent,'WORKER_VPS_ID',WORKER),
                      patch.object(agent,'SUFFIX','gestaosoftware.com.br')]
        for p in self.patches:p.start();self.addCleanup(p.stop)

    def test_reject_double_dot_domain(self):
        j=dict(VALID,requested_host='client..gestaosoftware.com.br')
        with self.assertRaises(ValueError):agent.validate_job(j)

    def test_reject_bad_hyphen(self):
        for host in ['-client.gestaosoftware.com.br','client-.gestaosoftware.com.br']:
            with self.subTest(host=host):
                with self.assertRaises(ValueError):agent.validate_job(dict(VALID,requested_host=host))

    def test_idempotent_reuse_of_secrets(self):
        config={k:'registry.local/gs/'+k.lower()+'@sha256:'+'f'*64 for k in agent.required if k.endswith('_IMAGE')}
        config.update({k:'test-value' for k in agent.required if not k.endswith('_IMAGE')})
        with patch.dict(os.environ,config),patch.object(agent.subprocess,'run') as run:
            run.return_value.returncode=0
            a=agent.install(dict(VALID))
            secret=pathlib.Path(self.tmp.name)/('c'+uuid.UUID(COMPANY).hex[:16])/'secrets.env'
            first=secret.read_bytes()
            b=agent.install(dict(VALID))
            self.assertEqual(first,secret.read_bytes())
            self.assertEqual(a,b)
            self.assertEqual(secret.stat().st_mode & 0o777,0o600)
            self.assertEqual(run.call_count,2)

    def test_refuse_domain_change_for_existing_workspace(self):
        folder=pathlib.Path(self.tmp.name)/('c'+uuid.UUID(COMPANY).hex[:16]);folder.mkdir()
        (folder/'secrets.env').write_text(f'GESTAO_COMPANY_ID={COMPANY}\nTENANT_HOST=antigo.gestaosoftware.com.br\n')
        with self.assertRaisesRegex(ValueError,'Migração manual'):
            agent.install(dict(VALID))

    def test_refuse_symlink_workspace(self):
        folder=pathlib.Path(self.tmp.name)/('c'+uuid.UUID(COMPANY).hex[:16]);folder.symlink_to(pathlib.Path(self.tmp.name))
        with self.assertRaisesRegex(ValueError,'link simbólico'):
            agent.install(dict(VALID))

    def test_agent_never_calls_docker_if_worker_is_wrong(self):
        j=dict(VALID,target_vps_id=str(uuid.uuid4()))
        with patch.object(agent.subprocess,'run') as run:
            with self.assertRaises(ValueError):agent.install(j)
            run.assert_not_called()

    def test_two_concurrent_installs_keep_same_credentials(self):
        from concurrent.futures import ThreadPoolExecutor
        config={k:'registry.local/gs/'+k.lower()+'@sha256:'+'f'*64 for k in agent.required if k.endswith('_IMAGE')}
        config.update({k:'test-value' for k in agent.required if not k.endswith('_IMAGE')})
        with patch.dict(os.environ,config),patch.object(agent.subprocess,'run') as run:
            run.return_value.returncode=0
            with ThreadPoolExecutor(max_workers=2) as pool:
                futures=[pool.submit(agent.install,dict(VALID)) for _ in range(2)]
                outputs=[f.result(timeout=5) for f in futures]
            self.assertEqual(outputs[0],outputs[1])
            workspace=pathlib.Path(self.tmp.name)/('c'+uuid.UUID(COMPANY).hex[:16])
            self.assertTrue((workspace/'secrets.env').exists())
            self.assertEqual(list(workspace.glob('secrets.env.tmp-*')),[])

if __name__=='__main__':unittest.main(verbosity=2)
