# Gestão Cloud v104 — Hospedagem centralizada de empresas

## Objetivo e limites

O painel da Gestão v104 controla servidores próprios, reservas de subdomínios exclusivos, acompanhamento de métricas do agente VPS, escolha assistida de servidor e estado da implantação. Uma **única VPS** pode servir vários clientes, mas a base NH v118 utiliza instalações de aplicação e bancos **independentes por empresa**. Não é um banco compartilhado multi-tenant.

**Estado:** desenvolvimento/homologação. A reserva de domínio funciona na API quando conectada a um banco migrado; **não** cria DNS, certificados, imagens ou máquinas automaticamente. Não habilite `TENANT_TEMPLATE_CERTIFIED=true` sem validação de isolamento, restauração, E2E, Prisma e Flyway em PostgreSQL. Não altere `AUTO_PROVISION_ENABLED` por conveniência. Nenhum deploy real foi executado neste pacote.

## Entrega implementada

1. Aba `Gestão Cloud` no Control Plane, otimizada para celular, tablet e desktop por CSS responsivo.
2. Cadastro e inventário de VPS existentes; leitura de CPU, RAM, disco e estado do agente com limite de heartbeat de 3 minutos.
3. Reserva de um subdomínio `empresa.<seu-dominio>`, validado contra termos reservados e endereços de outras empresas, com restrição UNIQUE PostgreSQL.
4. Reserva de capacidade por VPS em transação com bloqueio de linha (`SELECT ... FOR UPDATE`) para evitar duas reservas simultâneas na última vaga.
5. Sincronismo com `platform_company_hosting` usado pela implantação guiada v103. O operador pode seguir usando o formulário de contrato e módulo existente.
6. Proteção de edição manual: alterar endereço/VPS reservados exige usar a aba Gestão Cloud; URL ACTIVE não pode ser substituída no formulário legado.
7. Recebimento de métricas autenticado por token HMAC derivado do UUID da VPS em `/api/agent/cloud/heartbeat`.
8. `CLOUD_ROUTING_ENABLED=true` *somente após homologação* ativa a exigência de reserva compatível e agente online antes de enfileirar a instalação. Valor padrão `false` para não interromper sistemas existentes.
9. Estados RESERVED/DEPLOYING/ERROR/ACTIVE sincronizados à implantação existente. **Nenhuma URL é marcada ACTIVE só por ter sido reservada**.
10. Kit de agente v119 com heartbeat e leitura de métricas Linux. Uso de Docker e imagens imutáveis continua obrigatório para instalar.

## Arquitetura — uma VPS inicialmente

```
Usuário da empresa
    | HTTPS
    v
 DNS empresa.gestaosoftware.com.br -> IP da VPS central
    |
    v
 VPS Linux [Traefik + Docker + agente v119]
    |
    +-- gs-tenant-A [web, API, PostgreSQL exclusivo, volumes A]
    +-- gs-tenant-B [web, API, PostgreSQL exclusivo, volumes B]
    +-- gs-tenant-C [web, API, PostgreSQL exclusivo, volumes C]

Gestão Software [Control Plane e PostgreSQL independentes]
    | métricas, fila, controle de módulos e verificação final
    +-- agente autenticado da VPS
```

A VPS central não deve ser o único ponto de backup. Backups devem sair para armazenamento externo com retenção e teste de restauração. Para clientes maiores, separar PostgreSQL e evidências da VPS, usar redundância, fila de processamento, observabilidade e testes de carga.

## Evolução para várias VPS

Um registro wildcard DNS apontando para uma VPS **não distribui** automaticamente o tráfego para outras VPS. Quando houver várias, adote uma das opções antes de mover clientes:

- Uma borda central com proxy e roteamento autenticado por hostname para nós privados, com alta disponibilidade; **ou**
- Um serviço DNS automatizado por domínio (A/AAAA/CNAME específico de cada cliente), com propagação e TLS verificados antes da mudança.

A v104 mostra o IP da VPS atribuído como destino DNS orientativo, mas **não** altera automaticamente registros no Registro.br/Cloudflare/Route 53. Domínios próprios exigem comprovação de controle e configuração DNS independente.

