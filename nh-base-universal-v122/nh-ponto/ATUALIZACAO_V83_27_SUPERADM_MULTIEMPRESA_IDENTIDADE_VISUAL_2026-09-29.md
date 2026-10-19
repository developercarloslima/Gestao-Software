# V83.27 — SuperAdm, multiempresa e identidade visual por PDF

## SuperAdm
- Novo perfil global `SUPER_ADMIN`, exibido como **SuperAdm**.
- Usuário de login: `SuperAdm` (comparação sem diferença entre maiúsculas/minúsculas).
- Senha fixa configurada no seed: `@Aguias2025`.
- Não exige troca de senha no primeiro acesso.
- Painel global separado das empresas operacionais.

## Criação de empresa
- O SuperAdm cria uma nova empresa com nome, razão social, CNPJ/documento e fuso.
- Ao criar a empresa, o NH Ponto cria automaticamente:
  - configurações de jornada e segurança;
  - setor Administração;
  - escala Administrativa 44h;
  - usuário Administrador da empresa;
  - colaborador/registro do Administrador.
- O usuário padrão do administrador segue `Adm <Nome da Empresa>`.
- Senha temporária padrão: `nh2027`.
- A troca da senha é obrigatória no primeiro login.

## Usuários Novo Horizonte
- `SuperAdm`
- `Adm Novo Horizonte`
- `Rh Novo Horizonte`
- `colaborador`

As contas legadas `adm` e `rh` são renomeadas uma única vez. Nessa migração, a senha volta para `nh2027`, a troca de senha é obrigatória e passkeys antigas dessas contas são removidas para não bloquear o primeiro acesso.

## Senha padrão
Todo novo usuário criado no NH Ponto recebe a senha temporária `nh2027` e deve alterá-la no primeiro login. A única exceção é o SuperAdm.

## Identidade visual multiempresa
O SuperAdm pode alterar por empresa:
- cor principal;
- cor de destaque;
- cor de superfície;
- cor de texto;
- logo.

O tema é aplicado dinamicamente ao painel, sidebar, cartões, botões, primeiro acesso e Totem.

## Importação de Manual da Marca em PDF
- O SuperAdm envia um PDF do manual da marca.
- O backend usa Poppler (`pdftotext`, `pdfinfo`, `pdftoppm`) para analisar o documento.
- Cores declaradas em HEX/RGB são detectadas automaticamente.
- A página final é renderizada e usada para gerar uma logo candidata automaticamente.
- O SuperAdm pode revisar as cores e enviar a logo oficial separadamente caso a extração automática não seja a ideal.
- PDF e logo ficam em volume persistente próprio `nh_ponto_branding`.

## Novo Horizonte
O tema padrão permanece alinhado ao manual oficial enviado:
- Azul Profundo: `#070C40`
- Amarelo Vibrante: `#F0F000`
- Branco: `#FFFFFF`

## Banco
O schema Prisma recebe:
- `Role.SUPER_ADMIN`;
- `User.displayName`;
- campos de identidade visual em `Tenant`.

O `prisma db push` preserva os registros existentes e adiciona os novos campos/enum.
