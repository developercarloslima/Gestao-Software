# Base Universal NH v120 — Central de Importação Segura

## Objetivo

Permitir que um administrador **da própria empresa** traga seus associados via CSV, em etapas guiadas, **sem acesso ao banco nem intervenção técnica**. O banco da Gestão Software guarda empresas, contratos, cobranças da licença e infraestrutura; **os dados pessoais dos associados ficam no PostgreSQL isolado do respectivo tenant NH**, não no control plane.

Esta versão não oferece migração universal de qualquer sistema, nem garante recuperação de dados que não existam no arquivo fornecido. O sistema detecta campos desconhecidos e bloqueia a carga em vez de descartá-los silenciosamente.

## Fluxo do operador

1. No Gestão Software, abra **Importar dados**, selecione a empresa e clique **Abrir importação segura** (o endereço só aparece quando houver appUrl HTTPS registrada).
2. Faça login com o usuário ADMIN da **empresa**, não com o admin central da Gestão Software.
3. Em **/admin/importacao.html**, baixe o modelo CSV e exporte os clientes do sistema antigo em UTF-8.
4. Selecione CSV de até **2 MB ou 5.000 registros** e confirme o fundamento jurídico e a autorização administrativa.
5. Confira a prévia (documentos mascarados), contagem de válidos, duplicados já existentes e erros. Linhas inválidas impedem a confirmação: corrija o arquivo de origem e reenvie.
6. Confirme a gravação. O lote roda numa transação; clientes com mesmo CPF/CNPJ são preservados, não substituídos.
7. Confira o resultado e a lista **Clientes trazidos para esta empresa**. O original do lote é recuperável apenas pelo ADMIN, com acesso auditado.

## Modelo CSV

Cabeçalho UTF-8 separado por ponto e vírgula (ou vírgula):

```csv
nome;documento;email;telefone;nascimento;telefone2;cep;endereco;numero;complemento;bairro;cidade;uf;codigoexterno
```

Obrigatórios: `nome`, `documento` com CPF/CNPJ válido. Outros campos são opcionais. `nascimento` é `AAAA-MM-DD`; CEP usa 8 dígitos. Campos podem vir entre aspas e conter separadores. Um único CSV pode conter até 5.000 registros.

**Sem perda silenciosa:** cabeçalhos não suportados resultam em erro. O operador deve guardar a exportação original até a conciliação e tratar colunas especiais por importadores próprios.

## Segurança efetivamente implementada

- Endpoints somente quando `GS_TENANT_MODE=true`/perfil `tenant`. `/api/admin/importacao/**` exige usuário com `ADMIN` no backend do tenant.
- TLS/HTTPS na publicação via proxy. Em Nginx, upload limitado a 3 MB nessa rota, sem afetar uploads grandes de vistorias; a API impõe 2 MB.
- AES-256-GCM por empresa sobre o CSV arquivado no banco, com nonce aleatório e identificador do lote autenticado (AAD); a chave `IMPORT_AES_KEY_BASE64` vem de segredo externo, nunca do repositório.
- O agente VPS v122 gera uma chave de 32 bytes diferente para cada empresa nova e registra no `secrets.env` com permissão 0600. Em instalações preexistentes, configurar essa chave individualmente; sem chave, upload retorna 503, **sem fallback inseguro**.
- Hash SHA-256 do arquivo de origem, verificado antes de gravar os associados e antes de recuperar o original. Lotes repetidos por hash são bloqueados; confirmar o mesmo lote duas vezes não insere registros duplicados.
- Não cria senhas, usuários de portal, passkeys nem biometria a partir da planilha.
- Escrita **somente dos registros novos** em `associates`, com `ON CONFLICT DO NOTHING`, e trilha dos novos IDs em `tenant_customer_import_links`. Nenhum UPDATE/DELETE no cadastro original.
- Criação de todos os clientes e finalização do lote dentro de uma única transação PostgreSQL. A integração real em banco ainda exige homologação de rollback, paralelismo e recuperação.
- Original criptografado permanece no banco para reconciliação. Logs não recebem conteúdo CSV. Download do original exige ADMIN e registra o usuário em `tenant_customer_import_access_audit`.
- Registros importados aparecem em `/admin/importacao.html` e na API comercial existente da empresa, de acordo com as permissões de cada módulo.

## Segurança ainda necessária na infraestrutura

- Criptografia de disco e **backups criptografados** do PostgreSQL e do `secrets.env`, cópias fora da VPS, teste regular de restauração.
- Proteção de segredos (ideal: Vault/KMS, rotação e política de recuperação da chave), TLS interno conforme arquitetura, gestão de acessos e MFA.
- Regras LGPD: documentar controlador/operador, fundamento jurídico, instruções de tratamento, contrato e retenção/descarte; a autorização de um funcionário não substitui a base legal adequada.
- Monitoramento, limites de requisição por administrador e testes de carga. Não alimentar esta tela com arquivos não confiáveis que contenham macros/scripts.

## Limites explícitos

- **Incluído nesta entrega:** associados, CPF/CNPJ, contatos, nascimento, endereço e código externo de origem (rastreio). CSV.
- **Não incluído:** importação de veículos, vínculos de consultores, apólices/contratos, pagamentos, faturas, saldos financeiros, anexos, arquivos de imagem, usuários, hashes de senha, selfies e biometrias, histórico de ponto. Eles exigem mapeamento e reconciliação específicos **por sistema de origem** para preservar vínculos e integridade legal. Importações CSV não ativam cobranças Asaas nem criam logins de associados.
- Isolamento garantido **pelo banco PostgreSQL exclusivo por empresa** da Base Universal, e não por filtros de `tenant_id` numa instância compartilhada. Para futura arquitetura multi-tenant compartilhada é necessário refatorar controles de acesso e chaves.
- A ferramenta não pode prometer “perda zero” sem conferir também total de origem, campos não mapeados, duplicados, anexos, integridade de vínculos e backup restaurável.

## Migração e implantação

- **Somente no banco novo tenant**, Flyway `db/tenant/V4__secure_customer_import.sql`. Nunca aplique essa migração em bancos da NH operacional antiga.
- No agente: atualizar a imagem Docker do backend para v120; agente v122 passa `IMPORT_AES_KEY_BASE64` para o contêiner.
- Para tenants já instalados, configurar e preservar uma chave nova em `secrets.env` por empresa. Não reutilize a chave entre clientes. Não gere chave diferente a cada reinício: isso impediria decifrar arquivos anteriores.
- Homologar num PostgreSQL 16 descartável com `spring.profiles.active=tenant` e `mvn -Ptenant verify`; testar `POST /api/admin/importacao/preparar`, `POST /{id}/confirmar`, histórico, download, duplicação e rollback.
- O script de GitHub Actions `validacao-integracao.yml` verifica a migração V4 e suas tabelas em banco descartável quando executado em um runner com Maven/PostgreSQL.

### Aprovação de produção

**Não aprovada ainda:** compilar Maven completo, executar Flyway/Prisma em PostgreSQL real, validar backup/restauração e testar o fluxo de ponta a ponta em ambiente real. Não alteramos a instância atual nem liberamos a implantação automática.
