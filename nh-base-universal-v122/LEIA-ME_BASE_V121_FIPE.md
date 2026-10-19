# Base Universal NH v121 — integração da versão NH v83.126 (FIPE)

## Inclusão efetiva

- Pesquisa local de versões FIPE no painel de Análise: modelo, ano, categoria e veículo 0 km; seleção manual se várias versões; preenchimento automático quando correspondência única; alternativa manual quando não há dados.
- Endpoints `/api/analysis/fipe/versions`, `/api/admin/fipe/status`, `/api/admin/fipe/prices` e `/api/admin/fipe/sync` com autenticação; valor/código da versão verificados novamente no backend antes de gravar.
- Código, mês/ano de referência e origem FIPE na cotação e no dossiê; vínculo temporário preservado quando uma alteração comercial depende do aceite do associado.
- Nova base tenant V5 e V6: tabelas de snapshots FIPE e vínculo FIPE, sem importar catálogos ou dados comerciais da NH. **V1–V4 intocadas**.
- Gestão v107, importação segura CSV, identidade white-label e contas da empresa preservadas.

## Importante: limites desta mesclagem

A versão operacional NH v83.126 também contém alterações acumuladas de contrato, CEP, regras regionais, fluxo de supervisão e termo de ciência de peças que dependem de outros componentes e de revisão jurídica/comercial. **Esses recursos NÃO foram todos mesclados** nesta entrega: não é seguro transplantar migrações comerciais históricas V85–V94 para um banco universal.

### Configuração FIPE

No modo `tenant`, o sincronizador começa **DESLIGADO** (`FIPE_DATASET_SYNC_ENABLED=false`) para não gerar importações pesadas simultâneas em dezenas de VPS. Após testes de banco/rede e política de uso do dataset, configure `FIPE_DATASET_SYNC_ENABLED=true` no tenant escolhido. A sincronização usa os releases públicos de `fipex-labs/dataset`, Git LFS e downloads HTTP(S); exige acesso de saída, espaço e validação SHA-256. Sem snapshot concluído, os campos FIPE são preenchidos manualmente e nenhum código é inferido.

### Homologação obrigatória

1. Compilar `mvn -f backend/pom.xml test` com JDK21/Maven e `mvn -f backend/pom.xml -Ptenant package` (se o perfil estiver disponível).
2. Executar em PostgreSQL 16 descartável `spring.profiles.active=tenant`: Flyway deve executar V1–V6 sem catalogar valores comerciais de outra empresa. Validar Hibernate/JPA e startup.
3. Habilitar sincronização somente no ambiente de teste, importar release FIPE e verificar tabela completa, checksum, tipos e atualização mensal.
4. Testar análise com uma/múltiplas versões, nenhuma FIPE, 0 km, edição manual, alteração de veículo, proposta com aceite pendente e gravação do código em cotação/PDF.
5. Testar isolamento por empresa e importação CSV v120. Fazer backup e restauração; conferir que nenhum domínio/credencial NH foi inserido.

**Não aplicar na NH operacional, nem em produção de clientes reais, antes de homologação.**
