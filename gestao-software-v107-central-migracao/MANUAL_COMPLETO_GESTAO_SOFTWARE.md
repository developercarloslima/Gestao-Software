# Manual Operacional Completo — Gestão Software

Versão do Control Plane: Gestão Software V100 (Render Ready)
Versão de referência da Novo Horizonte: V83-107 (Controle Total pela Gestão + Render)
Data: 09/10/2026

## 1. Objetivo deste manual

Este manual descreve o ciclo completo de operação da plataforma Gestão Software: cadastrar uma empresa-cliente, definir plano e mensalidade, escolher módulos, configurar identidade visual, gerar e assinar contrato, preparar domínio e hospedagem, fazer deploy, validar a licença e administrar a empresa depois que ela entra em produção.

A regra principal da arquitetura é simples: a Gestão Software é o Control Plane. As empresas-clientes possuem aplicações independentes, bancos e domínios próprios, mas a licença, o status da empresa, os módulos e o branding são controlados centralmente pela Gestão Software.

A Gestão não precisa ficar aberta no navegador. O estado da empresa fica salvo no banco da Gestão e a aplicação-cliente mantém localmente a última configuração válida confirmada. Fechar o navegador de Carlos ou Pedro não altera nada.

## 2. Arquitetura geral

### 2.1 Gestão Software

A Gestão Software possui:
- site público;
- Área Administrativa / Control Plane;
- cadastro de empresas-clientes;
- catálogo de módulos;
- planos e mensalidades;
- identidade visual e arquivos da empresa;
- contratos;
- domínio, subdomínio e hospedagem;
- controle de VPS/infraestrutura;
- financeiro da Gestão;
- calculadora de plano;
- status da licença de cada empresa.

### 2.2 Empresa-cliente

Cada empresa-cliente pode ter:
- domínio próprio, por exemplo `app.empresa.com.br`;
- subdomínio fornecido pela Gestão, por exemplo `empresa.gestaosoftware.com.br`;
- banco operacional próprio;
- serviços próprios no Render/VPS/AWS;
- apenas os módulos que contratou.

### 2.3 Comunicação entre Control Plane e cliente

A empresa consulta a API pública da Gestão usando um host lógico cadastrado no Control Plane. A resposta informa, entre outros dados:
- empresa ativa ou desativada;
- módulos habilitados;
- nome exibido;
- logo;
- favicon;
- cores;
- slogan;
- rota inicial.

A Novo Horizonte persiste a última configuração confirmada no próprio banco. Se a Gestão ficar temporariamente indisponível, a NH continua usando o último estado confirmado. Se a NH estava desativada, continua desativada; se estava ativa, continua ativa.

## 3. Usuários administrativos da Gestão

O Control Plane deve ser usado apenas pelos administradores da Gestão Software.

Contas iniciais de referência:
- `carlos.lima`
- `pedro.henrique`

No primeiro acesso, a senha inicial deve ser alterada. A Calculadora de Planos e ações sensíveis ficam restritas a Carlos Lima e Pedro Henrique.

Nunca forneça acesso ao Control Plane para administradores de empresas-clientes. O Super Admin de uma empresa administra apenas empresas já contratadas/permitidas dentro do produto operacional; ele não cria clientes da Gestão, não altera preço comercial e não muda infraestrutura da Gestão Software.

## 4. Fluxo completo para cadastrar uma nova empresa

Acesse:

`Área Administrativa > Empresas-clientes > Nova empresa`

### 4.1 Dados cadastrais

Preencha:
- Grupo empresarial, quando existir;
- Razão social;
- Nome fantasia;
- CPF/CNPJ;
- Responsável;
- E-mail;
- Telefone;
- Status inicial.

Para uma empresa que será implantada imediatamente, use `ACTIVE`. Para uma implantação ainda em preparação, pode-se usar `TRIAL` enquanto os testes estão em andamento.

### 4.2 Plano

Escolha somente a categoria comercial:
- Start;
- Business;
- Pro;
- Scale;
- Enterprise;
- Dedicated Cloud;
- Hybrid Enterprise;
- Personalizado, quando necessário.

