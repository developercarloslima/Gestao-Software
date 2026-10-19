# V83.93 — Ajustes de marcações centralizados

Data: 06/10/2026

## Objetivo

Simplificar a rotina do Administrador/RH/Supervisor. Em vez de lançar quantidades de horas/minutos no banco, a gestão passa a corrigir diretamente as marcações reais da jornada por colaborador e por dia.

## Nova central “Ajustes da equipe”

- Acesso direto no menu lateral para perfis de gestão.
- Filtro por dia e busca por colaborador, matrícula, setor ou unidade.
- Visão geral dos colaboradores com:
  - jornada do dia;
  - entrada;
  - início do intervalo;
  - retorno do intervalo;
  - saída;
  - horas trabalhadas pelas marcações;
  - saldo do dia;
  - banco mensal;
  - banco acumulado.
- Cada horário existente pode ser clicado e corrigido diretamente.
- Marcações ausentes podem ser adicionadas com tipo, data, horário e motivo.
- O ajuste é feito por horário; não é necessário informar quantidade de horas/minutos.

## Auditoria e integridade

- A marcação original não é apagada.
- Uma correção cria uma nova marcação administrativa imutável e marca a anterior como substituída pela solicitação aprovada.
- O responsável pelo ajuste, motivo, horário anterior e horário novo ficam registrados na auditoria.
- O banco do dia é recalculado automaticamente após a correção.
- Se a correção mover uma marcação para outro dia, os dois dias são recalculados.

## Espelho de ponto

Foi adicionada a seção “Ajustes de marcação” ao espelho com:

- dia;
- tipo de marcação;
- horário anterior;
- horário ajustado;
- motivo;
- responsável pelo lançamento.

A alteração também passa a fazer parte do snapshot do aceite mensal; portanto, um ajuste posterior invalida o aceite anterior e exige nova assinatura da versão atualizada.

## Banco de horas

- Removido da interface o formulário “Adicionar ou retirar horas”.
- Correções passam a ser realizadas pelas marcações.
- A compensação/abono com saldo acumulado continua disponível.
- Lançamentos manuais históricos existentes no banco são preservados para rastreabilidade.

## Interface

A tela foi reorganizada como uma central única, privilegiando seleção de colaborador/dia, visualização imediata dos horários e ação de correção em poucos passos.
