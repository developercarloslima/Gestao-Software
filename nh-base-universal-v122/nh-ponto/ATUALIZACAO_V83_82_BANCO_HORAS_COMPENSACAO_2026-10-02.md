# V83.82 — Banco de horas por tempo real + compensação administrada

Data: 2026-10-02

## Regra principal

A partir do marco da V83.82, o motor de jornada separa quatro grandezas:

1. **tempo previsto** pela jornada/escala;
2. **tempo realmente trabalhado** entre as marcações válidas, descontando intervalo/pausa;
3. **crédito de banco** quando o tempo efetivamente trabalhado supera o previsto;
4. **déficit** quando o tempo efetivamente trabalhado fica abaixo do previsto.

### Crédito

- Todo minuto excedente efetivamente trabalhado entra no banco quando a empresa está em modo Banco de Horas.
- A regra vale inclusive para os minutos que antes eram tratados como tolerância na saída manual.
- Exemplo: jornada líquida prevista de 8h e trabalho real de 8h10 → crédito de 10 min.
- Entrada antecipada efetivamente trabalhada também participa da diferença.

### Saída automática

- A finalização automática do sistema não gera crédito pelo trecho automático posterior ao horário previsto.
- O motor limita esse trecho ao fim previsto exclusivamente para o cálculo do banco.
- O tempo efetivamente comprovado antes disso continua preservado.

## Déficit e uso do saldo

- Horas faltantes são calculadas em minutos reais.
- O déficit fica visível separadamente e **não consome automaticamente o saldo positivo**.
- ADMIN/SUPER_ADMIN escolhem quanto do saldo será usado para abonar o déficit.
- O valor escolhido reduz as horas faltantes e consome a mesma quantidade do saldo disponível.
- O sistema bloqueia uso acima do saldo disponível ou acima do déficit/jornada permitida.

## Compensação futura

O ADM pode programar uma compensação futura, por exemplo:

- colaborador possui 3h disponíveis;
- ADM autoriza que no dia seguinte comece às 11h em vez do horário normal;
- registra 180 min de compensação para aquele dia.

O sistema reserva o saldo. No fechamento da jornada:

- aplica somente o déficit real;
- se faltaram menos minutos que o reservado, usa apenas o necessário;
- se não houve déficit, cancela a reserva e devolve todo o saldo automaticamente.

## Justificativas

Na tela de justificativas de falta o ADM possui duas decisões distintas:

- **Aprovar / abonar**: mantém o comportamento de justificativa formal e zera a falta conforme a regra já existente;
- **Usar banco de horas**: informa quantos minutos serão utilizados do saldo e o motivo. Apenas esses minutos são compensados.

## Histórico preservado

Foi criado `bankPolicyV2At` em `TimeRuleSettings`.

- dias anteriores ao marco continuam usando a regra histórica;
- recálculos antigos não migram silenciosamente para a nova política;
- `TimeBalanceLedger`, `Punch` e ajustes históricos não são apagados;
- saldos acumulados antes da V83.82 continuam compondo o saldo disponível.

## Dados e auditoria

`BankHourAdjustment` passa a registrar:

- `kind`: `MANUAL` ou `COMPENSATION`;
- `status`: `PLANNED`, `APPLIED` ou `CANCELLED`;
- `requestedMinutes`;
- `workDate`.

Eventos de auditoria registram criação, aplicação, liberação e cancelamento de compensações.

## Interface

Na aba Banco de horas:

- saldo disponível;
- ajustes manuais;
- total compensado;
- total reservado;
- extras;
- déficit;
- formulário de uso/programação de saldo por colaborador, data, minutos e justificativa.

O colaborador visualiza o saldo **disponível**, já descontando reservas programadas.

## Banco de dados

Há alteração de schema Prisma. O deploy da plataforma executa `prisma db push` antes da API.
Não apagar banco nem volumes.