O plano não possui preço obrigatório pré-fixado no cadastro. Ele serve para classificar o porte e as condições comerciais.

### 4.3 Mensalidade calculada

O campo `Mensalidade calculada` é o valor final negociado e contratado para aquela empresa.

Exemplo:
- Plano: Business;
- Mensalidade calculada: R$ 4.200,00;
- Prazo: anual;
- Forma de pagamento: Pix Automático.

É essa mensalidade que deve constar no contrato e nas cobranças futuras da Gestão Software.

### 4.4 Prazo contratual

Escolha:
- Mensal;
- Semestral;
- Anual.

O prazo representa a condição comercial acordada. Alterações futuras devem ser feitas em `Editar empresa` e, quando a alteração mudar condições contratuais relevantes, deve-se gerar/adotar novo instrumento contratual conforme a política comercial da Gestão.

### 4.5 Forma de pagamento

Escolha uma das opções:
- Pix Automático;
- Débito Automático;
- Boleto.

A forma selecionada fica associada à empresa e ao contrato.

Importante: as tarifas Asaas dos pagamentos não devem ser tratadas como perda da margem da Gestão quando forem repassadas ao pagador. Exemplo: se a cobrança-base é R$ 100,00 e a tarifa financeira repassada é R$ 2,00, a cobrança total é R$ 102,00.

No saque do consultor, se o saldo solicitado é R$ 100,00 e a transferência custa R$ 2,00, o sistema deve informar `Valor solicitado R$ 100,00`, `Taxa R$ 2,00`, `Valor líquido R$ 98,00` antes da confirmação.

### 4.6 Módulos

Marque somente os módulos contratados.

Catálogo atual:
- Site Principal;
- Cota;
- Vistoria / Eventos;
- Apontamento Digital;
- Gestão Financeira e Comercial.

A ativação do módulo é persistente. Carlos ou Pedro não precisam permanecer logados para o módulo continuar ativo.

Se um módulo for desligado no Control Plane, a empresa deixa de ter direito ao módulo. A interface o oculta e o backend também bloqueia as APIs correspondentes.

### 4.7 Logo da empresa

Envie manualmente a logo.

Formatos aceitos no fluxo atual:
- PNG;
- JPG/JPEG;
- WEBP;
- SVG, quando suportado pelo navegador/backend.

A logo é armazenada pela Gestão e pode ser substituída posteriormente em `Editar empresa`.

### 4.8 PDF da identidade visual

Faça upload do manual/guia de identidade visual da empresa em PDF.

O arquivo serve como referência para:
- cores;
- tipografia;
- aplicação de marca;
- símbolos;
- uso de fundo;
- padrões visuais.

O PDF fica vinculado à empresa e pode ser consultado pelos administradores da Gestão.

### 4.9 White-label

Configure:
- nome exibido na plataforma;
- texto institucional abaixo da marca, normalmente `Gestão Software LTDA`;
- cor principal;
- cor secundária;
- cor de apoio;
- título do site;
- slogan;
- subtítulo do login;
- rota padrão após login.

A regra esperada é: a aplicação mostra a marca do cliente e abaixo/na assinatura institucional informa que a tecnologia é fornecida pela Gestão Software LTDA.

## 5. Contrato automático

Ao cadastrar a empresa, a Gestão cria um contrato vinculado às condições comerciais registradas.

O contrato deve refletir:
- identificação da empresa;
- plano;
- módulos;
- mensalidade;
- prazo;
- forma de pagamento;
- demais condições vigentes no momento da criação.

### 5.1 Assinatura da empresa-cliente

Na ficha da empresa existe um link público de assinatura.

Fluxo:
1. Copiar link do contrato;
2. Enviar ao responsável da empresa;
3. O responsável abre o link;
4. Confere o contrato;
5. Informa nome e CPF/CNPJ;
6. Confirma o aceite;
7. O sistema registra a assinatura do cliente.

### 5.2 Assinatura pela Gestão

Somente Carlos Lima ou Pedro Henrique podem assinar pela Gestão Software.

