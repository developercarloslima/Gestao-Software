# ATUALIZAÇÃO V83.92 — JUSTIFICATIVAS DE ATRASO CONFIGURÁVEIS — 2026-10-05

## Regra por empresa

O Super Adm passa a decidir como cada empresa tratará entradas depois da tolerância:

- `VARIED`: justificativa escrita + selfie ao vivo + GPS no momento do envio.
- `PROFESSIONAL`: justificativa escrita + selfie ao vivo + GPS + documento comprobatório (`HOURS_DECLARATION` ou `ATTENDANCE_PROOF`).

A tela do colaborador mostra somente o fluxo aplicável, sem informar quem configurou a regra.

## Aprovação

- Justificativas válidas ficam `PENDING` até decisão administrativa.
- No modo profissional, envio sem documento é persistido como `REJECTED` automaticamente e auditado.
- Documento, selfie e motivo podem ser conferidos na central de aprovação.
- A entrada criada por justificativa aprovada usa o horário do servidor registrado no envio da solicitação.

## Espelho de ponto

- O espelho oficial/assinado não expõe o motivo nem o documento; mostra apenas o tipo de justificativa, aprovado/reprovado e o nome do responsável pela decisão.
- Super Adm e `TIME_REVIEWER` possuem uma visão restrita com botão **Ver justificativa**, que abre motivo completo, selfie, GPS, documento e responsável pela análise.
- O acesso detalhado é auditado.

## Compatibilidade

Mantidas as regras da V83.91 (banco por marcações) e V83.90 (logout por 10 minutos de inatividade sem encerrar jornada).
