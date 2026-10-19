# Kit de implantação por empresa — Gestão Software (protótipo seguro)

Este diretório contém um **agente de provisionamento inicial para VPS gerenciada** e um Compose parametrizado com bancos e volumes independentes. **Não** cria instâncias AWS, Render ou VPS automaticamente. Não usar em produção sem auditoria e homologação.

## Arquitetura

- Painel Gestão Software recebe os dados comerciais e a estimativa de demanda.
- Contrato assinado e fatura verificada diretamente no Asaas desbloqueiam a fila.
- Um agente independente executado em servidor autorizado consulta jobs por token secreto.
- O agente instala imagens Docker previamente aprovadas pelo operador e cria um banco PostgreSQL e volumes próprios para cada empresa.
- A URL só é liberada pela Gestão após validação externa de TLS e identificação do tenant pelo endpoint `/api/health/tenant`.

## Pré-requisitos para o operador

1. Preparar uma VPS Linux exclusiva ou cluster próprio, DNS para wildcard do domínio autorizado, HTTPS, Traefik, rede Docker pública e backups configurados.
2. Criar um usuário de serviço restrito para o agente; entender que acesso ao Docker é acesso privilegiado no host.
3. Publicar as **sete imagens revisadas** do sistema NH via CI/registry (não utilizar imagens da NH com credenciais/dados antigos). A versão-base fornecida em separado ainda exige auditoria de white-label e tests E2E.
4. Definir `GESTAO_API_URL` (HTTPS), `PROVISIONER_WORKER_TOKEN` igual ao usado na Gestão, `WORKER_VPS_ID` (UUID da VPS cadastrada na Gestão), `GESTAO_CONTROL_URL`, `GESTAO_CONTROL_PUBLIC_URL`, `TENANT_ALLOWED_HOST_SUFFIX`, `TRAEFIK_PUBLIC_NETWORK`, `TRAEFIK_CERTRESOLVER` e as sete variáveis `NH_*_IMAGE` com tags imutáveis/digests.
5. Somente após todos os pré-requisitos e testes, configurar `ALLOW_RESOURCE_CREATION=yes`. Antes disso, `provisioner_agent.py --check` deve bloquear a execução.

Para conferir variáveis sem criar recursos:

```bash
python3 provisioner_agent.py --check
```

Para operar **apenas no servidor autorizado**, depois de revisado:

```bash
python3 provisioner_agent.py
```

O processo deve ser executado como serviço do systemd, com reinício supervisionado, logs restritos e rotação; isso não é instalado automaticamente pelo kit.

## Cuidados obrigatórios

- Não apontar URLs de empresas para servidores sem DNS/TLS previamente configurados.
- Não registrar senhas ou tokens nos eventos da Gestão nem em logs públicos.
- Não compartilhar volumes, redes privadas ou bancos entre empresas.
- Não habilitar a API administrativa do Docker para a internet.
- Não criar serviços pagos sem autorização comercial explícita.
- O Asaas confirma pagamentos; o agente não aceita estados financeiros enviados por usuários.
- Configurar monitoramento, backups e recuperação testada antes de ativar uma empresa real.
- Troca de senhas iniciais e entrega segura ao responsável comercial ainda precisam de integração na plataforma.

## Limitações

A integração de AWS/Render e a seleção automática de capacidade financeira e regiões ainda não estão implementadas. O script só opera com tenants de perfil START e o provedor MANAGED_VPS. A versão-base NH incluída nesta entrega deve ser considerada **código para refatoração**, não software já liberado para usar em qualquer empresa.

## Atualização v102 — chave exclusiva por VPS

A variável `PROVISIONER_WORKER_TOKEN` na Gestão é a **chave mestra**. Nunca copie esse valor para a VPS. Para cada VPS, execute em um ambiente administrativo restrito:

```bash
export PROVISIONER_MASTER_KEY='MESMA_CHAVE_MESTRA_DA_GESTAO'
python3 gerar_token_vps.py UUID_DA_VPS
```

Copie **somente** o token derivado para `PROVISIONER_WORKER_TOKEN` do agente daquela VPS. O backend recalcula o HMAC-SHA256 para verificar o UUID enviado em todas as ações da fila. Um token de VPS não autoriza agir por outra. Rotacione a chave mestra e todos os tokens derivados se ocorrer comprometimento.

O agente v102 exige referências de imagens Docker imutáveis por `@sha256:...`, CNPJ de empresa configurado e escrita atômica dos segredos. O modo `--check` é de leitura (não necessita `ALLOW_RESOURCE_CREATION=yes`).

**Ainda não utilizar em produção:** a base NH precisa de auditoria white-label e testes E2E; a Gestão deixa `TENANT_TEMPLATE_CERTIFIED=false` por padrão e rejeita o deploy. Isso é intencional.