O contrato passa a `ASSINADO` somente quando as assinaturas necessárias estiverem concluídas.

### 5.3 Regra operacional recomendada

Não liberar produção definitiva para uma nova empresa antes de:
- cadastro concluído;
- mensalidade conferida;
- módulos conferidos;
- contrato assinado;
- domínio/hospedagem conferidos;
- backup e responsáveis definidos.

## 6. Empresas cadastradas

Na listagem de empresas existem ações operacionais.

### 6.1 Ver empresa

Use para consultar:
- dados comerciais;
- contrato;
- identidade visual;
- módulos;
- hospedagem;
- mensalidades;
- status.

### 6.2 Editar

`Editar` deve ser usado para alterar qualquer informação cadastral/comercial permitida, incluindo:
- dados da empresa;
- responsável;
- plano;
- mensalidade;
- prazo;
- forma de pagamento;
- módulos;
- logo;
- PDF de identidade visual;
- cores e branding.

Depois de alterar módulos, valide a aplicação-cliente. O cache normal é curto; no ambiente de teste da NH foi configurado para aproximadamente 30 segundos.

### 6.3 Desativar

Use `Desativar` quando a empresa precisa perder acesso temporariamente ou quando a assinatura/licença foi suspensa.

Efeito esperado:
- status deixa de ser ACTIVE/TRIAL;
- endpoint público da Gestão retorna `active=false`;
- backend da empresa passa a responder 403 `COMPANY_DISABLED` nas rotas operacionais;
- site mostra mensagem de plataforma temporariamente indisponível;
- Apontamento Digital também bloqueia operações;
- dados não são apagados.

Desativar é reversível. Para reativar, edite a empresa e retorne o status para `ACTIVE` (ou `TRIAL` durante testes).

### 6.4 Excluir

Use somente quando a empresa realmente deve ser removida da Gestão.

Excluir é diferente de desativar. A exclusão é destinada a cadastros incorretos, testes descartáveis ou clientes sem necessidade de preservação histórica.

Em produção, prefira desativar quando houver contratos, histórico financeiro, auditoria ou obrigação de retenção.

## 7. Controle de módulos na empresa-cliente

A NH V83-107 aplica a licença em múltiplas camadas.

### 7.1 Site

O site busca `/api/public/tenant-config` e:
- aplica logo e cores;
- oculta módulos não contratados;
- mostra tela de suspensão quando a empresa está desativada.

### 7.2 API Java

A API possui interceptor de entitlement.

Empresa desativada:
- todas as rotas operacionais retornam HTTP 403;
- código `COMPANY_DISABLED`.

Módulo desativado:
- rotas daquele módulo retornam HTTP 403;
- código `MODULE_DISABLED`.

Health check e endpoint de configuração permanecem disponíveis para diagnóstico e sincronização.

### 7.3 Apontamento Digital

O Apontamento também possui gate próprio.

Ele consulta a configuração persistida servida pelo backend principal da NH. Assim:
- desativar empresa bloqueia Ponto;
- desativar apenas APONTAMENTO_DIGITAL bloqueia Ponto;
- a interface mostra aviso;
- a API Fastify rejeita operações.

## 8. Persistência quando a Gestão está fora do ar

A Gestão Software não precisa estar aberta no computador de Carlos ou Pedro.

Há três situações diferentes:

1. Navegador do administrador fechado: não muda nada. O servidor da Gestão continua funcionando.
2. Control Plane web temporariamente indisponível, mas NH tem última configuração: a NH usa o último estado confirmado.
3. Banco/servidor da Gestão totalmente indisponível: a NH continua com a última licença persistida; não inventa alteração de módulos.

Essa regra evita que um problema temporário da Gestão altere contratos/licenças indevidamente.

## 9. Hospedagem da empresa

Na ficha da empresa, configure a hospedagem.

Campos principais:
- VPS/infraestrutura vinculada;
- nome do ambiente;
- domínio principal;
- subdomínio;
- modo DNS;
- URL da aplicação;
- URL da API;
- banco;
- caminho de deploy;
- método de deploy;
- observações.

