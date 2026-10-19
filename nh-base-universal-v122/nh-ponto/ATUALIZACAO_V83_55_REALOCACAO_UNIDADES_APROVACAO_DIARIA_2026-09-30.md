# V83.55 — realocação de unidades e aprovação diária consolidada

Data: 30/09/2026

## Exclusão de unidades

- ADMIN e SUPER_ADMIN podem iniciar a exclusão de uma unidade.
- Se houver colaboradores ativos vinculados, a exclusão não é encerrada imediatamente: abre um modal com a lista completa dos colaboradores vinculados.
- Para cada colaborador é possível selecionar outra unidade ativa para realocação.
- Colaboradores operacionais também podem ser arquivados pelo assistente, preservando histórico; contas ADMIN, HR, SUPER_ADMIN e o próprio usuário que executa a ação precisam ser realocadas.
- O modal possui **Cancelar**, **Salvar todas realocações** e **Salvar e excluir unidade**.
- As realocações, arquivamentos e exclusão da unidade são auditados.
- O DELETE sem corpo não envia mais `Content-Type: application/json`, corrigindo o `FST_ERR_CTP_EMPTY_JSON_BODY` observado nos logs.

## Aprovação da jornada

- Cada ação (entrada, intervalo, retorno, pausa, retorno e saída) continua registrando horário, selfie e GPS imediatamente.
- As marcações permanecem em `REVIEW`, mas a fila de aprovação diária só recebe a jornada depois de existir `CLOCK_IN` e `CLOCK_OUT` para o dia.
- A aprovação individual de uma marcação foi desativada. A decisão é única para a jornada diária inteira.
- Apenas ADMIN e SUPER_ADMIN podem aprovar/reprovar a jornada diária.
- A lista mostra colaborador, data, início, finalização, quantidade de evidências e status **PENDENTE DE APROVAÇÃO**.
- **Ver informações** abre horários, latitude/longitude e precisão de cada selfie, unidade vinculada/endereço/coordenadas de referência e miniatura de cada foto, com **Ver imagem completa**.
- Reprovação exige justificativa obrigatória no frontend e no backend.
- Após a decisão, todas as marcações pendentes do dia recebem a mesma decisão e o espelho/ledger é recalculado uma única vez.
- Jornadas ainda pendentes não geram falta/débito antes da decisão.
- Quando um colaborador tinha jornada prevista e não possui jornada aprovada naquele dia, o cálculo marca automaticamente a falta no histórico e no espelho após o término da jornada do dia.

## Central de Risco

- Novos alertas passam a usar o padrão: **“Marcação de NOME DO COLABORADOR aguardando revisão de integridade”**.
- Alertas antigos ainda não resolvidos com código `PUNCH_REVIEW` também são apresentados com o nome do colaborador ao carregar a Central de Risco.

## Coordenadas da unidade

- Latitude/longitude da unidade voltam a ser campos opcionais de referência cadastral para comparação manual com o GPS das selfies.
- Isso **não reativa geocerca**: não há raio, distância máxima, aprovação/reprovação automática nem bloqueio por localização.

## Painel principal e erros 400/502

- O painel principal deixa de oferecer aprovação individual e encaminha o Administrador para **NH Ponto > Aprovação de jornadas**.
- Respostas 400/401/403/404/409/410/422 recebidas do serviço NH Ponto deixam de ser convertidas genericamente em 502 pelo backend principal.
- O 502 fica reservado a falhas reais de upstream/servidor.

## Banco de dados

- Nenhuma nova coluna ou migração é necessária na V83.55.
- Histórico e bancos existentes são preservados.
