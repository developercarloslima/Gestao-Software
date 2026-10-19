# Apontamento Digital — V83.94 / 0.8.x

## V83.94 — Intervalo mínimo de 105 minutos (06/10/2026)

Após **Iniciar intervalo**, o **Retorno do intervalo** permanece inativo por 105 minutos. O frontend exibe a contagem regressiva e o horário de liberação, e o backend aplica a mesma trava nos endpoints de precheck, challenge e gravação final para impedir retorno antecipado.


## V83.92 — justificativas de atraso configuráveis

- O Super Adm define, por empresa, como será a justificativa de entrada após a tolerância: **Variada** ou **Profissional**.
- **Variada:** motivo digitado + selfie tirada no momento da justificativa, com GPS capturado junto da selfie.
- **Profissional:** motivo digitado + selfie tirada no momento + declaração de horas ou comprovante de presença. Se for enviada sem documento, a solicitação é registrada como **reprovada automaticamente**.
- Justificativas válidas ficam pendentes e precisam de aprovação dos perfis administrativos autorizados antes de criar a marcação de entrada.
- No espelho assinado pelo administrador e pelo colaborador aparece somente o resumo da justificativa (manual / declaração de horas / comprovante de presença), resultado e nome do responsável pela decisão.
- Somente **Super Adm** e o responsável de ponto **Leandro (TIME_REVIEWER)** possuem o botão **Ver justificativa** no espelho para abrir motivo, selfie, documento e responsável pela análise.
- A tela de aprovação dos administradores exibe selfie e documento comprobatório quando houver, para permitir a conferência antes da decisão.
- A política V83.91 do banco de horas diário por marcações permanece intacta.
- O logout automático de 10 minutos da V83.90 permanece ativo e não encerra a jornada em andamento.

### Banco / Prisma

A V83.92 acrescenta campos ao `AdjustmentRequest` para guardar o tipo de justificativa, documento comprobatório e GPS da selfie, e adiciona `lateJustificationMode` em `TimeRuleSettings`. O deploy do `ponto-api` usa `prisma db push`, portanto o schema é sincronizado sem apagar os dados existentes.

## V83.93 — Ajustes de marcações centralizados (06/10/2026)

A gestão agora corrige a jornada diretamente pelas marcações do colaborador. A nova central **Ajustes da equipe** mostra horários e saldos por dia e permite corrigir ou incluir entrada, intervalo, retorno e saída sem lançar horas/minutos manualmente. O registro original é preservado, a correção é auditável, o banco é recalculado e o espelho de ponto informa horário anterior, horário ajustado, motivo e responsável.
