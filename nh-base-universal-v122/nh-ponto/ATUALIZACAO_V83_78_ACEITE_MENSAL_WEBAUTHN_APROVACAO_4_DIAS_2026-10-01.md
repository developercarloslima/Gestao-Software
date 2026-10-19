# NH Ponto V83.78 — aceite mensal WebAuthn e manutenção da aprovação

Data: 01/10/2026

## 1. Aceite mensal do espelho

- A partir do último dia de cada competência, ADMIN e SUPER_ADMIN podem registrar o aceite do espelho mensal de um colaborador usando a própria passkey/WebAuthn.
- O aceite administrativo é vinculado a empresa, colaborador, competência e hash SHA-256 do conteúdo consolidado do espelho.
- O colaborador só recebe a opção de dar seu próprio aceite WebAuthn depois de existir um aceite administrativo válido para a versão atual do espelho.
- Se marcações, aprovações ou ajustes alterarem o conteúdo consolidado depois do aceite do administrador, o hash deixa de coincidir e o aceite anterior não libera o colaborador. O administrador precisa aceitar novamente a versão atual.
- Espelhos com jornadas ainda pendentes de aprovação não podem receber o aceite mensal do administrador.
- O documento impresso passa a mostrar os registros de aceite WebAuthn do administrador e do colaborador, incluindo data/hora e identificação abreviada da credencial.
- O WebAuthn é usado como prova eletrônica de aceite dentro do sistema; esta implementação não declara certificação ICP-Brasil.

## 2. Jornadas de administradores

- Marcações de usuários ADMIN são consolidadas diretamente como aprovadas.
- A jornada finalizada continua aparecendo na aba de Aprovação de jornada para conferência e controle.
- A tela de controle não oferece aprovar/reprovar novamente uma jornada de ADMIN já consolidada.
- Jornadas antigas de ADMIN que ainda estejam em REVIEW são consolidadas automaticamente pelo serviço de manutenção.

## 3. Aprovação automática após 4 dias

- Jornadas finalizadas que permanecerem em REVIEW por 4 dias são aprovadas automaticamente pelo servidor.
- A manutenção roda periodicamente a cada 15 minutos e também faz uma execução inicial após a API iniciar.
- A operação é idempotente: uma jornada já processada não é aprovada novamente.
- Depois da aprovação automática, o ledger do dia é recalculado.
- A auditoria registra `JOURNEY_AUTO_APPROVED_AFTER_4_DAYS`.

## 4. Mensagens de tentativas bloqueadas

A área “Possíveis fraudes / falhas” mantém o dado técnico internamente, mas apresenta ao usuário textos mais amigáveis para casos como:

- selfie não confirmada;
- localização/GPS/geocerca;
- dispositivo não validado;
- confirmação de presença local (rede/QR/Bluetooth/NFC);
- confirmação de identidade/liveness;
- validação de segurança incompleta.

## 5. Banco de dados

O Prisma recebeu:

- `WebAuthnChallengePurpose.TIMESHEET_ACCEPTANCE`;
- tabela `TimesheetAcceptance`;
- relações com Tenant, Employee e User.

O deploy atual já executa `prisma db push`, portanto a nova tabela/enum são aplicados durante a subida do serviço.

## 6. PWA e API

- API: `0.8.14-v83.78`.
- Cache da PWA: `apontamento-digital-v83-78-aceite-mensal-webauthn`.
