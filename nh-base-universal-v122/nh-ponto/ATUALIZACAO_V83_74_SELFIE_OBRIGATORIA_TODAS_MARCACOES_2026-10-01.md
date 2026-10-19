# V83.74 — selfie obrigatória em todas as marcações manuais

Data: 01/10/2026
Base: V83.73 — celular para marcação + computador separado para monitoramento de tela.

## Regra aplicada

Toda ação manual do colaborador exige uma nova selfie capturada no momento da ação e GPS do aparelho:

- Iniciar jornada (`CLOCK_IN`)
- Iniciar intervalo (`BREAK_START`)
- Retorno do intervalo (`BREAK_END`)
- Iniciar pausa (`PAUSE_START`)
- Retorno da pausa (`PAUSE_END`)
- Finalizar jornada (`CLOCK_OUT`)

A selfie não pode ser reaproveitada e a marcação offline continua desativada.

## Regra de saída e tolerância

1. Até 10 minutos após o horário previsto de saída, o colaborador pode finalizar manualmente, mas a selfie continua obrigatória.
2. Ao atingir +10 minutos, o ponto entra em trava. Outras ações ficam bloqueadas e permanece apenas o fluxo de finalização manual com selfie.
3. De +10 até +15 minutos, a saída manual continua exigindo selfie.
4. Ao completar +15 minutos sem saída manual, o servidor cria automaticamente o `CLOCK_OUT` sem selfie.
5. `SYSTEM_AUTO_LOCK_NO_SELFIE` é a única exceção de saída sem selfie e só é aceita quando:
   - o tipo é `CLOCK_OUT`;
   - não existe ator humano (`actorUserId = null`);
   - a criação vem do serviço automático do servidor.
6. A saída automática permanece em `REVIEW` para conferência do Administrador/Super Adm.

## Proteções de backend

- O schema de `POST /punches` exige `selfieDataUrl`, latitude e longitude.
- O desafio de marcação informa `requireSelfie: true` para todas as seis ações.
- O serviço central `createImmutablePunch` rejeita marcações presenciais sem selfie/GPS.
- Tentativa de usar `SYSTEM_AUTO_LOCK_NO_SELFIE` manualmente ou para outro tipo de marcação gera `SYSTEM_AUTO_CLOSE_SOURCE_FORBIDDEN`.
- Ajustes administrativos formais (`APPROVED_CORRECTION` e `APPROVED_ADDITION`) continuam sendo registros administrativos, não prova presencial, e permanecem separados das marcações manuais do colaborador.

## Interface

- O painel informa permanentemente que toda marcação manual exige selfie.
- A tela de trava explica claramente a janela +10 → +15 minutos.
- A finalização automática informa que se trata da única exceção sem selfie.
- Telas administrativas de escalas/configurações/revisão foram atualizadas com a mesma regra.

## PWA/cache

O cache do Service Worker foi alterado para `v83-74`, evitando que celulares mantenham a interface antiga após o deploy.

## Banco de dados

Nenhuma migration Prisma foi necessária nesta versão.
