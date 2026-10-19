# ATUALIZAÇÃO V83.87 — CONTADORES DE TEMPO SEM GRAVAÇÃO DE TELA

Data: 2026-10-04

## Alteração principal

A gravação/captura de tela do computador foi desativada integralmente no fluxo operacional do NH Ponto. Nenhuma nova sessão de captura, trecho de vídeo, MediaRecorder ou compartilhamento de tela é iniciado pelo frontend ou aceito pelas rotas do backend.

## Contadores mantidos

- Tempo total logado: da entrada até a saída, incluindo intervalo e pausa enquanto a jornada permanece aberta.
- Tempo efetivo de trabalho: somente os períodos entre entrada/retorno e intervalo/pausa/saída.
- Tempo de intervalo.
- Tempo de pausa.

Os valores são calculados no servidor a partir das marcações oficiais da jornada e continuam atualizando em tempo real na tela.

## Gestão

A antiga aba “Monitoramento de tela” passa a se chamar “Contadores de tempo”. O Administrador consulta por colaborador e por dia os quatro contadores, sem acesso a vídeos ou imagens de tela.

## Privacidade e legado

As rotas de iniciar captura, heartbeat de captura, upload de trechos e visualização de vídeos foram removidas da API operacional. O frontend não usa getDisplayMedia nem MediaRecorder. Os jobs de retenção permanecem somente para encerrar sessões antigas e eliminar gravações históricas conforme a política de retenção já configurada; não criam novas gravações.

## Compatibilidade

Selfies obrigatórias das marcações, GPS, passkeys, banco de horas, aprovações, abonos, escalas e demais regras da v83.86 permanecem inalterados.