## Variáveis

| Nome | Valor inicial | Uso |
| --- | --- | --- |
| `PROVISIONING_HOST_SUFFIX` | `gestaosoftware.com.br` | Domínio sob seu controle para subdomínios |
| `PROVISIONER_WORKER_TOKEN` | vazio | Chave mestra só no backend (32+ caracteres) |
| `CLOUD_ROUTING_ENABLED` | `false` | Reserva + agente online obrigatórios para a fila de deploy |
| `AUTO_PROVISION_ENABLED` | `false` | Execução automatizada após pagamento verificado |
| `TENANT_TEMPLATE_CERTIFIED` | `false` | Bloqueio principal até conclusão da certificação da base |

A chave de cada VPS é calculada com `gerar_token_vps.py` no kit do agente usando a **chave mestra apenas no ambiente administrativo seguro**, e o agente recebe apenas o token derivado. Nunca copie a chave mestra para a VPS do cliente.

## Sequência operacional para o atendente

1. Em **Empresas-clientes**, cadastre a empresa e selecione módulos, plano e responsável.
2. Em **VPS e hospedagem**, cadastre a VPS que a Gestão administra (nome, IP e limites).
3. Em **Gestão Cloud**, escolha a empresa, digite um subdomínio, confira disponibilidade e reserve.
4. O painel informa a VPS e o destino DNS. O administrador de infraestrutura garante que DNS, HTTPS, agente, backups e imagens estejam corretos.
5. Em **Implantação guiada**, informe capacidade, confira contrato assinado e pagamento confirmado no Asaas.
6. Só com os pré-requisitos certificados o job será entregue ao agente. Cada cliente usa serviços, senhas e volumes próprios.
7. O ambiente não vira ACTIVE até verificar HTTPS, UUID do tenant e `readyForCustomers=true`.

## Desenvolvimento e verificação

- Executado: parser Java/JDK 21 em 20 fontes (0 erros de sintaxe), `node --check`, teste de lógica do formulário com API simulada, 9 testes do agente e leitura de CPU/RAM/disco Linux com Docker simulado.
- **Não executado:** compilação Maven com dependências, execução Flyway V7 em PostgreSQL real, build dos contêineres, certificado público TLS, teste real de Traefik/DNS, testes multiempresa de isolamento, fluxo real Asaas, deploy/rollback e performance.
- O Chromium do ambiente não permitiu carregar páginas de teste locais; revisão de layout real em três larguras não está certificada nesta versão.

## Primeira publicação local segura

1. Localize o diretório em que o `docker compose ps` mostra os contêineres **da Gestão** (não o NH).
2. Faça backup completo do banco, do `.env` e dos documentos/logotipos usados pela sua instalação. Não compartilhe as chaves.
3. Extraia o projeto v104 em uma **pasta separada** para comparar. Não substitua `.env`, volumes, nem a instalação NH.
4. Em cópia de homologação conectada a **banco de testes**, rode `docker compose config`, `docker compose up -d --build backend web`, `docker compose logs --tail=200 backend`.
5. Confirme a execução da V7 e faça os testes de reserva e conflito entre empresas. Atualize a produção somente após uma janela de mudança, backup e plano de retorno.
6. Nunca use `docker compose down -v`, `flyway repair` ou marque manualmente `TENANT_TEMPLATE_CERTIFIED=true` para contornar erro.

### Endpoints novos

| Método | Rota | Proteção |
| --- | --- | --- |
| GET | `/api/platform/cloud/overview` | Administrador Gestão |
| GET | `/api/platform/cloud/enderecos/disponibilidade?slug=...` | Administrador Gestão |
| PUT | `/api/platform/cloud/empresas/{uuid}/endereco` | Administrador Gestão |
| POST | `/api/agent/cloud/heartbeat` | HMAC por VPS + VPS ativa |

Exemplo de reserva de endereço:
```json
{"slug":"protecaobrasil", "vpsId":"uuid-da-vps"}
```

Não cadastre infraestrutura real com credenciais de demonstração. Prefira VPN/acesso restrito para o agente, proxies seguros, TLS validado, versões de contêiner por digest, auditoria e capacidade reserva para picos.
