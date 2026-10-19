# V83.28 — Apontamento Digital + exclusão de colaborador

## Nome do produto
- O nome exibido do sistema passa de **NH Ponto** para **Apontamento Digital**.
- Rotas técnicas `/ponto` e `/ponto-api` foram mantidas para não quebrar links/deploy existentes.
- PWA, títulos, telas, mensagens, Totem, painel principal e nome WebAuthn passam a exibir **Apontamento Digital**.

## Exclusão de colaborador
- ADMIN e RH podem usar **Excluir** na equipe.
- A exclusão é segura/auditável: revoga o login, remove passkeys administrativas eventualmente ligadas ao usuário, revoga a referência facial do Totem, remove o colaborador das telas operacionais e registra auditoria.
- Marcações, selfies, espelhos, banco de horas e registros históricos são preservados.
- Contas ADMIN, RH e SUPER_ADMIN não podem ser removidas por esse botão.
- O próprio usuário não pode excluir o próprio cadastro.

## Banco
- `Employee.archivedAt` marca colaboradores excluídos sem destruir histórico.
- O `entrypoint-nh.sh` já executa `prisma db push`, então o novo campo é criado no primeiro start da API.
