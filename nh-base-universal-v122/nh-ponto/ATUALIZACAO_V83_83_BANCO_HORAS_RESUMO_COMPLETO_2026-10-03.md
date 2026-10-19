# V83.83 — Banco de horas com resumo completo e atualização automática

## Objetivo

O Banco de Horas deixa de exibir apenas saldo/extra/déficit e passa a demonstrar todas as grandezas relevantes da competência mensal:

- horas previstas;
- horas normais efetivamente trabalhadas;
- horas extras efetivamente trabalhadas;
- total trabalhado;
- déficit de horas;
- saldo líquido do mês (extras - déficit restante);
- saldo acumulado disponível no banco;
- ajustes manuais;
- compensações aplicadas;
- horas reservadas para compensações futuras.

## Regra de cálculo

Exemplo de jornada prevista de 8h:

- trabalhou 8h00: normais 8h00, extras 0h00, total 8h00, déficit 0h00;
- trabalhou 8h10 com saída manual: normais 8h00, extras +0h10, total 8h10, saldo do mês +0h10;
- trabalhou 7h30: normais 7h30, extras 0h00, total 7h30, déficit -0h30, saldo do mês -0h30.

A finalização automática do sistema não cria crédito artificial: o trecho acrescentado apenas pela marcação automática é retirado das grandezas usadas pelo banco.

## Atualização automática e retroativa controlada

- A abertura da tela Banco de Horas recalcula automaticamente a competência selecionada.
- O servidor também faz manutenção automática a cada 15 minutos.
- No primeiro start da V83.83, o mês atual e o mês anterior são reconstruídos para garantir que jornadas já realizadas nesta semana apareçam corretamente.
- O marco da política V83.82 pode ser recuado somente até a segunda-feira da semana corrente, de forma auditada, para incorporar jornadas já realizadas antes do deploy nesta mesma semana.
- Nenhuma marcação de ponto, selfie, jornada ou histórico é apagado.

## Competência

A tela ganhou seletor mensal. O Administrador pode consultar o mês atual e competências anteriores. Meses futuros são bloqueados.

## Dados históricos

O ledger diário é reprocessado a partir das marcações aprovadas já existentes. Isso permite preencher horas normais, extras, total trabalhado e déficit sem criar batidas artificiais.
