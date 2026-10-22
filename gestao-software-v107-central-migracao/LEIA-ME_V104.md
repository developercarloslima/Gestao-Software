# Gestão Software v104 — Gestão Cloud

**Base:** Gestão Software v103, com todos os módulos e contratos existentes preservados no código. Esta é uma atualização aditiva da central de hospedagem.

Novidades: aba Gestão Cloud, subdomínios exclusivos por empresa, vínculo a VPS, agente de métricas, estados de implantação, controles contra alterações manuais divergentes e migração Flyway V7.

**Cuidado:** não é atualização automática de um único clique. Leia `README_GESTAO_CLOUD_V104.md` antes de migrar o PostgreSQL. Em homologação, mantenha `CLOUD_ROUTING_ENABLED=false`, `AUTO_PROVISION_ENABLED=false` e `TENANT_TEMPLATE_CERTIFIED=false`.

## Arquivos para aplicar
- Projeto completo Gestão v104: substituir código em uma pasta nova, preservando banco e `.env` da instalação local.
- Kit agente VPS v119: atualizar **somente** numa VPS autorizada e após testes. O script transmite métricas reais para a central.
- Base NH v118: não altera a instalação atual da Novo Horizonte. O tenant universal continua aguardando certificação para novos clientes.

Para encontrar uma falha de banco em homologação:
```powershell
docker compose logs --tail=250 backend
```

Toda implantação continua exigindo contrato, pagamento real validado pelo Asaas, URLs HTTPS, isolamento e `readyForCustomers=true`.
