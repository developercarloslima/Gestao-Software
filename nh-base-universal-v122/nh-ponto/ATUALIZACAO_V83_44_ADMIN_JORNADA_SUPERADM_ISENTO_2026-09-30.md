# Atualização V83.44 — ADMIN bate ponto; somente SUPER ADMIN é isento

Data: 30/09/2026

## Regra aplicada

A partir desta versão, **todo usuário com perfil `ADMIN` ativo deve participar da jornada e bater ponto normalmente**. A única exceção é o **`SUPER_ADMIN` global**, que continua sem vínculo de jornada e não recebe as telas de registro de ponto.

Essa regra substitui a regra anterior da V83.36/V83.43 que deixava `ADMIN` e `SUPER_ADMIN` fora da jornada.

## Comportamento do ADMIN

- recebe vínculo `Employee` ativo;
- visualiza **Registrar ponto**, **Minha jornada** e **Ajustes**;
- continua visualizando **Minha segurança**, **Gestão**, **Totem facial** e **Auditoria**, conforme suas permissões;
- usa o mesmo fluxo de jornada operacional definido para o NH Ponto, incluindo selfie simples e validações existentes da marcação;
- aparece em **Equipe com jornada**, permitindo configurar unidade/local, escala, setor, salário e demais dados aplicáveis;
- não pode ser transformado em outro perfil pela tela de colaboradores; criação e gestão do perfil `ADMIN` continuam sob responsabilidade do Super Adm.

## Comportamento do SUPER ADMIN

- continua sem vínculo `Employee` de jornada;
- não recebe **Registrar ponto**, **Minha jornada** ou **Ajustes** pessoais;
- permanece com acesso global de administração;
- se existir um vínculo de funcionário antigo ligado ao Super Adm por erro ou versão anterior, o seed o arquiva/desvincula sem apagar o histórico.

## Administradores existentes

O seed da V83.44 procura os `ADMIN` ativos. Para cada um:

1. preserva o usuário e as permissões existentes;
2. mantém um vínculo `Employee` já existente na empresa-base, reativando-o quando aplicável;
3. se não houver vínculo atual, cria um vínculo de jornada;
4. tenta preencher automaticamente setor, escala e unidade ativos da empresa-base quando disponíveis.

Registros históricos antigos que já estavam desvinculados não são apagados.

## Administrador multiempresa

O seletor **Empresa em gestão** continua alterando somente o contexto administrativo. A jornada pessoal do administrador é resolvida pela empresa do seu próprio vínculo `Employee`.

Isso impede o seguinte erro: o Admin gerencia a Empresa B no seletor e, ao abrir o próprio ponto, a marcação ser gravada indevidamente na Empresa B quando sua jornada pertence à Empresa A.

Para novos administradores multiempresa criados pelo Super Adm, a primeira empresa selecionada no cadastro passa a ser a empresa-base inicial da jornada.

## Ajustes pessoais

Foi adicionada a rota `GET /adjustments/me`. A aba **Ajustes** do usuário passa a buscar somente os pedidos de ajuste do próprio vínculo de jornada, inclusive para `ADMIN`, sem misturar os pedidos de toda a empresa que ele administra.

## Unidade/local e geocerca

Para registrar ponto em uma empresa que exige geocerca, o administrador precisa ter uma unidade/local de trabalho configurado, assim como os demais usuários de jornada. O Admin agora aparece em **Equipe com jornada** para que essa configuração possa ser feita.

## Banco de dados

Não foi necessária nova alteração de schema Prisma para esta regra. O vínculo de jornada usa a estrutura `User` + `Employee` já existente.

## Arquivos principais alterados

- `apps/api/src/services/admin-attendance.service.ts`
- `apps/api/src/routes/super-admin.ts`
- `apps/api/src/server.ts`
- `apps/api/src/routes/admin.ts`
- `apps/api/src/routes/adjustments.ts`
- `apps/web/src/App.tsx`
- `apps/web/src/components/Adjustments.tsx`
- `apps/web/src/components/PunchPanel.tsx`
- `apps/web/src/components/management/Employees.tsx`
- `prisma/seed.ts`
- `prisma/bootstrap.ts`
- `README.md`
- `CHANGELOG.md`

## Validação realizada

Os arquivos TypeScript/TSX alterados foram submetidos à validação sintática com o compilador TypeScript disponível no ambiente e passaram sem erros de sintaxe. A instalação completa das dependências do monorepo não concluiu neste ambiente, portanto o build integral por `npm` deverá ser executado normalmente no ambiente de deploy, que já possui/baixa as dependências do projeto.
