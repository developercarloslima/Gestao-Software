# V83.77 — Jornadas/escalas, bloqueio de entrada e Leandro multiempresa

## 1. Gestão de jornadas e escalas
A aba **Jornadas e escalas** passa a ser administrada exclusivamente por **SUPER_ADMIN** e **ADMIN**.

É possível:
- criar uma jornada/escala;
- editar uma jornada/escala;
- ativar ou desativar;
- excluir;
- definir cada dia da semana como trabalhado ou folga;
- configurar entrada, saída e intervalo em minutos para cada dia;
- acompanhar o total de horas trabalhadas por dia e por semana;
- vincular o modelo a um ou vários colaboradores em lote.

A configuração detalhada fica em `Shift.daySchedules`. Ao vincular a escala, o sistema também copia os horários para `Employee.workSchedule`, mantendo uma fotografia operacional da jornada do colaborador.

### Sincronização do vínculo
- **Editar modelo:** todos os colaboradores ainda vinculados ao `shiftId` recebem os novos `daySchedules`.
- **Excluir modelo:** a relação `shiftId` é removida pelo banco (`onDelete: SetNull`), mas a cópia em `Employee.workSchedule` permanece. Assim ninguém fica sem os horários que já estavam configurados.

Todas as operações de criação, edição, exclusão e vínculo geram eventos de auditoria.

## 2. Bloqueio de entrada fora do dia/horário
Foi criado o serviço `work-schedule-access.service.ts` como autoridade de janela de entrada.

Para `CLOCK_IN`, o sistema verifica:
1. se existe jornada/escala vinculada ou jornada individual;
2. se o dia está marcado como trabalhado;
3. se não é um feriado fechado pela política existente;
4. qual é o horário previsto de entrada;
5. qual é o horário previsto de saída;
6. se a marcação está dentro da janela permitida.

### Regra de 15 minutos
A entrada fica disponível exatamente **15 minutos antes** da entrada cadastrada.

Exemplo:
- jornada: segunda-feira, 08:00–18:00;
- abertura do ponto: **07:45**;
- 06:00: bloqueado;
- 07:15: bloqueado;
- 07:44: bloqueado;
- 07:45: liberado;
- 08:00: liberado;
- durante a jornada: liberado, caso ainda não exista entrada;
- depois de 18:00: bloqueado para nova entrada.

A referência “07:15” não corresponde a 15 minutos antes de 08:00; corresponde a 45 minutos antes. Por isso a implementação usa 07:45.

### Defesa em profundidade
A verificação de escala não depende somente da interface. Ela ocorre no backend em três pontos do fluxo:
- `/punches/precheck`;
- `/punches/challenge` (antes da selfie);
- `POST /punches` (antes da gravação definitiva).

A última verificação usa o horário efetivo do servidor, evitando que uma interface antiga ou chamada direta à API contorne a regra.

A liberação administrativa de hora extra/reabertura já existente continua funcionando como exceção explícita e auditável.

## 3. Domingos
Domingo deixa de ser fechado por regra global. A partir da V83.77:
- domingo marcado como trabalhado na escala pode ter jornada normal;
- domingo desmarcado continua sendo folga e bloqueia a entrada;
- feriados nacionais/estaduais continuam seguindo a política existente.

A tela de calendário foi ajustada para indicar que domingos seguem a escala do colaborador.

## 4. Leandro multiempresa
O usuário **Leandro**, papel `TIME_REVIEWER`, passa a receber no login a lista de todas as empresas operacionais criadas no sistema.

No cabeçalho aparece o seletor **Empresa para conferência**. Ao trocar a empresa:
- Presença hoje passa a mostrar a empresa selecionada;
- Aprovação de jornadas passa a usar a empresa selecionada;
- Espelho de pontos passa a usar a empresa selecionada.

O backend valida o `X-Tenant-Id` enviado pelo frontend e permite ao `TIME_REVIEWER` apenas tenants operacionais existentes.

### Permissões que não mudaram
A troca de empresa não amplia o papel do Leandro. Ele continua sem acesso a:
- cadastro/edição/exclusão de colaboradores;
- criação/edição/exclusão de jornadas/escalas;
- salários e folha;
- banco de horas administrativo;
- locais e departamentos;
- configurações;
- auditoria global;
- monitoramento de tela;
- reset de senha/biometria.

A passkey do Leandro continua pertencendo à conta dele, enquanto a prova de aprovação é vinculada à empresa selecionada.

## 5. PWA e versão
- Health da API: `0.8.13-v83.77`.
- Cache PWA: `apontamento-digital-v83-77-jornadas-escalas-multiempresa`.
- Nenhuma migration nova foi adicionada.
