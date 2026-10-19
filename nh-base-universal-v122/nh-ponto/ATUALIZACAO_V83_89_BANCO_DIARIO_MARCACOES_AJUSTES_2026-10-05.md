# ATUALIZAÇÃO V83.89 — BANCO DIÁRIO POR MARCAÇÕES, JUSTIFICATIVA COM SELFIE E AJUSTES AUDITADOS

Data: 05/10/2026

## Banco de horas

- A apuração oficial voltou a ser exclusivamente por **marcações de ponto**.
- Cada dia é independente: `saldo do dia = hora extra reconhecida - déficit + ajuste manual do dia`.
- O saldo de um dia não é carregado para o cálculo do dia seguinte.
- O **Banco mensal** é a soma dos saldos diários somente da competência selecionada.
- O **Banco acumulado** permanece separado: guarda os créditos de horas extras acumulados entre competências e é reduzido quando esses créditos são utilizados/ajustados.
- Dias futuros não entram na apuração.
- A tela do banco ganhou detalhamento diário por colaborador com previsto, trabalhado pelas marcações, extra, déficit, ajuste manual, saldo do dia e situação.

## Contadores gerenciais

- Removido o indicador **Tempo total logado** da interface.
- Contadores de tempo efetivo, intervalo e pausa ficaram somente na área de gestão/Administrador como indicadores de produtividade.
- Esses contadores não alteram banco de horas, espelho ou apuração oficial.

## Justificativa de atraso

- Após a tolerância, o colaborador informa somente o **motivo por escrito**.
- O sistema exige uma **selfie tirada na hora** antes do envio.
- O horário solicitado é o horário do servidor no momento do envio; não existe mais campo manual de hora de chegada.
- A selfie fica disponível para conferência do ADM/RH/Supervisor antes da decisão.

## Ajustes administrativos

- Registros criados por ajuste administrativo não aparecem mais como falha de selfie/face/localização.
- A interface identifica claramente **AJUSTE ADMINISTRATIVO** e explica que não se trata de prova biométrica de presença.

## Ajuste manual do banco

- Formulário simplificado com **Adicionar horas** / **Retirar horas**.
- Horas e minutos são informados separadamente.
- Campo obrigatório **Dia de referência**.
- Motivo obrigatório.
- Todo ajuste manual aparece no **Espelho de pontos**, com dia de referência, crédito/débito, valor, motivo, responsável e data/hora do lançamento.
- Alterações manuais passam a compor o hash/snapshot do aceite mensal do espelho.

## Banco de dados

O schema Prisma adiciona à `AdjustmentRequest`:

- `selfieStorageKey`
- `selfieCapturedAt`

O deploy existente executa `prisma db push`, portanto a sincronização ocorre ao subir o `ponto-api`. Não apagar volumes/bancos.