Modos usuais:
- `SUBDOMAIN`: subdomínio fornecido pela Gestão;
- `CUSTOM_DOMAIN`: domínio próprio totalmente operado para o cliente;
- `CLIENT_DOMAIN_WITH_MODULES`: cliente já tem domínio/site e recebe apenas os módulos.

## 10. Domínio e DNS

### 10.1 Subdomínio Gestão Software

Exemplo:
`empresa.gestaosoftware.com.br`

Crie um registro DNS apontando para a infraestrutura de produção conforme o provedor utilizado.

### 10.2 Domínio do cliente

Exemplo:
`app.empresa.com.br`

O cliente cria o DNS indicado pela Gestão. No Render, normalmente o domínio é adicionado em `Settings > Custom Domains`; depois o Render fornece o destino CNAME/A/ALIAS aplicável.

### 10.3 Host lógico de licença

A aplicação deve possuir:
`GESTAO_TENANT_HOST=app.empresa.com.br`

Esse valor precisa ser exatamente um domínio ou subdomínio cadastrado na ficha da empresa na Gestão. É por esse host que o Control Plane identifica qual empresa está consultando a licença.

## 11. Deploy no Render — pré-requisitos

Para deploy automatizado no Render, cada projeto precisa estar em GitHub, GitLab ou Bitbucket.

Recomendação de repositórios:
- `gestao-software-control` — Gestão Software;
- `nh-plataforma` — Novo Horizonte.

Não envie `.env` com segredos para Git.

Arquivos preparados:
- Gestão Software V100: `render.yaml`;
- NH V83-107: `render.yaml`.

## 12. Deploy da Gestão Software no Render

A Gestão deve ser implantada primeiro.

O Blueprint V100 cria:
- `gestao-software-control-test-db`;
- `gestao-software-control-test-api`;
- `gestao-software-control-test-web`.

### 12.1 Passos

1. Criar/reutilizar um repositório Git;
2. Colocar o conteúdo da V100 na raiz;
3. Commitar e enviar para GitHub/GitLab/Bitbucket;
4. No Render, criar Blueprint pelo repositório;
5. Confirmar região e planos;
6. Aplicar;
7. Esperar database, API e web ficarem `Live`;
8. Abrir `/admin/login.html`;
9. Trocar senha inicial dos proprietários;
10. Conferir Novo Horizonte na lista de empresas.

URLs de staging esperadas pelo Blueprint atual:
- `https://gestao-software-control-test-web.onrender.com`
- `https://gestao-software-control-test-api.onrender.com`

Se o Render alterar o nome do serviço, use a URL real nas variáveis da NH.

## 13. Deploy da Novo Horizonte no Render

A V83-107 possui Blueprint completo de staging.

Ele prevê:
- banco NH;
- banco Apontamento;
- API principal;
- proxy Hinova;
- API facial;
- Apontamento Digital API + web;
- site principal Nginx.

### 13.1 Segredos que devem ser revisados

Antes de considerar produção, revise no Dashboard do Render:
- `ADMIN_PASSWORD`;
- `CONSULTANT_PASSWORD`;
- `ANALYST_PASSWORD`;
- `TOW_PASSWORD`;
- `WORKSHOP_PASSWORD`;
- `EVENT_PASSWORD`;
- `BUYER_PASSWORD`;
- credenciais Hinova;
- credenciais Asaas quando habilitado;
- Google Drive se utilizado.

Segredos gerados automaticamente, como JWT e chaves internas, não devem ser copiados para código.

### 13.2 Variáveis de controle central

Na API principal da empresa:
- `GESTAO_CONTROL_URL=https://gestao-software-control-test-api.onrender.com`
- `GESTAO_TENANT_HOST=nh.gestaosoftware.com.br`
- `GESTAO_CONTROL_CACHE_SECONDS=30`

Em nova empresa, troque `GESTAO_TENANT_HOST` pelo host cadastrado para aquele cliente.

## 14. Teste completo Gestão -> NH

### Teste A — empresa ativa

