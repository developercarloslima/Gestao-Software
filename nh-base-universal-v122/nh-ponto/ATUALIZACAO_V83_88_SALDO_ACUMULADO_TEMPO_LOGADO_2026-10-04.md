# ATUALIZAÇÃO V83.88 — SALDO ACUMULADO E TEMPO TOTAL LOGADO

## Regra oficial de horas

- A apuração oficial da jornada passa a usar o **tempo total logado entre CLOCK_IN e CLOCK_OUT**.
- Intervalo, pausa e tempo efetivo continuam existindo somente nos contadores gerenciais para análise de desempenho do ADM/SUPER_ADMIN.
- Esses contadores gerenciais não reduzem as horas normais lançadas no espelho/banco.
- As horas normais continuam limitadas à carga prevista da jornada.
- Déficit é calculado quando o total logado reconhecido não alcança a carga prevista do dia.
- Hora extra em dia escalado é somente o período realmente logado após o fim previsto e dentro da extensão autorizada pelo ADM (+1h/+2h). Intervalo/pausa não cria hora extra artificial.

## Saldo acumulado

- Foi removido o campo separado **Saldo p/ compensar**.
- Existe somente um **Saldo acumulado** do banco de horas.
- O saldo acumulado recebe automaticamente **somente horas extras efetivamente realizadas**.
- Horas normais não aumentam o saldo acumulado.
- Déficits não reduzem automaticamente o saldo acumulado.
- O saldo não zera na mudança de mês: permanece entre competências até ser usado.
- Quando o ADM usa/reserva horas para abono/compensação, o valor já é descontado do próprio saldo acumulado.
- Ajustes manuais permanecem apenas como correção administrativa auditada.

## Correção de históricos

- O saldo acumulado não usa mais débitos históricos de `bankDeltaMinutes` para montar o total.
- Linhas antigas negativas deixam de contaminar o acumulado.
- Para compatibilidade histórica, créditos positivos antigos continuam sendo reconhecidos quando não houver o detalhamento moderno de horas extras.
- Jornadas reprovadas não geram crédito de horas extras.

## Interface

- `Total trabalhado` no Banco de Horas foi renomeado para **Total logado**.
- O espelho mensal também passa a identificar a coluna oficial como **Total logado**.
- `Saldo acumulado` informa que representa horas extras acumuladas já descontadas quando usadas/reservadas.
- O colaborador vê o mesmo saldo acumulado na própria jornada.

## Banco de dados

Não há nova tabela nem migration Prisma nesta versão. A alteração é de regra de cálculo e leitura dos dados existentes.
