# Asaas Sandbox por empresa-cliente — Gestão Software

## Princípios

- Cada empresa-cliente usa **sua própria conta Asaas Sandbox**, sua própria API Key, saldo e token de webhook. Nunca usar a chave Asaas do Gestão central para cobrar associados de uma empresa-cliente.
- O Asaas do Control Plane (faturas do SaaS Gestão) é independente do Asaas de cada tenant (adesões, mensalidades, pagamentos de consultores).
- A API Key deve existir **somente no backend** (Render Environment ou `secrets.env` privado da VPS). Nunca no frontend, banco sem criptografia, repositório ou conversa.
- A configuração inicial é **fail-closed**: `ASAAS_ENABLED=false`. Sem chave própria e webhook homologado, não emitir nem transferir.
- Sandbox não movimenta dinheiro real. Não usar chave ou URL de produção nesse ambiente.

## Variáveis por backend de tenant

| Variável | Valor inicial | Regra |
| --- | --- | --- |
| `FINANCIAL_PROVIDER` | `ASAAS` | Cada tenant seleciona seu próprio provedor. |
| `ASAAS_ENABLED` | `false` | Alterar para `true` somente após configurar os dois segredos e testar. |
| `ASAAS_BASE_URL` | `https://api-sandbox.asaas.com/v3` | Sempre Sandbox na homologação. |
| `ASAAS_API_KEY` | vazio | Chave Sandbox **da própria empresa**. |
| `ASAAS_WEBHOOK_TOKEN` | gerado individualmente na VPS / informado no Render | Token independente da API Key, configurado também no painel Asaas. |
| `FINANCIAL_PAYMENT_FEE` | `2.00` | Tarifa repassada na cobrança conforme regra da empresa. |
| `FINANCIAL_WITHDRAWAL_FEE` | `2.00` | Tarifa informada antes do saque do consultor. |

Para uma empresa criada em VPS pelo agente, o arquivo privado `secrets.env` do **respectivo tenant** já terá os campos e um token de webhook aleatório. Preencha a chave da conta da empresa, revise tarifas e só então habilite. Reinicie **apenas o backend desse tenant**. Contas/segredos dos demais tenants não devem ser alterados.

Para uma empresa criada no Render, informe os segredos em **Environment** do serviço de backend da própria empresa. Os arquivos `render.yaml` pedem credenciais com `sync: false` apenas na criação inicial do Blueprint. Em serviços existentes, o preenchimento deve ser manual pelo Dashboard. Execute um deploy do backend para ativar mudanças.

## Webhook da empresa

No painel Asaas Sandbox dessa empresa, configure uma única URL HTTPS, sem redirecionamento:

```
https://DOMINIO_DA_EMPRESA/api/public/financial/asaas/webhook
```

Use como token de autenticação **exatamente** o valor de `ASAAS_WEBHOOK_TOKEN` do backend dessa empresa. Não use a API Key como token de webhook.

Escute os eventos de cobrança necessários à conciliação (incluindo `PAYMENT_RECEIVED`, `PAYMENT_CONFIRMED`, `PAYMENT_OVERDUE`, `PAYMENT_REFUNDED`) e os de transferência `TRANSFER_DONE`, `TRANSFER_FAILED`, `TRANSFER_CANCELLED`. O backend valida `asaas-access-token` e deduplica eventos pelo identificador recebido. Consulte a lista atual de eventos no próprio Asaas.

## Homologação obrigatória por empresa

1. Crie/acesse a conta **Sandbox da empresa** e gere a API Key no painel Asaas.
2. Configure `ASAAS_API_KEY` e `ASAAS_WEBHOOK_TOKEN` no backend exclusivo da empresa; configure o webhook no Asaas.
3. Defina `FINANCIAL_PROVIDER=ASAAS` e `ASAAS_ENABLED=true`. Faça o deploy/restart do backend.
4. Confira `GET /api/public/financial/status` no domínio da empresa: `provider=ASAAS`, `configured=true`, `paymentsEnabled=true`.
5. Faça um **novo aceite digital de teste** com associado e cotação válidos. Verifique a geração da adesão, boleto, Pix e 12 cobranças futuras individuais, sem cobranças duplicadas.
6. Simule o pagamento no Sandbox e confira a baixa via webhook, o histórico e o painel do associado.
7. Com os dez boletos de fechamento quitados de teste, verifique a comissão, uma solicitação de saque Pix, a aprovação do Financeiro, a transferência no Sandbox e o webhook de conclusão/falha.
8. Faça testes de duplicação de webhook e confira se a mesma cobrança/comissão/transferência não é contabilizada duas vezes.
9. Somente após passar por essa lista avalie a migração **individual** para produção, com outra chave/URL e aprovação operacional específica.

**Atenção às cobranças antigas:** ativar a financeira depois do aceite não cria automaticamente uma nova cobrança de adesão para todos os históricos. Revise pendências e use a reconciliação financeira do veículo/contrato, conferindo antes a existência de títulos no Asaas para evitar duplicidade.

## Fontes oficiais

- https://docs.asaas.com/docs/sandbox
- https://docs.asaas.com/docs/api-keys
- https://docs.asaas.com/docs/sobre-os-webhooks
- https://docs.asaas.com/docs/webhook-para-transferencias
- https://render.com/docs/configure-environment-variables
- https://render.com/docs/blueprint-spec