1. Gestão > Novo Horizonte;
2. Status ACTIVE;
3. Todos os módulos necessários ativos;
4. Abra a NH;
5. Confirme navegação e login;
6. Confirme `/api/public/tenant-config` com `active=true`.

### Teste B — desligar apenas Cota

1. Gestão > Novo Horizonte > Editar;
2. Desmarque Cota;
3. Salve;
4. Aguarde até 30 segundos;
5. Atualize `/cota/`;
6. A tela deve esconder/redirecionar o módulo;
7. Uma chamada direta à API de Cota deve retornar 403 `MODULE_DISABLED`;
8. Apontamento e outros módulos permanecem ativos.

### Teste C — desligar Apontamento

1. Desative `APONTAMENTO_DIGITAL`;
2. Aguarde sincronização;
3. Abra `/ponto/`;
4. A interface deve informar que o módulo não está ativo;
5. API do Ponto rejeita operações com 403.

### Teste D — desativar empresa inteira

1. Clique `Desativar` na empresa;
2. Aguarde até 30 segundos;
3. Recarregue a NH;
4. Site mostra plataforma indisponível;
5. Login/API operacional retorna 403 `COMPANY_DISABLED`;
6. Ponto também bloqueia;
7. Dados permanecem no banco.

### Teste E — fechar o Control Plane

1. Reative a empresa;
2. Confirme que a NH funciona;
3. Feche o navegador da Gestão;
4. Continue usando a NH;
5. A NH deve permanecer ativa normalmente.

### Teste F — indisponibilidade temporária da Gestão

Somente em staging/controlado:
1. Confirme estado da NH;
2. Suspenda temporariamente o serviço da Gestão;
3. NH utiliza a última configuração persistida;
4. Restaure a Gestão;
5. Confirme nova sincronização.

## 15. Criar e fazer deploy de uma nova empresa sem alterar código

### Etapa 1 — cadastrar na Gestão

Cadastre dados, plano, mensalidade, pagamento, módulos, logo e PDF.

### Etapa 2 — assinar contrato

Conclua assinatura da empresa e assinatura de Carlos/Pedro.

### Etapa 3 — escolher domínio

Exemplo:
`cliente.gestaosoftware.com.br`

Cadastre o mesmo host na seção Hospedagem da empresa.

### Etapa 4 — criar infraestrutura

Para ambiente dedicado, crie um novo conjunto Render/VPS usando o mesmo código-base da plataforma.

Não faça uma cópia customizada do código por cliente. A diferença deve vir de configuração, banco, domínio e módulos.

### Etapa 5 — variáveis

No deploy do cliente, altere principalmente:
- banco;
- `GESTAO_CONTROL_URL`;
- `GESTAO_TENANT_HOST`;
- URL pública;
- origens CORS;
- WebAuthn RP/Origin quando houver Ponto;
- credenciais próprias das integrações do cliente.

### Etapa 6 — deploy

Suba os serviços e acompanhe health checks/logs.

### Etapa 7 — domínio

Adicione o domínio no Render e faça o DNS.

### Etapa 8 — validação

Teste:
- branding;
- módulos ativos;
- módulos desligados;
- login;
- banco;
- contrato;
- integrações;
- suspensão e reativação.

## 16. Atualização de uma empresa já em produção

Atualizações de código devem partir da mesma base central do produto.

Processo recomendado:
1. Testar localmente;
2. Subir branch/release;
3. Deploy em staging;
4. Smoke test;
5. Backup quando aplicável;
6. Deploy produção;
7. Confirmar migrations;
8. Confirmar health checks;
9. Validar módulos principais.

Alterar plano, mensalidade, módulo ou branding não deve exigir novo build/deploy. Essas ações são feitas diretamente no Control Plane.

## 17. Monitoramento Render

Para cada serviço, acompanhe:
- status do deploy;
- logs;
- CPU;
- memória;
- resposta HTTP;
- health check;
- conexões do banco.

Health checks de referência:
- Gestão API: `/api/health`;
- Gestão Web: `/`;
- NH API: `/api/health`;
- NH Web: `/health`;
- Hinova: `/health`;
- Face API: `/health`;
- Ponto: `/health`.

