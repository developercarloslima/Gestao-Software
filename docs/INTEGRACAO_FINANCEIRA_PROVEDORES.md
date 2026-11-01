# Integração financeira — Gestão Software e Base Universal v122

## Separação dos dois financeiros

| Origem | Titular da cobrança | Uso | Fonte de dados |
| --- | --- | --- | --- |
| Control Plane Gestão | Empresa contratante do SaaS | Mensalidades, faturas, inadimplência, VPS, despesas e resultado dos sócios | Banco do Gestão, `platform_company_invoices` |
| Base Universal | Associado da empresa cliente | Adesão, boletos, Pix, recorrência, comissões, saques e DRE da empresa | Banco **exclusivo** da empresa cliente; `financial_charges` |

Uma empresa nunca consulta faturas dos demais clientes. A Gestão nunca soma o dinheiro dos associados às receitas próprias do SaaS.

## Provedora a escolher

O código já possui adaptadores e seleção explícita. **Não basta colar uma chave de qualquer instituição:** após escolher a financeira, deve ser implementado o adaptador da API e homologada a semântica de seus eventos. A infraestrutura e as telas não precisam ser refeitas.

| Contexto | Variável | Padrão de homologação | Adaptador atual | Indicador de conexão |
| --- | --- | --- | --- | --- |
| Gestão | `GESTAO_FINANCIAL_PROVIDER` | `NONE` | `AsaasCheckoutService` (`ASAAS`) | `GET /api/platform/finance/provider` (autenticado) |
| Base | `FINANCIAL_PROVIDER` | `NONE` | `AsaasClient` (`ASAAS`) | `GET /api/public/financial/status` (sem dados pessoais) |

`NONE` bloqueia novas operações financeiras externas, mesmo se existir credencial Asaas antiga. `ASAAS` somente é habilitado quando as credenciais e flags próprias estiverem válidas. Escolher um nome desconhecido **não** aciona outro provedor automaticamente.

Na Gestão, `GESTAO_ASAAS_API_KEY`, `GESTAO_ASAAS_BASE_URL` e `GESTAO_ASAAS_WEBHOOK_SECRET` continuam em uso apenas quando o provedor é Asaas. Na Base, `ASAAS_ENABLED`, `ASAAS_API_KEY`, `ASAAS_BASE_URL` e `ASAAS_WEBHOOK_TOKEN` funcionam da mesma forma. **Segredos somente no servidor e nunca no GitHub ou navegador.**

Tarifas cobradas da empresa/associado podem ser configuradas na Base por `FINANCIAL_PAYMENT_FEE` e `FINANCIAL_WITHDRAWAL_FEE`, com compatibilidade para `ASAAS_PAYMENT_FEE` e `ASAAS_WITHDRAWAL_FEE`. Não simular tarifa ou pagamento se a financeira estiver desconectada.

### Contratos Java para a próxima financeira

- Base: implementar `FinancialProvider` com `code`, `enabled`, `createCustomer`, `createCharge`, `createMonthlySubscription`, `refreshPayment`, `balance`, `transferPix`; registrar como bean Spring. O `FinancialProviderGateway` seleciona somente o código configurado.
- Gestão: implementar `PlatformPaymentProvider` com `code`, `ready` e `checkout`; o `PlatformPaymentGateway` escolhe o fornecedor para a fatura SaaS.
- Webhooks: traduzir os eventos da instituição em eventos internos **idempotentes** (IDs externos únicos), verificando autenticação/assinatura, conta/empresa, valor, moeda e referência antes de creditar, dar baixa, liberar implantação, pontuar fechamento ou liberar saque.
- Cadastros externos de clientes e assinaturas podem ter formato próprio do fornecedor. Ao trocar de financeira, mapear IDs da antiga sem apagar históricos e sem recriar cobranças com resultado externo incerto.
- Confirmar Pix/boletos/transferências pelo evento verificado ou consulta autenticada ao provedor. Não aceitar `paid=true` do navegador como prova de quitação.

## Segunda via de boleto (site principal da Base)

1. O associado acessa `/boleto.html` e autentica com CPF/CNPJ **e sua senha** da Área do Associado. Só CPF é insuficiente para expor valores, placas, nomes e links de cobrança.
2. O frontend usa `POST /api/auth/login`, sem guardar token na página, e consulta `GET /api/associate-portal/second-copy` com Bearer token temporário.
3. O backend associa a sessão ao **próprio** associado, consulta cobranças locais por veículo e apresenta vencimento, valor, status, linha digitável e URL HTTPS da financeira, quando já existentes.
4. Cobranças vencidas, pagas, canceladas ou sem documento emitido aparecem como **não disponíveis**; o usuário é encaminhado ao financeiro para atualização.
5. Não existe proxy `/api/baixar-boleto?url=...`: URLs arbitrárias poderiam gerar SSRF. A página abre o link HTTPS devolvido pela financeira diretamente no navegador.

Caso o associado precise trocar a senha no primeiro login, ele é encaminhado para `/associado/`. Sem provedora configurada, a tela apresenta situação verdadeira do banco (nenhuma emissão externa falsa), mas fica pronta para exibir boletos reais assim que o adaptador emitir/receber webhooks.

## Regras de receita e repasse preservadas

- Gestão: bruto recebido = mensalidades marcadas pagas; faturado = títulos da competência não cancelados; MRR = contratos ativos/teste.
- Resultado operacional = recebido − despesas operacionais − custo das VPS ativas. Impostos não estão automaticamente incluídos nesse indicador.
- Rateio Pedro/Carlos: inicialmente 50%/50%, editável somente na proporção de soma 100%; não é transferência bancária automática.
- Base: primeira cobrança recorrente **quitada** após adesão resolvida identifica o fechamento; saque de comissão é desbloqueado após **10 fechamentos quitados**, com reserva do saldo durante a operação e conciliação da financeira. Pix não pode ser marcado pago manualmente.
- A integração externa pertence ao módulo financeiro comercial; não muda as regras comerciais de cota/vistoria nem o histórico dos aceites já assinados.

## Testes antes de habilitar produção

- [ ] Escolher financeira e obter documentação oficial + credenciais de **sandbox** para cada empresa/conta.
- [ ] Homologar cadastro de pagador, boleto/linha digitável, Pix QR/copia-e-cola, recorrência, vencimento, estorno, cancelamento, tarifas e transferência Pix/TED.
- [ ] Validar webhooks assinados, duplicados, fora de ordem, concorrência e resultado incerto/timeout, com conciliação independente.
- [ ] Verificar que CPF de uma empresa ou token de outro associado não consulta uma cobrança alheia.
- [ ] Conferir DRE, inadimplência, repasse, tarifas efetivas e faturas faturadas vs efetivamente recebidas.
- [ ] Testar o percurso boleto → pagamento de teste → webhook → baixa → segundo boleto, sem criar cobrança duplicada.
- [ ] Validar URLs e domínio HTTPS final; ativar a financeira em produção somente depois dos testes e autorização da empresa.
