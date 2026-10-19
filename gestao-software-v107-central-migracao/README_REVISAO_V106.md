GESTÃO CLOUD V106 — REVISÃO TÉCNICA E SEGURANÇA

Inclui alterações incrementais sobre a Gestão v105 e o agente VPS v120.
Não altera a base NH universal v118, nem modifica V1–V8 do Flyway.

ALTERAÇÕES:
- Rotas desconhecidas do Control Plane passam a ser negadas por padrão.
- CORS administrativo exclui o coringa de subdomínios de clientes; origens adicionais devem ser explicitamente autorizadas.
- Reservas automáticas e manuais adquirem bloqueios na ordem empresa -> VPS.
- Empresas com implantação falhada/recursos retidos não podem ser realocadas silenciosamente.
- Exclusão de empresa com faturas, contratos, deploys, reserva Cloud ou hospedagem ativa é bloqueada; usar desativação.
- Erros de negócio da infraestrutura passam a ser exibidos em linguagem adequada ao operador.
- O painel não pré-seleciona a primeira VPS como se fosse automaticamente a mais adequada.
- Agente VPS v121: valida módulos oficiais, bloqueia hosts reservados/aninhados,
  protege a criação concorrente de segredos e serializa nomes de empresa para dotenv.
- Testes locais de segurança/regressão ampliados.

SEGURANÇA:
- NÃO execute diretamente sobre a instalação NH existente.
- Não execute docker compose down -v nem remova bancos/volumes.
- As flags AUTO_PROVISION_ENABLED / TENANT_TEMPLATE_CERTIFIED / CLOUD_ORCHESTRATOR_ENABLED
  e ALLOW_RESOURCE_CREATION devem continuar desativadas até homologação em Docker/Postgres/DNS/VPS reais.
- A base NH v118 ainda exige migração Prisma versionada e aprovada antes de uso real.
- Uma reserva não libera uma URL funcional automaticamente.

VALIDAÇÃO WINDOWS:
Extraia a pasta gestao-software-v106 separadamente da instalação atual.
No PowerShell dentro da pasta nova:
  powershell -ExecutionPolicy Bypass -File .\TESTAR_CLOUD_V106_WINDOWS.ps1 -SomenteAnalise
  powershell -ExecutionPolicy Bypass -File .\TESTAR_CLOUD_V106_WINDOWS.ps1
A segunda execução usa Docker/Postgres DESCARTÁVEIS com projeto próprio.

VALIDAÇÃO AGENTE (LINUX, NÃO WINDOWS):
  python3 -m unittest discover -v -p 'test_*.py'
  python3 provisioner_agent.py --check
O --check requer env de imagens por digest e token do worker configurados e NÃO cria recursos.

O relatório de revisão está incluído no pacote completo.
