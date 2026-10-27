# Limpeza das empresas fictícias (Neon do Gestão)

O arquivo `limpeza-empresas-demo-neon.sql` **não é executado automaticamente** e não deve ser colocado na pasta de migrações Flyway.

1. Confirme que o projeto do Neon é o **banco central de demonstração do Gestão**, nunca o banco dedicado NH nem qualquer banco de produção.
2. Antes da exclusão, tire um backup ou crie uma branch recuperável no Neon e confira que foi possível acessá-la.
3. Confira que existem exatamente as empresas fictícias **Novo Horizonte** e **SMG** com os IDs previstos, sem faturas pagas nem implantações ativas.
4. Rode o SQL pelo Neon SQL Editor como simulação: a última instrução é `ROLLBACK`, portanto nenhuma exclusão permanece.
5. Depois de conferir os avisos, altere **somente** o último `ROLLBACK;` para `COMMIT;`, execute novamente e valide `SELECT COUNT(*) FROM platform_companies;`.
6. **Não apague** `gs_users`, `platform_modules`, `platform_partners`, `flyway_schema_history`, nem a base do tenant NH. Preserve as contas `carlos.lima` e `pedro.henrique`.

Observações: a exclusão física do banco não equivale a desativar uma empresa pelo painel, que preserva histórico. Este script é uma **limpeza excepcional dos dados fictícios da demonstração**. Não desfaz serviços, bancos, DNS ou containers externos que já tenham sido provisionados.

Se aparecer erro de dependência, status de pagamento ou implantação, **pare** e revise. Não use `CASCADE` genérico nem desative constraints.
