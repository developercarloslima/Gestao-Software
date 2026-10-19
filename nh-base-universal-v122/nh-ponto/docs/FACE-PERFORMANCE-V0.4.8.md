# Desempenho facial — v0.4.8

## Objetivo

O cadastro e a leitura facial não podem permanecer carregando indefinidamente. Em operação normal com a API já ativa, o alvo continua sendo poucos segundos; em falha técnica, a operação deve encerrar de forma controlada e permitir nova tentativa.

## Proteções adicionadas

- deadline de carregamento do motor facial: 8 s;
- deadline de warmup: 6 s;
- detector leve: 2,5 s por inferência;
- descriptor facial: 5 s por inferência;
- pipeline seguro de login/marcação: 7 s por inferência;
- watchdog de cadastro: 15 s para detectar jobs `PENDING/PROCESSING` presos;
- reset do cache do motor após timeout, evitando reutilizar uma instância problemática;
- fotos criptografadas preservadas em falha técnica para retry sem nova captura;
- recuperação automática de submissions antigas presas após atualização/restart.

## Primeiro acesso

A ordem continua: senha temporária -> ciência biométrica -> captura facial -> WebAuthn. O WebAuthn pode ser cadastrado enquanto a face é processada e, depois de concluído, o perfil é liberado sem esperar a face ficar `ACTIVE`. A análise segue em segundo plano e o usuário recebe um pop-up com aprovação, necessidade de recaptura ou falha técnica recuperável. A conclusão biométrica definitiva continua exigindo face `ACTIVE`.

## Observação de segurança

O motor self-hosted usa comparação facial, liveness/antispoof nas operações protegidas e WebAuthn para a biometria do dispositivo. Isso melhora bastante a experiência e evita travamento, mas não deve ser descrito como certificação bancária. Para requisitos regulatórios/antifraude equivalentes aos de instituições financeiras, use um provedor especializado de face-liveness/identity proofing com SLA e certificações próprias.
