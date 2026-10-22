# Gestão Software v101 — Central de implantação guiada (pré-produção)

> **Estado: implementação-base para homologação.** Nenhuma infraestrutura foi contratada ou criada. O fluxo completo de deploy e pagamento em ambiente real depende da configuração autorizada de Asaas, registry, DNS, Traefik e agente. Não habilitar `AUTO_PROVISION_ENABLED` antes dos testes de segurança e recuperação.

## O que está incluído

1. Aba **Implantação guiada** do administrador, com seleção da empresa cadastrada.
2. Questionário de capacidade: funcionários, sessões simultâneas, vendas e cobranças mensais, quantidade de batidas de ponto e tamanho/retenção das evidências, reconhecimento facial, alta disponibilidade e provedor.
3. Recomendações indicativas de vCPU/RAM/armazenamento; categorias START, BUSINESS e ENTERPRISE. **Não** constitui prova de capacidade ou SLA.
4. Salvamento da análise exigindo os **mesmos módulos contratados** no cadastro.
5. Travas de autorização: contrato assinado por cliente e Gestão, fatura do mês mais recente confirmada no próprio Asaas e módulos ativos.
6. Geração manual da cobrança via Asaas (somente após configurar o provedor), com registro local e sem tentativa automática de recriação após falha incerta.
7. Verificação direta do pagamento na API do Asaas; o status "PAID" cadastrado manualmente **não libera deploy**.
8. Fila por empresa, auditoria dos eventos e agente autenticado, com estados QUEUED → PROVISIONING → VERIFYING → ACTIVE / FAILED.
9. Publicação do link somente após consulta externa real a `https://DOMINIO/api/health/tenant` por HTTPS e comparação da identidade UUID do cliente.
10. Agente inicial de VPS gerenciada (**somente START**) no kit separado de provisionamento.

## Como executar a versão de homologação

1. Faça backup do PostgreSQL da Gestão; mantenha a pasta/nome do projeto Compose e seu volume persistente.
2. Atualize a Gestão por **cópia nova** para conferir alterações e variáveis. Não execute `docker compose down -v`.
3. Copie `.env.example` para `.env` e configure credenciais e segredos próprios. As configurações de gateway podem ficar em branco para testar apenas dimensionamento.
4. Execute `docker compose up -d --build` e verifique `docker compose logs --tail=150 backend`.
5. Acesse `http://localhost:3100/admin/` → **Implantação guiada**. Cadastre a empresa no painel Empresas antes de dimensionar.
6. Após selecionar os módulos do contrato, execute **Analisar capacidade**, **Salvar análise** e **Atualizar situação**.
7. Para testagem Asaas, configure apenas **Sandbox** (`https://api-sandbox.asaas.com/v3`) e uma chave Sandbox. Gere uma cobrança; realize pagamento de testes e verifique o status. A versão não considera uma fatura paga por mera alteração manual.
8. Para instalar em VPS já configurada, consulte o README do kit agente e habilite explicitamente as duas proteções: `AUTO_PROVISION_ENABLED=true` (apenas depois dos testes) e `ALLOW_RESOURCE_CREATION=yes` no agente isolado. Revise custos, imagens, certificados e domínio antes da ativação.

### Variáveis da Gestão

| Variável | Finalidade |
|---|---|
| `GESTAO_ASAAS_API_KEY` | Chave secreta do Asaas (Sandbox durante homologação). |
| `GESTAO_ASAAS_BASE_URL` | Apenas `https://api-sandbox.asaas.com/v3` ou `https://api.asaas.com/v3`. |
| `GESTAO_ASAAS_WEBHOOK_SECRET` | Segredo com pelo menos 32 caracteres, validado no cabeçalho `asaas-access-token`. |
| `PROVISIONER_WORKER_TOKEN` | Chave aleatória com 32+ caracteres, exclusiva do agente. |
| `AUTO_PROVISION_ENABLED` | Desativado por padrão. |

### Limites de funcionalidade conhecidos

- **AWS, Render e VPS do cliente**: sem adaptadores de criação de recursos. A escolha entra no dimensionamento, mas o botão de solicitar implantação rejeitará provedores sem conector. Não afirme que deploy ocorre automaticamente nesses casos.
- **Business/Enterprise**: exige revisão, testes de pico, armazenamento, segurança, custo e SLO. Nenhum recurso é criado automaticamente.
- **VPS gerenciada START**: requer agente em servidor Linux, registry com imagens, Traefik/TLS, domínio com DNS apontado, back-up e volumes; sem esses itens o job não fica ACTIVE.
- **Versão-base do NH**: parcial e necessita remoção dos artefatos de marca, PDFs, seeds legados e execução de testes E2E de todos os módulos antes de qualquer cliente externo. Nunca provisionar automaticamente imagens antigas da Novo Horizonte em novo cliente.
- **Financeiro**: o checkout inicial cria cobrança convencional; cobrança recorrente por Pix Automático ou débito automático requer integração específica.
- **Credenciais dos clientes**: o agente gera senhas isoladas mas ainda requer processo seguro de entrega inicial/reset ao responsável; não exibir segredos no painel público.
- **Webhooks e autenticação**: executar testes adversariais, concorrência e auditoria antes de produção. A confirmação de domínio por health-check deve incluir checagens de segurança de rede e DNS.
- **Testes**: sem Docker/Maven nesta sessão; realizar build, integração PostgreSQL, migrações, infra smoke test e teste de carga nos ambientes autorizados.

## Critérios mínimos de aceite de implantação

- O domínio possui HTTPS válido e aponta somente para o ambiente daquele cliente.
- A API confirma o UUID daquele tenant (`/api/health/tenant`), sem informações confidenciais.
- Os volumes PostgreSQL e anexos são exclusivos e com backup e restauração testados.
- Nenhuma credencial ou arquivo original da Novo Horizonte existe no novo ambiente.
- As regras de módulos contratados são impostas também nas APIs, não só na navegação.
- Cada login (admin, consultor, associado, financeiro, ponto) funciona e exige atualização da senha inicial.
- As configurações de logotipo e cores mudam pelo painel da Gestão sem rebuild da imagem.
- Recebimentos e webhooks de teste conferem fatura correta e são idempotentes.
- O botão para publicar URL só libera após verificação real, e o rollback é documentado.


## Complemento v102

- Navegação orientada em seis etapas, mensagens da próxima ação e validações do dimensionamento.
- Criação da empresa volta automaticamente à implantação guiada.
- Provedores sem adaptador implantável são marcados explicitamente e impedem deploy.
- `TENANT_TEMPLATE_CERTIFIED=false` impede criar implantação enquanto o template contiver dados, marca e migrações de cliente anterior. Não confundir com capacidade nominal do agente.
- `PROVISIONING_HOST_SUFFIX` restringe domínios aceitos para provisionamento e saúde.
- A chave mestra `PROVISIONER_WORKER_TOKEN` permanece apenas na Gestão; cada VPS usa token HMAC por UUID (ver kit agente).
- O agente exige imagens imutáveis `@sha256`, CNPJ real, domínio permitido e segredos independentes por empresa.
- Uma auditoria bloqueante identifica nomes NH legados e documentos/contas de teste.
- Teste de sintaxe Java, JS, YAML e testes unitários locais incluídos separadamente.

**Não habilite a criação de recursos pagos enquanto a auditoria white-label acusar ocorrências.**
