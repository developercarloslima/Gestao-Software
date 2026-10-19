# Atualização v83.96 — Jornada normal sem aprovação + histórico de presença

Data: 07/10/2026

## Regras aplicadas

- Jornada iniciada dentro da tolerância normal não entra mais em fila de aprovação.
- Marcações regulares válidas são gravadas como `APPROVED` imediatamente.
- Ao finalizar a jornada, o dia é consolidado diretamente no banco de horas e fica disponível no espelho de pontos.
- Somente faltas e entradas atrasadas mantêm fluxo de justificativa/aprovação.
- Saída automática continua registrada e auditável, porém não cria fila de aprovação de jornada.
- Registros `REVIEW` legados de versões anteriores são tratados como marcações regulares válidas pelo motor de cálculo, sem bloquear banco/espelho.

## Presença hoje / histórico

- A aba Presença hoje permanece disponível para Administrador, Super Adm e responsável de ponto (Leandro/TIME_REVIEWER).
- Novo botão `Ver presenças anteriores`.
- Ao abrir, é exibido um calendário (`input date`) para escolher dia/mês/ano.
- Consulta permitida entre hoje e os últimos 365 dias.
- O mesmo detalhamento do dia atual fica disponível no histórico: marcações, horários, status, selfie, GPS, precisão, unidade/local e observações.
- A atualização automática a cada 30 segundos fica ativa somente na data de hoje; consultas históricas permanecem estáveis até atualização manual ou troca de data.

## Aprovação

- A antiga fila de aprovação diária de jornadas normais foi removida da área de segurança.
- A tela de aprovação passa a destacar justificativas de atraso pendentes e informa que faltas são analisadas na área própria de justificativas.
- TIME_REVIEWER pode analisar justificativas de atraso, mantendo o perfil operacional do responsável de ponto.

## Compatibilidade

- Nenhuma migration Prisma nova foi necessária.
- Não há exclusão de marcações, selfies, banco de horas ou histórico.
- Cache da PWA atualizado para v83.96.