## 18. Migrations

As migrations Flyway da Gestão são executadas pelo backend na inicialização.

Na V100, a sequência atual inclui V1 a V5.

Na NH V83-107, as migrations novas incorporadas incluem:
- V86 — financeiro/Asaas/consultores;
- V87 — persistência de licença da Gestão;
- V88 — taxas Asaas repassadas.

Não rode migration manual duas vezes e nunca crie duas migrations com o mesmo número.

## 19. Diagnóstico rápido

### 19.1 Site abre, API dá 502

Causa provável: backend não iniciou ou proxy não consegue alcançar o backend.

Verifique logs do backend e health endpoint.

### 19.2 Backend cai no Flyway

Procure mensagens como:
`Found more than one migration with version X`.

Nunca deixe dois arquivos `VX__...sql` com o mesmo X.

### 19.3 403 no login da Gestão

Verifique:
- CORS;
- token antigo no navegador;
- usuário ativo;
- backend Live;
- `ALLOWED_ORIGINS`.

### 19.4 NH não responde à desativação

Verifique:
- `GESTAO_CONTROL_URL`;
- `GESTAO_TENANT_HOST`;
- domínio cadastrado na Gestão;
- resposta de `/api/public/tenant-config`;
- resposta da Gestão em `/api/public/platform/branding?host=...`.

### 19.5 Branding não atualiza

Confirme:
- logo enviada;
- host correto;
- URL da logo acessível;
- cache do navegador;
- tenant-config atualizado.

## 20. Checklist de produção para nova empresa

Antes de marcar uma implantação como concluída, confirme:

- [ ] Empresa cadastrada;
- [ ] Dados fiscais conferidos;
- [ ] Responsável e contatos conferidos;
- [ ] Plano definido;
- [ ] Mensalidade calculada definida;
- [ ] Forma de pagamento definida;
- [ ] Módulos corretos;
- [ ] Logo enviada;
- [ ] PDF de identidade visual enviado;
- [ ] Branding revisado;
- [ ] Contrato criado;
- [ ] Contrato assinado pela empresa;
- [ ] Contrato assinado por Carlos/Pedro;
- [ ] Domínio definido;
- [ ] Hospedagem cadastrada;
- [ ] Banco criado;
- [ ] Segredos configurados;
- [ ] `GESTAO_CONTROL_URL` configurada;
- [ ] `GESTAO_TENANT_HOST` corresponde ao host da Gestão;
- [ ] Deploy Live;
- [ ] HTTPS válido;
- [ ] Health checks OK;
- [ ] Login testado;
- [ ] Módulos ativos testados;
- [ ] Módulos não contratados bloqueados;
- [ ] Desativação da empresa testada;
- [ ] Reativação testada;
- [ ] Backup/monitoramento definidos.

## 21. Rotina recomendada de Carlos e Pedro

### Diariamente
- verificar alertas de infraestrutura;
- verificar empresas inadimplentes/suspensas;
- verificar deploys com falha;
- verificar tickets críticos.

### Ao fechar nova venda
- usar Calculadora de Planos;
- cadastrar empresa;
- definir mensalidade;
- escolher módulos;
- gerar contrato;
- acompanhar assinatura;
- provisionar domínio/infraestrutura;
- executar checklist de produção.

### Antes de desativar
- conferir empresa correta;
- confirmar motivo;
- preferir suspensão a exclusão;
- registrar comunicação ao cliente quando aplicável.

### Antes de excluir
- confirmar que não há obrigação de preservação contratual, fiscal ou operacional;
- exportar/guardar o que for necessário;
- confirmar que se trata da empresa correta.

## 22. Princípio operacional final

A Gestão Software deve controlar configuração e licença; a empresa-cliente deve executar sua operação sem depender de um navegador administrativo aberto.

Mudar configuração comercial = Control Plane.

Atualizar código do produto = pipeline/deploy.

Cadastrar novo cliente = configuração + contrato + infraestrutura, sem alteração manual de código por cliente.
