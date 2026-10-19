# V83.94 — intervalo mínimo de 105 minutos antes do retorno

Data: 06/10/2026

## Regra aplicada

- Depois que o colaborador registra **Iniciar intervalo (BREAK_START)**, o botão **Retorno do intervalo (BREAK_END)** fica bloqueado por **105 minutos**.
- A tela mostra o horário exato em que o retorno será liberado e uma contagem regressiva.
- Ao completar 105 minutos, o botão volta a ficar disponível automaticamente.
- A restrição é validada também no backend em `/punches/precheck`, `/punches/challenge` e no envio final de `/punches`, portanto não pode ser contornada apenas alterando a interface.
- A regra vale para a marcação normal do colaborador. Ajustes administrativos auditáveis continuam disponíveis para a gestão corrigir situações excepcionais.
- Nenhuma migration de banco é necessária.
- As regras da V83.93 (ajustes centralizados), V83.92 (justificativas), V83.91 (banco por marcações) e V83.90 (logout por inatividade) permanecem preservadas.
