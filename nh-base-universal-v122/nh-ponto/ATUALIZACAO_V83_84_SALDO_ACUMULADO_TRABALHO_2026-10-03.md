# V83.84 — Saldo acumulado por horas trabalhadas

## Alteração principal

A tela Banco de Horas deixa de usar créditos/ajustes/reservas para compor a coluna **Saldo acumulado**.

A partir desta versão:

- `Saldo total do mês = horas normais trabalhadas + horas extras - déficit efetivo`;
- `Saldo acumulado = soma histórica de horas normais trabalhadas + horas extras - faltas/atrasos efetivos`;
- ajustes manuais, compensações e reservas permanecem visíveis em campos próprios e não alteram o indicador de Saldo acumulado;
- o saldo disponível para compensações continua sendo calculado separadamente para preservar as regras de uso/abono do banco de horas.

## Horas extras

A coluna **Horas extras** apresenta somente o total de tempo extra. A legenda visual de `50%` e `100%` foi removida desta tela. As classificações continuam disponíveis internamente para cálculos financeiros/folha onde forem necessárias.

## Exemplos

- 8h previstas e 8h trabalhadas: normais 8h00, extras 0h00, déficit 0h00, saldo total do mês +8h00.
- 8h previstas e 8h10 trabalhadas: normais 8h00, extras +0h10, déficit 0h00, saldo total do mês +8h10.
- 8h previstas e 7h30 trabalhadas, com 0h30 de atraso/falta: normais 7h30, extras 0h00, déficit -0h30, saldo total do mês +7h00.

## Histórico e atualização automática

A V83.84 mantém a reconstrução automática já existente na V83.83. Ao abrir Banco de Horas, a competência selecionada é recalculada a partir das marcações aprovadas. O servidor também atualiza o mês corrente periodicamente e, no primeiro start, reconstrói o mês atual e o anterior.

Nenhuma marcação, selfie, jornada ou registro anterior é apagado.
