# Gestão Cloud v104 — Agente v119

Este pacote estende o agente v118 e **não** cria recursos automaticamente sem `ALLOW_RESOURCE_CREATION=yes`.

- Identificação: `WORKER_VPS_ID` e `PROVISIONER_WORKER_TOKEN` (HMAC específico da VPS; **nunca** a chave mestra).
- O agente precisa acessar `GESTAO_API_URL` por HTTPS válido. O endpoint de heartbeat é `POST /api/agent/cloud/heartbeat`.
- Envie métricas de CPU, RAM e disco pelo `--heartbeat-only` sem criar serviços. Ao funcionar como daemon, transmite a cada ~45 s.
- Métricas são indicativas. A Gestão marca offline com mais de 3 min sem heartbeat.
- Reservas de subdomínios são persistidas no banco da Gestão; certificados TLS e DNS requerem infraestrutura configurada e domínio controlado pelo operador.
- Para **provisionar** ainda são exigidas imagens com digest, Traefik, contrato assinado, pagamento confirmado, template certificado e checklist.
- Nunca armazene credenciais do agente no front-end ou em logs.
- Utilize uma VPS Linux de homologação e um banco de dados descartável antes de habilitar implantação real.

Teste sem instalação:

```bash
python3 provisioner_agent.py --check
python3 provisioner_agent.py --heartbeat-only
```

A opção `--check` exige todas as variáveis de imagens e valida Docker, mas não instala recursos. `--heartbeat-only` também exige configuração do servidor, pois executa a validação do agente. Para usar como daemon, instale uma unidade systemd sem permissões de root quando possível e use Docker com conta segregada, lembrando que acesso ao socket Docker concede privilégios elevados.

**Não ligue** `CLOUD_ROUTING_ENABLED=true` na Gestão até existirem heartbeat, domínio reservado e testes de integração. Esse parâmetro não substitui `TENANT_TEMPLATE_CERTIFIED`, que continua false.
