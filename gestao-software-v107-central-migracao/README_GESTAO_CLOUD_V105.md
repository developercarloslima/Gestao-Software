# Gestão Cloud v105 — Orquestrador inteligente para múltiplas VPS

## O que foi implementado

- **Seleção de servidor** pelo dimensionamento da empresa: reserva de CPU vCPU, RAM e disco; exclusão de servidores offline, sem métricas, sobrecarregados, cheios ou que tenham clientes antigos sem conciliação de recursos.
- Classificação determinística por best-fit e saúde do servidor. Região, latência, custo, IOPS e HA ainda precisam de conectores/metadados próprios; **não há garantia de que a VPS classificada em primeiro seja a melhor em todos os aspectos**.
- **Reserva transacional**: o banco bloqueia as linhas das VPS em ordem estável (`SELECT ... FOR UPDATE`) para evitar a reserva concorrente de capacidade inexistente. A tabela `gs_cloud_capacity_reservations` armazena os recursos de cada empresa.
- **DNS opcional Cloudflare**: gera um registro A exclusivo dentro da zona autorizada, somente depois do contrato e do pagamento verificados. Não sobrescreve registros de outra origem. Falhas de DNS não simulam sucesso.
- Estado dos jobs, proteção de URL e health-check independentes; ao ocorrer falha ou tempo excedido a capacidade permanece `HELD_FAILED` até revisão. Não se faz retry arriscado sem verificar a instalação anterior.
- **UI responsiva** com ranking, explicações de bloqueio, reserva automática e DNS.
- **Agente v120**: valida domínios com rigor, impede reaproveitar workspace de empresa ou domínio diferente, preserva credenciais em repetição idempotente e valida a VPS autorizada.

## Passos para atualizar, sem tocar na NH

1. Faça backup verificável do banco e dos arquivos persistentes da **Gestão** e mantenha uma cópia separada dos seus `.env`. NÃO envie `.env` para a internet ou para o chat.
2. Extraia v105 em **nova pasta**, separada da instalação atual. Não misture as pastas de migrações de versões diferentes.
3. Execute `powershell -ExecutionPolicy Bypass -File .\TESTAR_CLOUD_V105_WINDOWS.ps1` na pasta da v105. O projeto `gs-cloud-v105-homologacao` cria um PostgreSQL **descartável** sem portas publicadas e não reutiliza volumes atuais.
4. Confira logs e capturas da UI, configure 2 VPS de laboratório com agentes e imagens NH certificadas, valide DNS e TLS com subdomínio de teste.
5. Execute uma implantação real de empresa fictícia **somente no laboratório**, incluindo rollback, backup e restauração. Sem essa etapa, os testes locais não aprovam produção.
6. Após homologação, atualize o código e as migrações da Gestão em janela de manutenção com backup restaurável. Mantenha `AUTO_PROVISION_ENABLED=false`, `TENANT_TEMPLATE_CERTIFIED=false`, `CLOUD_ORCHESTRATOR_ENABLED=false`, `CLOUD_DNS_PROVIDER=MANUAL` até obter a aprovação final.
7. Ative os indicadores individualmente após evidência: orquestrador, DNS de teste, tenant certificado e por fim a automação de produção. Não habilite todos de uma vez.

### Novas APIs (acesso do administrador)

- `GET /api/platform/cloud/empresas/{uuid}/recomendacao` — pontuação e motivos para cada VPS.
- `POST /api/platform/cloud/empresas/{uuid}/reservar-automaticamente` com `{"slug":"cliente"}` — reserva VPS e subdomínio, bloqueada por feature flag.
- `POST /api/platform/cloud/empresas/{uuid}/configurar-dns` — Cloudflare, mediante contrato/pagamento confirmados e credenciais de zona restritas.

### Flags, inicialmente desativadas

```
CLOUD_ORCHESTRATOR_ENABLED=false
CLOUD_DNS_PROVIDER=MANUAL
CLOUDFLARE_DNS_TOKEN=
CLOUDFLARE_ZONE_ID=
CLOUD_ROUTING_ENABLED=false
AUTO_PROVISION_ENABLED=false
TENANT_TEMPLATE_CERTIFIED=false
```

**Não existe implantação automática de VPS com estes valores, propositalmente.** O agente em cada VPS recebe HMAC individual derivado da chave mestra da Gestão; nunca use a chave mestra num agente. Crie token Cloudflare com escopo apenas DNS Edit na zona administrada, nunca token global. Registros próprios externos e DNS Route53 não são integrados nesta versão.

### Falha e reimplantação

O agendamento marca jobs travados (45 min em PROVISIONING e 24h em VERIFYING) como `FAILED`, marca DNS/URL da empresa como não liberados e retém reserva `HELD_FAILED`. Um operador deve verificar se há contêineres, banco e volumes antes de retomar. A mesma empresa pode renovar a reserva depois de revisar os recursos e tentar a implantação novamente, preservando credenciais idempotentes. **Nunca remova volumes para fazer um retry.**

### Limites de produto

- Nesta versão o agente autoriza apenas dimensão **START**; BUSINESS e ENTERPRISE continuam exigindo análise e ambiente dedicado.
- Falta validar a base universal NH v118 no mundo real (Maven, Prisma, migrações tenant, DNS, backup, S3 evidências, facial e testes de carga). Não ativar `TENANT_TEMPLATE_CERTIFIED` sem esses critérios.
- Hospedagem de várias VPS no mesmo domínio funciona por registros DNS específicos (ou proxy de borda planejado); wildcard para um único IP não distribui tráfico sozinho.
- A licença/assinatura e cobrança vêm da Gestão, mas DNS não é publicado antes da verificação independente do pagamento.
