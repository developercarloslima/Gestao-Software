# NH V83-107 - Controle Total Gestão Software

Base: V83-106 Termo de Aceite Independente.

Esta versão inclui as funcionalidades financeiras/Asaas/Área do Associado da V83-96 e reforça o controle central da Gestão Software.

Principais regras:
- empresa ACTIVE/TRIAL: funciona conforme módulos habilitados;
- empresa SUSPENDED/CANCELLED: API operacional bloqueada com 403 COMPANY_DISABLED;
- módulo desligado: rotas do módulo bloqueadas com 403 MODULE_DISABLED;
- configuração confirmada fica persistida no banco da NH;
- indisponibilidade temporária do Control Plane não altera a última licença confirmada;
- Apontamento Digital consulta a licença através do backend principal da NH;
- branding/logo/PDF são administrados pela Gestão Software.

Render: veja RENDER_DEPLOY.md e render.yaml.
