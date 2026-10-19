# Kit agente VPS gerenciada v118

Agente exclusivo de VPS administrada pela Gestão Software, associado à `WORKER_VPS_ID`. Não aceita IP/host arbitrário, VPS diferente, CNPJ inválido, provedor diferente nem dimensionamento BUSINESS/ENTERPRISE.

## Segurança aplicada
- Imagens devem ser pinadas a `@sha256:<digest>` e previamente publicadas.
- Autenticação com token do agente; instalação só quando `ALLOW_RESOURCE_CREATION=yes` foi configurado expressamente pelo operador autorizado.
- `.env` de cada empresa criado com permissão `0600`; pasta própria `0700`, segredos aleatórios distintos, volumes/bancos independentes.
- Docker Compose usa `profiles` para subir Ponto e Hinova apenas quando os respectivos módulos foram contratados.
- O agente reporta apenas URL candidata; a Gestão precisa validar HTTPS, identidade e checklist antes de torná-la pública.

## Validações sem criação de infraestrutura
```bash
python3 -m unittest test_provisioner_agent -v
python3 -m py_compile provisioner_agent.py
python3 provisioner_agent.py --check
```
A última checagem exige configurar o ambiente em `env.sample` (segredos não devem ser comitados). Sem essas variáveis, deve recusar corretamente.

## Implantação real: ainda depende de testes
Este agente **não cria VPS**, não registra DNS, não instala Traefik, não configura backup automaticamente, não integra AWS nem Render. Requer servidor/registry/DNS/TLS previamente gerenciados. Não usar para empresas pagantes antes da execução da CI e homologação do NH v118. A flag de criação de recursos deve permanecer desativada.
