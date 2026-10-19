# V83.36 — Hierarquia multiempresa e unificação do Super Adm

> **Regra superada pela V83.44 (30/09/2026):** administradores comuns (`ADMIN`) passaram a participar da jornada e devem bater ponto. Somente o `SUPER_ADMIN` permanece isento. Este arquivo é mantido apenas como histórico da V83.36.

## Contas da Novo Horizonte
- `Super Adm` / `@Aguias2025`: conta global, sem jornada e sem troca obrigatória de senha.
- A conta antiga `Adm Novo Horizonte` é desativada como conta separada e sua função global passa ao Super Adm.
- A conta antiga `Rh Novo Horizonte` é migrada uma única vez para `Adm Novo Horizonte`.
- `Adm Novo Horizonte` recebe a senha temporária `nh2027`, deve trocá-la no primeiro acesso e o popup continua sendo exibido até a troca ser concluída.
- Passkeys antigas da conta migrada são removidas para permitir novo cadastro administrativo após a troca da senha.

## Hierarquia e empresas
- Novo vínculo `AdminTenantAccess` relaciona cada Administrador às empresas que ele pode gerenciar.
- Super Adm pode criar Administradores e marcar uma ou várias empresas para cada um.
- Super Adm pode alterar posteriormente essas permissões.
- Administradores automáticos criados junto com uma empresa recebem acesso inicial apenas àquela empresa.
- O Super Adm tem acesso global sem precisar de vínculos individuais.

## Gestão de apontamento
- A Central de Gestão continua única e genérica.
- Administradores com várias empresas recebem um seletor **Empresa em gestão**.
- O frontend envia o tenant ativo ao backend e o backend valida a permissão antes de trocar o contexto.
- O Super Adm reutiliza a mesma Central de Gestão para qualquer empresa.
- O cadastro de colaborador mostra a empresa em contexto; o colaborador é gravado naquele tenant e não em uma lista global.

## Separação entre gestão e jornada
- ADMIN e SUPER_ADMIN não participam da jornada.
- Novos administradores não recebem registro `Employee`.
- Registros Employee administrativos antigos são arquivados/destacados sem apagar histórico.
- O cadastro de colaboradores não oferece mais os perfis ADMIN/RH; administradores são criados pela área do Super Adm.

## Segurança
- A troca de empresa só afeta rotas de gestão. Rotas de senha/passkey permanecem vinculadas à conta administrativa de origem.
- Um Admin sem permissão explícita recebe `403` ao tentar acessar outra empresa.
- A senha padrão de novos Admins continua `nh2027`, com troca obrigatória no primeiro acesso.

## Passkey em ambiente multiempresa
- A passkey pertence à conta do administrador, não à empresa selecionada.
- A mesma passkey pode confirmar aprovações em qualquer empresa que esteja nas permissões daquele Admin.
- O contexto da empresa é validado separadamente no backend antes de abrir a marcação para revisão.
- O Super Adm pode cadastrar uma passkey própria na aba **Minha segurança** para aprovações sensíveis sem alterar sua senha fixa de login.
