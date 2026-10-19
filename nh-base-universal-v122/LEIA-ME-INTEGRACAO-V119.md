# Relatório de integração — Base Universal NH v119 (candidata)

## Referências
- **NH operacional fornecido:** `nh-checklist-completo-v83-113-previews-responsivo(1).zip` (1010 entradas no arquivo).
- **Base universal anterior:** `nh-base-universal-v118-implantacao-guiada.zip` (674 entradas).
- **Base gerada:** v119 candidata, derivada da v118 e com incorporação seletiva.

## O que foi incorporado e está no pacote
1. **Pré-visualizações de anexos** dos painéis ADM, Analista e Supervisão, com cache de 18 itens pequenos, limite de 3 carregamentos concorrentes, 3 tentativas e fallback amigável para download.
2. **Ajuste responsivo** do banner de credencial e ações de Termo em telas pequenas.
3. **Aceite WebAuthn** com verificação obrigatória do usuário e sem tratar PIN/biometria como método de aceite separado; identidade exibida continua parametrizada.
4. **Termo de Responsabilidade** nos painéis ADM, Analista e Supervisão: consultar status, preparar, enviar, copiar, abrir e baixar PDF antes ou depois do aceite.
5. **Registro de auditoria** do aceite: status, data, IP, localização (quando capturada), dispositivo, origem e hashes, sem apresentar aceite herdado como se fosse assinatura nova.
6. **Atualização do dossiê final** no aceite do termo independente, conforme fluxo da versão fornecida (exige teste funcional com anexos reais antes de produção).
7. **Nova migration tenant V3** acrescenta apenas duas colunas de metadados; não carrega aceites, usuários ou tabelas comerciais do banco NH.
8. **PDF universal** adota nome/CNPJ por configuração, sem carregar logotipo da NH e sem cláusulas exclusivas de outra empresa: exige cláusulas jurídicas por tenant; prontidão bloqueia a empresa enquanto não forem configuradas.
9. **Preservados da v118**: portais financeiros Asaas, licenciamento Gestão Software, isolamento tenant, seed seguro de Ponto e Flyway tenant separado.

## O que NÃO foi copiado automaticamente
- Migrações legadas **V85, V86 e V87** da linha operacional: os números conflitam com V86 financeiro/V89 e o backfill V87 é próprio do histórico NH.
- Regra econômica/completa de terceiros com valores **R$ 35/50/100 mil** e códigos de planos fixos. Na base v118 ainda há regras antigas 50/100 mil em módulos; **não estão certificadas como regras genéricas**. É obrigatória a parametrização completa em serviço/DTO/contratos/relatórios antes de liberar o módulo comercial para novo cliente.
- Logo, favicon, regulamento, imagens da marca e contas específicas da NH, arquivos compilados `dist`, e qualquer senha-padrão da linha operacional.
- Versões do NH Ponto fornecidas que desativariam o bloqueio por licença ou restaurariam senhas iniciais compartilhadas. A base v118 mais segura foi mantida.
- Backfill de aceite legado `DOSSIER_ACCEPTANCE`: reservado a um processo supervisionado para um banco histórico, nunca em banco vazio de nova empresa.

## Testes efetivamente executados neste ambiente
- Auditoria estática white-label tenant: **zero ocorrências bloqueadas**.
- `ci/test_release_static.py`: **passou**, incluindo sintaxe de 33 arquivos JS e validações das migrações tenant V1–V3.
- `ci/test_merge_v119.py`: **26 verificações passaram** (botões, horários/estados, isolamento estrutural, preservação financeira e controle de cláusulas).
- `ci/test_ux.py`: **passou em Chromium** para o assistente de implantação a 390, 768 e 1366 px, **com APIs simuladas**; sem erro JavaScript e sem rolagem horizontal.
- Teste novo da página de aceite no navegador com API simulada: **inconclusivo** por bloqueio de abertura de loopback e injeção de estilos no ambiente. Não foi aprovado e não está incluído como teste obrigatório.

## NÃO executado / motivos de bloqueio para produção
1. **Maven `-Ptenant clean verify`**: Maven e dependências Java externos não estão disponíveis nesta sessão.
2. **Flyway V1–V3 em PostgreSQL real**: PostgreSQL e Docker não estão disponíveis nesta sessão.
3. **Baseline Prisma do NH Ponto e migração real**: ainda exige gerar, revisar e executar a migration inicial com banco descartável.
4. **Integração WebAuthn verdadeira** com HTTPS/RP ID, Safari/Chrome, autenticação e dossiê pós-aceite.
5. **Asaas sandbox, DNS, implantação em duas VPS de laboratório, backup-restauração e isolamento**, com teste de carga.
6. **Motor comercial universal**: ainda precisa eliminar preços e coberturas fixas em cotação, snapshots, documentos e edição administrativa para evitar herança de regras do cliente histórico.
7. **Revisão jurídica** do termo e do regulamento por cada empresa.

## Política de implantação
**NÃO aplicar na NH existente, NÃO apontar para volumes/banco da NH, NÃO ativar provisionamento automático.**
A v119 está pronta para **homologação em banco novo e isolado**, mas **não está aprovada para produção**. Mantenha `TENANT_TEMPLATE_CERTIFIED=false` e `ALLOW_RESOURCE_CREATION=false` até concluir TODOS os gates.

## Como homologar
1. Extraia v119 em pasta própria.
2. Rode `python ci/auditar_template.py --root . --tenant-runtime`, `python ci/test_release_static.py`, `python ci/test_merge_v119.py`.
3. Em runner com Maven/Node/Docker/PostgreSQL, rode workflow `.github/workflows/validacao-integracao.yml` e gere baseline Prisma versionada.
4. Teste com tenant fictício e banco totalmente novos. Configure `TENANT_RESPONSIBILITY_TERM_CLAUSES` com texto aprovado para aquela empresa.
5. Refaça o teste de saque/financeiro, cotação, vistoria, termo WebAuthn, Ponto e restauração; só promova após revisão humana dos resultados.
