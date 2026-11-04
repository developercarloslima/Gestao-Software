# Asaas Sandbox — conta principal Gestão + subcontas das empresas-clientes

## Titularidade e isolamento financeiro

- **A conta principal Asaas da Gestão Software (CNPJ)** cria e administra a vinculação das subcontas. Suas cobranças SaaS (adesão/mensalidade das empresas-clientes) usam a conta principal e são separadas das cobranças dos associados.
- **Cada empresa-cliente possui uma subconta Asaas não-BaaS vinculada**, com sua própria carteira, saldo, pagadores, cobranças, transferências e credencial. O dinheiro arrecadado de associados pertence **somente à empresa-cliente**, fica na subconta dela e é recebido/transferido conforme a titularidade e os dados bancários configurados no próprio Asaas.
- Os saques/comissões Pix de consultores, após aprovação no financeiro **da empresa-cliente**, utilizam apenas o saldo/chave da subconta da empresa correspondente.
- **Não configurar split automático** ou repasse de recebíveis de associados à conta Gestão. A assinatura SaaS que a empresa paga ao Gestão é uma cobrança **separada**, feita pela conta principal Gestão.
- A empresa-cliente tem acesso **direto ao painel Asaas**: recebe convite de ativação, define senha e envia documentos pelo fluxo não-BaaS.
- A criação de subcontas via API exige conta-pai CNPJ; subcontas podem passar por análise e limites regulatórios. Conferir taxas de criação do contrato Asaas.

## Criar subconta pelo Control Plane

1. No Control Plane, cadastre a empresa com **CNPJ** e e-mail válido do seu responsável.
2. Em **Empresas-clientes → Ver empresa → Subconta Asaas**, use **Cadastrar subconta da empresa**.
3. Informe o celular, faturamento mensal estimado, tipo jurídico, CEP, logradouro, número e bairro. Confirme os dados do titular antes de enviar.
4. O backend envia `POST /v3/accounts` usando **somente a credencial da conta-pai**. A empresa recebe a sua própria carteira `walletId` e API key específica. A API key nunca é exibida no navegador nem salva em claro: ela é criptografada com AES-256-GCM no banco central.
5. A conta é registrada como `PENDING_ACTIVATION`: o titular faz ativação/validação documental **no Asaas**. Em Sandbox, as comunicações de teste podem ser encaminhadas ao e-mail da conta-raiz.
6. O provisionamento financeiro **não está automático no tenant**: o operador deve vincular a chave exclusiva da subconta ao backend da empresa, configurar webhook e validar a aprovação antes de habilitar cobranças. O registro no Control Plane, por si só, **não ativa** `ASAAS_ENABLED`. Isso evita receber valores na carteira errada.

A API usa uma reserva única por empresa. Se a requisição ao Asaas ficar com resultado incerto, o cadastro entra em `REVIEW_REQUIRED` e **não deve ser repetido automaticamente**: concilie primeiro com o Asaas para não abrir duas contas.

## Segredos do servidor central

| Variável | Uso |
| --- | --- |
| `GESTAO_ASAAS_API_KEY` | Chave da **conta-pai CNPJ** no Asaas. |
| `GESTAO_ASAAS_BASE_URL` | `https://api-sandbox.asaas.com/v3` na homologação. |
| `GESTAO_ASAAS_SUBACCOUNT_ENCRYPTION_KEY` | Base64 de **32 bytes aleatórios**, gerado e armazenado somente no Render/VPS. Protege as API keys das subcontas no banco central. Fazer backup seguro desta chave; perdê-la impede recuperar as credenciais cifradas. |

A chave de criptografia deve ser exclusiva deste ambiente (Sandbox vs Produção), nunca ir para GitHub, documentação, frontend ou logs.

## Segredos por backend da empresa (tenant)

| Variável | Valor inicial | Regra |
| --- | --- | --- |
| `FINANCIAL_PROVIDER` | `ASAAS` | Provedor financeiro do tenant. |
| `ASAAS_ENABLED` | `false` | Habilitar somente após configurar chave, webhook e homologar. |
| `ASAAS_BASE_URL` | `https://api-sandbox.asaas.com/v3` | Nunca misturar ambientes. |
| `ASAAS_EXPECTED_WALLET_ID` | carteira do tenant | Obrigatoria em Sandbox: a API compara via `GET /v3/wallets` e bloqueia a emissao se a chave for de outra conta. |
| `ASAAS_API_KEY` | vazio | **Chave da subconta da empresa**, nunca da conta principal. |
| `ASAAS_WEBHOOK_TOKEN` | token aleatório exclusivo | Igual ao token configurado no webhook desta subconta. |
| `FINANCIAL_PAYMENT_FEE` | `2.00` | Conferir a tarifa contratual e política de cobrança. |
| `FINANCIAL_WITHDRAWAL_FEE` | `2.00` | Conferir a tarifa e política para saques de consultores. |

Em VPS com agente, os dados residem no `secrets.env` privado de **cada tenant**, não em uma variável compartilhada. No Render, a chave deve residir somente na seção Environment do serviço backend **daquela empresa**; em Blueprints existentes, `sync: false` não preenche valores retroativamente. Somente habilite `ASAAS_ENABLED=true` depois de conferir a aprovação cadastral e o webhook.

## Webhook e homologação

Endereço de webhook da **subconta da empresa**:

```
https://DOMINIO_DA_EMPRESA/api/public/financial/asaas/webhook
```

O token HTTP recebido em `asaas-access-token` deve ser comparado com o `ASAAS_WEBHOOK_TOKEN` exclusivo do tenant. Não use a API key como token de webhook. Acompanhe eventos de pagamento (como `PAYMENT_RECEIVED`, `PAYMENT_CONFIRMED`, `PAYMENT_OVERDUE`, `PAYMENT_REFUNDED`) e transferência (`TRANSFER_DONE`, `TRANSFER_FAILED`, `TRANSFER_CANCELLED`). Confirme deduplicação pelo ID do evento.

1. Confirme no Asaas o titular correto e a aprovação da subconta.
2. Vincule a chave da subconta **somente** ao tenant dela; configure webhook.
3. Teste `GET /api/public/financial/status` do tenant para confirmar `configured=true`, `paymentsEnabled=true`.
4. Faça aceite de teste com associado e verifique adesão, boleto/Pix e faturas futuras, sempre na **subconta da empresa**.
5. Simule liquidação pelo Sandbox, valide webhook, baixa e acesso do associado.
6. Simule comissão e transferência Pix do consultor, conferindo débito **apenas** da subconta da empresa.
7. Confira que faturas SaaS Gestão aparecem na **conta-pai**, sem misturar recebíveis.
8. Teste isolamento entre duas empresas: uma não pode ver clientes, pagamentos, saldo ou transferências da outra. Teste webhook duplicado e idempotência.
9. Migre cada subconta a produção somente após aprovação regulatória e validação de titularidade.

**Aviso de histórico:** não recrie automaticamente boletos já emitidos antes de habilitar Asaas. Reconcilie os títulos existentes antes de voltar a cobrar.

## Fontes oficiais

- https://docs.asaas.com/reference/criar-subconta
- https://docs.asaas.com/docs/criacao-de-subcontas
- https://docs.asaas.com/docs/detalhamento-do-fluxo-de-aprovacao-de-subcontas
- https://docs.asaas.com/docs/sobre-os-webhooks
- https://render.com/docs/configure-environment-variables
