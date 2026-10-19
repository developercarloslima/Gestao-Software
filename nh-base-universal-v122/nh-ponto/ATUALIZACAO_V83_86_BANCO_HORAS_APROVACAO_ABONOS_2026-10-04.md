# V83.86 — Banco de horas: lançamento na finalização, reprovação, dias futuros e abonos

## Objetivo

A V83.86 corrige o comportamento do Banco de Horas para refletir somente jornadas que realmente aconteceram e aplica imediatamente o período trabalhado assim que a jornada é finalizada, mesmo antes da aprovação administrativa.

## Jornada finalizada aguardando aprovação

- Ao registrar `CLOCK_OUT`, a jornada é recalculada e gravada imediatamente no ledger.
- Jornadas em `REVIEW` entram provisoriamente em **Horas normais**, **Horas extras**, **Total trabalhado** e no saldo correspondente assim que forem finalizadas.
- Aprovar a jornada não duplica nem acrescenta horas; apenas confirma o valor que já estava contabilizado.
- Jornada ainda aberta não entra no saldo do banco.

## Reprovação de jornada

Quando o Administrador/Super Adm reprova a jornada completa:

- todas as horas trabalhadas daquele dia são removidas do cálculo;
- horas normais e extras daquele dia passam a zero;
- a jornada prevista do dia é lançada como déficit integral;
- o saldo é recalculado imediatamente.

Exemplo solicitado:

- 3 jornadas finalizadas de 8h = 24h contabilizadas imediatamente;
- 1 jornada é reprovada;
- horas normais válidas = 16h;
- déficit da jornada reprovada = 8h;
- saldo de trabalho = `16h + 0h extras - 8h déficit = 8h`.

## Dias futuros

- Dias futuros não são persistidos no ledger.
- O resumo e o saldo acumulado ignoram qualquer linha futura que possa ter sido criada por versões anteriores.
- Ao atualizar o Banco de Horas, resíduos de ledger com data posterior ao dia atual são removidos automaticamente.
- Reservas futuras de compensação continuam preservadas, porque são registros próprios e não jornadas futuras.

## Falta integral

Quando existe jornada prevista e o colaborador não registra nenhuma entrada, a falta integral é consolidada assim que o horário previsto da jornada termina. A tolerância de saída não posterga uma falta de quem não iniciou a jornada.

Exemplo: jornada 08:00–18:00, com 2h de intervalo. Sem nenhuma marcação, após 18:00 entram 8h de déficit.

## Fórmula exibida

O demonstrativo mantém a regra:

`Saldo do mês = Horas normais + Horas extras - Déficit`

Exemplo:

- quarta: 8h trabalhadas;
- quinta: 8h trabalhadas;
- sexta: falta de 8h;
- resultado: `16h + 0h - 8h = 8h`.

O **Saldo acumulado** usa a mesma lógica somente em dias ocorridos. O **Saldo p/ compensar** permanece separado e representa horas extras/créditos efetivamente disponíveis para abono.

## Abono/compensação pelo Administrador

A tela Banco de Horas agora permite usar saldo positivo para abonar por:

- **hora/minutos** — informa a quantidade; também permite programar compensação futura parcial;
- **dia inteiro** — usa o déficit consolidado do dia;
- **semana** — soma os déficits consolidados da semana de referência;
- **mês** — soma os déficits consolidados da competência de referência.

Para dia/semana/mês, apenas déficits já consolidados são utilizados. Para uma compensação futura parcial, deve ser usado o modo hora/minutos.

Exemplo: colaboradora possui 6h disponíveis e, numa jornada de 8h, foi autorizada a trabalhar apenas 2h. O ADM pode reservar 360 minutos para o dia. Quando a jornada fechar, o sistema aplica somente o déficit real e libera eventual sobra automaticamente.

Todas as operações continuam auditadas.

## Interface

A tabela passou a destacar separadamente:

- Horas normais;
- Horas extras;
- Total trabalhado;
- Déficit;
- Saldo do mês;
- Saldo acumulado;
- Saldo para compensar;
- Ajustes;
- Compensado;
- Reservado.

A descrição da tela informa explicitamente que jornada finalizada pendente já é contabilizada e que dias futuros não entram no saldo.

## Arquivos principais alterados

- `nh-ponto/apps/api/src/services/time-engine.service.ts`
- `nh-ponto/apps/api/src/services/bank-hours-maintenance.service.ts`
- `nh-ponto/apps/api/src/services/auto-close.service.ts`
- `nh-ponto/apps/api/src/routes/punches.ts`
- `nh-ponto/apps/api/src/routes/time.ts`
- `nh-ponto/apps/web/src/components/management/Bank.tsx`

## Banco de dados

Esta alteração não exige nova tabela nem migration Prisma. Ela reutiliza `TimeBalanceLedger` e `BankHourAdjustment` existentes.
