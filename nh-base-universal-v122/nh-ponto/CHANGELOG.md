## V83.89 — 2026-10-05

- Banco de horas diário calculado por marcações, sem carregar o saldo do dia anterior para o dia seguinte.
- Banco mensal separado do banco acumulado.
- Removido Tempo total logado da interface; contadores de produtividade ficam somente na gestão.
- Justificativa de atraso simplificada: motivo escrito + selfie, usando horário do servidor.
- Ajuste manual com dia de referência, adicionar/retirar horas e responsável no espelho.
- Ajustes administrativos deixam de exibir alertas técnicos de selfie/face/localização como erro.

## V83.88 — 2026-10-04

- Banco de horas passa a usar o tempo total logado para a apuração oficial.
- Intervalo, pausa e tempo efetivo ficam apenas nos contadores gerenciais.
- Saldo acumulado passa a ser exclusivamente o crédito permanente de horas extras, já descontado quando utilizado/reservado.
- Removido o campo separado “Saldo p/ compensar”.
- Corrigida a leitura de históricos para ignorar débitos antigos no acumulado.
- Espelho e banco passam a identificar o total oficial como “Total logado”.

## V83.87 — 2026-10-04
- Gravação de tela removida do NH Ponto.
- Mantidos somente: tempo total logado, tempo efetivo de trabalho, intervalo e pausa.
- Gestão passa a exibir “Contadores de tempo”, sem vídeo, sessão de captura ou tela monitorada.
- API não possui mais endpoints operacionais para iniciar captura, enviar chunks ou assistir gravações.

# V83.77 — Jornadas/escalas + entrada protegida + Leandro multiempresa
- SUPER_ADMIN e ADMIN podem criar, editar, excluir e vincular jornadas/escalas com dias e horários específicos.
- Entrada abre 15 minutos antes do horário previsto (08:00 → 07:45) e é bloqueada fora do dia/horário escalado.
- Validação de CLOCK_IN ocorre na pré-checagem, desafio da selfie e gravação final no backend.
- Domingos passam a seguir a escala do colaborador; feriados continuam fechados conforme a política vigente.
- Leandro/TIME_REVIEWER recebe seletor entre todas as empresas operacionais, mantendo apenas Presença hoje, Aprovação de jornadas e Espelho de pontos.
- Cache PWA e health atualizados para V83.77.

# V83.75 — Reset de senha e biometria pelo Super Adm
- SUPER_ADMIN pode resetar senha de qualquer usuário das empresas cadastradas.
- Reset de senha gera credencial temporária aleatória e força troca no próximo login.
- SUPER_ADMIN pode resetar passkeys/WebAuthn e referência facial de qualquer usuário da empresa.
- Selfies das marcações são preservadas.
- Usuários inativos também aparecem na gestão global para recuperação de acesso.
- Todas as ações de reset são auditadas no backend.

# V83.44 — Administrador também bate ponto
- Todo usuário `ADMIN` ativo passa a possuir vínculo `Employee` para jornada.
- Somente o `SUPER_ADMIN` global permanece fora do ponto.
- Administradores existentes recebem ou reativam automaticamente o vínculo de jornada no seed, sem apagar histórico antigo.
- Novos administradores criados pelo Super Adm já recebem empresa-base de jornada.
- O seletor de empresa do Admin continua servindo à gestão multiempresa; rotas pessoais de ponto usam sempre a empresa do vínculo `Employee`, evitando marcação na empresa errada.
- Admin passa a visualizar Registrar ponto, Minha jornada e Ajustes, além das telas administrativas.
- Admin passa a aparecer em Equipe com jornada para configuração de unidade, escala, salário e demais dados de jornada.
- Adicionado `/adjustments/me` para a aba pessoal de ajustes do Admin/colaborador.
- O perfil `ADMIN` não pode ser criado nem rebaixado pela tela de colaboradores; essa gestão continua exclusiva do Super Adm.

# v0.6.2
- Fotos biométricas deixam de ter retenção após o processamento.
- Expurgo automático de selfies legadas na primeira inicialização.
- ADMIN pode excluir facial, WebAuthn/passkeys individuais, todas as credenciais ou todas as biometrias.
- Sessão SSO do administrador principal ignora onboarding de colaborador.

# v0.5.1

- Salário passa a ser responsabilidade explícita do Administrador/RH; criação e edição de colaborador exigem salário base válido.
- Colaborador não recebe mais valores salariais pela rota de jornada e a tela “Minha jornada” exibe somente horas, faltas, extras e banco.
- Supervisores continuam vendo jornada da equipe, mas não recebem salário ou cálculo financeiro.
- Novo espelho gerencial do RH mostra salário, valor/hora, valor/minuto e valor/segundo, desconto de faltas, extras 50%/100% e estimativa após horas.
- Cálculo financeiro passa a usar a duração real das marcações em segundos; os campos em minutos são mantidos por compatibilidade.
- Espelho individual exibe o detalhamento diário e pode ser impresso.
- Referência padrão permanece 220h mensais com DSR e 44h semanais.
- Health/version atualizado para `0.5.1`.

# v0.5.0

- Libera o perfil após senha + ciência + envio da selfie + WebAuthn, sem aguardar aprovação facial.
- Mantém a análise facial assíncrona em segundo plano.
- Exibe pop-up de aprovação, recaptura ou retry técnico no perfil.
- Permite refazer a selfie diretamente do pop-up sem perder senha, aceite ou passkey.
- Mantém marcação provisória por WebAuthn enquanto a face está PENDING/PROCESSING e bloqueia nova marcação quando a face precisa de correção.

# Changelog

## 0.4.9 — Face rápida, mobile, trava por turno e cálculo de horas

- Cadastro facial do primeiro acesso reduzido para **uma selfie frontal**, com imagem compacta e processamento somente no servidor.
- Login facial reduzido para uma selfie e uma inferência segura no backend.
- Watchdog/deadlines mantidos para impedir análise facial presa indefinidamente; falha técnica permite reprocessar sem perder a selfie.
- Primeiro acesso obrigatório: senha nova, ciência biométrica, face aprovada e biometria digital/passkey.
- Navegação responsiva revisada para celular, tablet e desktop; botão **Sair** incluído no mobile.
- Cada ação de ponto pode ser usada uma única vez por turno; botão fica marcado com ✓ e desabilitado, com validação também no backend.
- Referência de jornada padrão ajustada para **44h/semana** e divisor salarial **220h/mês com DSR**.
- Minha Jornada e Prévia de Folha passam a exibir horas faltas + desconto estimado e horas extras + valor de referência/a receber.
- Dias futuros do mês não são contabilizados como faltas em prévias em andamento.
- Seed deixa de resetar senha/estado de primeiro acesso de contas já existentes durante deploy.
- Cache PWA e health atualizados para `0.4.9`.

## 0.4.8 — Face watchdog + primeiro acesso estrito

- Corrige o caso em que o cadastro facial podia ficar indefinidamente em `PROCESSING` (ex.: 15%) se o motor Human/WASM não resolvesse uma inferência.
- Carregamento, warmup e detecção facial agora possuem deadlines internos; timeout reseta o pipeline para permitir nova tentativa.
- Watchdog converte jobs presos em erro recuperável em vez de manter spinner infinito.
- Fotos criptografadas do cadastro são preservadas em falha técnica, permitindo **Tentar novamente** sem recaptura.
- Jobs antigos já presos há minutos/dias são recuperados automaticamente no próximo status/onboarding.
- Capturas do primeiro acesso foram reduzidas para 420 px/JPEG 72%, diminuindo upload e decodificação sem afetar o tensor final usado pelo modelo.
- Selfie de login/marcação também foi compactada antes do envio para reduzir latência.
- O desafio `BLINK` agora é instruído no cliente e reconhecido no servidor.
- Primeiro acesso passa a ser estrito: o painel só é liberado depois de **troca de senha + ciência biométrica + face aprovada + biometria/passkey cadastrada**.
- A biometria digital pode ser cadastrada enquanto a face está processando, mas isso não libera o painel antes da aprovação facial.
- Cache do PWA atualizado para forçar os navegadores a receberem esta correção.
- Health/version atualizado para `0.4.8`.

## 0.4.7 — Fast Face SLA

- Cadastro facial passa a iniciar imediatamente após o upload, sem esperar o timer do worker.
- Worker assíncrono consulta a fila a cada 500 ms.
- Motor facial de cadastro dividido em modo detector e modo descriptor leve.
- Apenas a foto frontal gera a assinatura facial; esquerda/direita validam presença e qualidade, removendo duas inferências pesadas.
- Selfies são reduzidas internamente para 224–288 px antes da inferência WASM.
- Motores de face são pré-aquecidos depois que a API abre a porta HTTP.
- Motor seguro (liveness + antispoof) aquece depois, sem disputar CPU com o cadastro inicial.
- Login facial usa uma inferência segura + uma inferência leve, com deadline de 8,5 s.
- O navegador deixa de carregar IA facial para bater ponto: ele apenas abre a câmera e envia uma selfie compacta.
- Face match, qualidade, liveness, antispoof e validação do gesto da marcação passam a ser calculados no backend já aquecido.
- Desafios de marcação usam poses verificáveis em uma única imagem (esquerda, direita, cima, baixo), eliminando espera de modelos no celular.
- Enquanto o app permanece aberto, uma chamada de health a cada 4 minutos mantém a instância ativa durante a jornada.
- Tempo de processamento do cadastro facial passa a ser registrado para medição do SLA.

> Meta operacional: 3–8 s com a instância já ativa. O plano Free do Render pode levar cerca de um minuto para acordar após 15 min sem tráfego; para SLA real de até 10 s, use instância always-on.

## 0.4.6 — acesso liberado durante análise facial + login biométrico

- O usuário não fica mais preso na tela de primeiro acesso enquanto o servidor processa as 3 fotos.
- Assim que senha, ciência biométrica, envio das fotos e passkey/biometria do dispositivo estiverem concluídos, o usuário já pode entrar no PontoProof mesmo com a face em `PENDING/PROCESSING`.
- Durante a análise facial, cada marcação exige somente a biometria digital/passkey; a prova fica marcada como `PROVISORIA_BIOMETRIA_DISPOSITIVO` e não recebe integridade máxima.
- Assim que o cadastro facial é aprovado, as marcações passam automaticamente a exigir biometria digital/passkey + selfie/reconhecimento facial/liveness/antispoof.
- Se as fotos forem rejeitadas, o usuário volta somente para a etapa facial e recebe um pop-up com os motivos; senha, ciência e passkey são preservadas.
- A aplicação acompanha o job facial em segundo plano e avisa com pop-up quando o cadastro for aprovado.
- Login passwordless por Windows Hello, impressão digital, Touch ID, Face ID/passkey via WebAuthn.
- Login por reconhecimento facial para usuários com template facial ativo. O navegador só captura duas imagens rápidas; a comparação é feita no backend.
- Login facial não persiste as imagens capturadas para autenticação.
- A marcação provisória durante análise facial exige internet, pois a prova WebAuthn precisa ser validada pelo servidor.
- Health/version atualizado para `0.4.6`.

## 0.4.5 — recuperação seletiva do onboarding facial

- Migração automática dos cadastros faciais iniciados nas versões que salvavam fotos em `/tmp`.
- Jobs legados `PENDING`, `PROCESSING` ou `FAILED` com evidência temporária são convertidos para `NEEDS_RETAKE`.
- A recuperação reinicia **somente a etapa das 3 fotos faciais**.
- Senha já alterada, ciência do uso biométrico e credenciais WebAuthn/passkey permanecem preservadas.
- O usuário recebe uma mensagem específica explicando que as fotos antigas expiraram por causa da atualização de armazenamento.
- O botão de retry também detecta jobs legados e encaminha para recaptura facial em vez de entrar em loop de `ENOENT`.
- Novas capturas continuam criptografadas e persistidas temporariamente no PostgreSQL até o worker concluir a análise.
- API health/version atualizada para `0.4.5`.

## 0.4.4 — Render biometric persistence fix

- Cadastro facial assíncrono não usa mais `/tmp` para fotos pendentes.
- As 3 capturas ficam temporariamente criptografadas com AES-256-GCM dentro do PostgreSQL até o worker concluir a análise.
- Sleep, restart e redeploy do Render não fazem mais o job perder as imagens.
- Fotos temporárias são removidas do banco após aprovação ou pedido de recaptura.
- O template facial aprovado permanece como embedding criptografado; a imagem bruta do onboarding não é mantida.
- Jobs antigos cujo arquivo `/tmp` já desapareceu passam para `NEEDS_RETAKE` com mensagem clara, em vez de entrar em loop de erro técnico.

# PontoProof v0.4.3

- Corrige WebAuthn no Render: RP ID e Origin são resolvidos em tempo de requisição.
- Impede `localhost` de ser usado como RP ID em produção quando `RENDER_EXTERNAL_HOSTNAME` estiver disponível.
- Mantém funcionamento local em `localhost`.
- Corrige cadastro de Windows Hello, impressão digital, Touch ID, Face ID e passkeys no domínio `*.onrender.com`.

# PontoProof v0.4.2

- Corrigido worker facial no Render/Node 22: não usa mais o subpath bloqueado `@vladmandic/human/dist/human.node-wasm.js`.
- O backend resolve a entrada pública de `@vladmandic/human`, localiza o bundle `human.node-wasm.js` pelo caminho absoluto instalado e o carrega diretamente.
- Mantido processamento facial assíncrono, WASM, três fotos, liveness, antispoof e embeddings.
- Adicionada validação explícita da existência do bundle WASM e dos modelos faciais para mensagens de erro mais claras.

# PontoProof v0.4.1

## Correção Render / worker facial

- Remove a dependência implícita de `@tensorflow/tfjs-node`, que derrubava a API no Render com `MODULE_NOT_FOUND`.
- Worker facial passa a carregar **sob demanda** o bundle `human.node-wasm`, compatível com container sem binários nativos do TensorFlow.
- Adiciona `@tensorflow/tfjs-core`, `@tensorflow/tfjs-converter` e `@tensorflow/tfjs-backend-wasm` 4.22.0.
- Falha ao inicializar o motor facial não impede mais o servidor HTTP de subir; o job fica como erro técnico e pode ser reprocessado.
- Corrige limpeza de JSON opcional do Prisma com `Prisma.DbNull`.
- Mantém o cadastro facial assíncrono: captura rápida no cliente e análise no backend.

# Changelog

## 0.4.0 - Cadastro facial assíncrono

- O primeiro cadastro facial não carrega mais IA antes de abrir a câmera.
- Captura rápida de 3 imagens: frontal, esquerda e direita.
- Fotos são criptografadas e enviadas ao backend imediatamente.
- Novo `FaceEnrollmentSubmission` persistente no PostgreSQL.
- Worker assíncrono processa qualidade, face única, liveness passivo, antispoof e embeddings.
- Validação cruzada garante que as 3 imagens pertencem à mesma identidade.
- Resultado `APPROVED`, `NEEDS_RETAKE` ou `FAILED` é consultado pelo frontend sem bloquear a tela.
- Popup informa aprovação ou quais imagens precisam ser refeitas.
- Erro técnico permite reprocessar as mesmas fotos sem obrigar nova captura.
- Fotos reprovadas são expurgadas após a decisão; fotos aprovadas viram as referências faciais ativas.
- O usuário pode cadastrar WebAuthn/passkey enquanto as fotos são processadas.
- Jobs em andamento são retomados após reinício do processo.
- Modelos Human deixaram de ser pré-carregados na tela de login/onboarding.
- A validação facial síncrona das marcações de ponto permanece separada e obrigatória.

# PontoProof v0.3.9

- Câmera biométrica abre imediatamente, sem aguardar modelos de IA.
- Modelos começam a carregar já na tela de login.
- `modelBasePath` corrigido para `/models/human/`, usando os modelos locais do próprio app.
- Cache de modelos em IndexedDB habilitado explicitamente.
- Validação de modelos desativada no cliente para reduzir o startup, sem desativar liveness/antispoof.
- Análise facial passa a usar frame interno 320x240; captura final continua em resolução maior.
- Primeiro carregamento fica oculto atrás do login/onboarding; usos seguintes aproveitam cache.

# v0.3.8

- Otimiza o início do reconhecimento facial no navegador.
- Modelos biométricos começam a carregar em segundo plano imediatamente após o login/onboarding.
- A câmera só é aberta depois que o motor facial está pronto, evitando ficar parada em “Preparando reconhecimento facial”.
- Reduz a captura padrão para 640x480/24fps, diminuindo bastante o custo da inferência local.
- Desativa somente o modelo de íris, que não é necessário para os desafios atuais (piscar, virar o rosto e mover a cabeça). Face mesh, embedding, liveness e antispoof permanecem obrigatórios.
- Adiciona cache-first dos modelos Human no Service Worker e Cache-Control de 7 dias no deploy Render.
- Exibe estado do motor facial (CARREGANDO/OTIMIZANDO/PRONTO) antes de abrir a câmera.
- Permite nova tentativa real se o carregamento dos modelos falhar.

# v0.3.7

- Render: BIOMETRIC_ENCRYPTION_KEY gerada automaticamente agora e derivada com SHA-256 quando o segredo não estiver em hex/base64 exatos.
- Mantém compatibilidade com chaves AES-256 explícitas em hex/base64.

# PontoProof v0.3.6

- Deploy de teste preparado para Render via Blueprint (`render.yaml`).
- Frontend e API agora podem rodar no mesmo domínio HTTPS usando `Dockerfile.render`.
- WebAuthn/WEB_ORIGIN podem ser derivados automaticamente de `RENDER_EXTERNAL_HOSTNAME`.
- API respeita `PORT` do provedor de hospedagem.
- Health check atualizado para v0.3.6.
- Blueprint cria serviço web e PostgreSQL gratuitos para teste.
- Evidências em filesystem continuam efêmeras no plano gratuito; produção requer persistent disk/object storage.

# v0.3.5

- Seed local agora cria 2 contas ADM, 2 contas RH e 2 contas de colaborador.
- Mantidos os logins originais como perfil 1 e adicionados `admin2@demo.com`, `rh2@demo.com` e `colaborador2@demo.com`.
- Todas as seis contas usam senha temporária `Demo@123` e entram no onboarding obrigatório de primeiro acesso.
- O papel Supervisor continua suportado pelo sistema, mas deixou de ser criado como usuário demo padrão.

# Changelog

## 0.3.3 - 2026-09-13

- Corrige o build TypeScript da rota WebAuthn: o token curto `PUNCH_BIOMETRIC` agora inclui `role` (e `employeeId`) para respeitar o contrato tipado do JWT.
- Mantém o token biométrico vinculado ao usuário, tenant e `punchChallengeId`; não altera as regras de validação da marcação.
- Atualiza banner de teste local e versão da API para 0.3.3.

## 0.3.2 - 2026-09-13

- Corrige 7 erros de TypeScript encontrados no primeiro build local da v0.3.1.
- Corrige checks de permissão com `Role[]` em ajustes, punches e segurança.
- Atualiza `AuthenticatorTransportFuture` para `AuthenticatorTransport` no SimpleWebAuthn v14.
- Expande a tipagem do JWT para o token curto `PUNCH_BIOMETRIC`, vinculado ao challenge da marcação.
- Corrige o tratamento de erro `unknown` no error handler do Fastify.
- Atualiza a versão reportada pela API para 0.3.2.
- Mantém intactas as regras de biometria facial, liveness, anti-spoof, WebAuthn, ProofScore e ledger.

## 0.3.1 - correção do teste local

- Corrige os 10 enums do `prisma/schema.prisma` para a sintaxe multilinha aceita pelo Prisma 6.19.x.
- Adiciona `npm run db:validate` antes de `prisma generate` no teste local.
- Registra aprovações `allowScripts` para Prisma/esbuild usadas no projeto, evitando os avisos de dependências pendentes do npm 11.
- Mantém todas as funcionalidades biométricas e de integridade da v0.3.0.

## 0.3.0 — 2026-09-13

### Identidade biométrica
- Foto frontal obrigatória na criação de colaborador.
- Referências FRONT/LEFT/RIGHT com revogação auditável.
- Captura de selfie em entrada, saída, intervalo, retorno, pausa e retorno.
- Detecção de exatamente um rosto, qualidade, liveness, desafio aleatório e antispoof.
- Embeddings de referência criptografados; face match calculado pelo servidor.
- Bloqueio temporário após falhas biométricas repetidas e desbloqueio administrativo auditado.

### WebAuthn
- Cadastro e revogação de biometria/passkey da plataforma.
- `userVerification: required` para prova adicional da marcação quando configurada.
- Credencial pública/counter armazenados; biometria bruta do dispositivo não é recebida.

### Proof Engine
- ProofScore normalizado por provas aplicáveis/configuradas.
- Núcleo obrigatório não pode ser desligado por Admin/RH.
- `APPROVED`, `REVIEW` e `BLOCKED`.
- Tentativa bloqueada não entra no ledger oficial.
- Offline com revisão obrigatória e teto de score quando configurado.
- Correção administrativa identificada como `AJUSTE_ADMINISTRATIVO`, sem fingir prova biométrica retroativa.

### Presença
- GPS + precisão + geocerca.
- Sinais de posição antiga, precisão inválida, velocidade extrema e deslocamento implausível.
- QR dinâmico HMAC.
- Web Bluetooth e Web NFC quando suportados.
- Presence Gateway opcional para rede local, com `worksiteId`, `gatewayId`, expiração e nonce assinados.

### Privacidade
- Aviso biométrico versionado e ciência registrada antes da marcação.
- Selfies e referências armazenadas cifradas em AES-256-GCM.
- Volume biométrico persistente separado no deploy.
- Retenção automática de selfies oficiais sem quebrar o hash do ledger.
- Retenção de tentativas bloqueadas e expurgo de referências revogadas.
- Hash de IP/User-Agent no registro de ciência, sem persistência em texto claro nesse evento.

### Gestão e revisão
- Fila de revisão com selfie da tentativa e foto-base lado a lado.
- Política de integridade configurável para camadas adicionais.
- Locais com geocerca, prefixo beacon, NFC e gateway de rede.
- Painel de colaborador mostra status facial e bloqueio biométrico.

### Operação
- `local-test.ps1` agora executa build completo antes de iniciar.
- Dockerfiles corrigidos para postinstall/modelos faciais.
- `docker-compose.prod.yml` com chaves/variáveis WebAuthn e volume biométrico.
- Serviço `apps/presence-gateway` incluído.

## 0.2.0 — 2026-09-13
- Central de gestão, motor de jornada, banco de horas, Jornada Espelho, ledger SHA-256, auditoria, idempotência offline e deploy Docker inicial.

## 0.3.4 - Primeiro acesso biométrico por usuário

- Removida a obrigação de o ADM/RH capturar a foto facial durante o cadastro do colaborador.
- Todo novo usuário recebe senha temporária e `mustChangePassword=true`.
- Primeiro login agora é bloqueado por onboarding obrigatório para ADMIN, HR, SUPERVISOR, EMPLOYEE e AUDITOR.
- Etapas obrigatórias: troca da senha temporária, ciência do aviso biométrico, cadastro facial próprio e cadastro de biometria/passkey do dispositivo via WebAuthn.
- Backend bloqueia todas as rotas operacionais enquanto o onboarding estiver incompleto; somente as rotas necessárias para concluir o primeiro acesso permanecem disponíveis.
- Cadastro facial próprio usa challenge de prova de vida, antispoof e armazenamento criptografado já existente.
- Cadastro WebAuthn usa autenticador de plataforma (Windows Hello, impressão digital, Touch ID, Face ID ou passkey compatível), sem armazenar a impressão digital bruta.
- Painel de equipe passa a exibir status de primeiro acesso, face e biometria digital.
- ADM/RH pode revogar a referência facial, mas não cadastrar o rosto em nome do usuário.
- Seed local deixa de sobrescrever a senha depois que o usuário já fez a troca no primeiro acesso.

## V83.23 — 2026-09-28
- Colaboradores passam a usar usuário/senha + selfie simples em toda ação da jornada; passkey por batida removida.
- Selfie da jornada não faz reconhecimento facial e fica pendente para aprovação de ADMIN/RH.
- Passkeys restritas a ADMIN/RH, com novo reset único e fluxo WebAuthn revisado.
- Cadastro de colaborador agora usa username obrigatório e e-mail de contato opcional.
- Totem continua identificando por face, mas a marcação usa uma nova selfie simples e não exige passkey do colaborador.

## V83.25 — 2026-09-28
- Tela de ativação do Totem refeita para responsividade completa.
- Card de ativação e área da logo passam a usar fundo azul.
- Logo de fundo azul aplicada ao fluxo de ativação.
- Adicionado botão "Voltar para o painel" para ADMIN/RH sem encerrar a sessão.
- Ajustes de largura, tipografia, inputs, avisos e botões para 280px, mobile, tablet, desktop e ultrawide.

## V83.26 — 2026-09-29
- Selfie da jornada em modo imersivo: câmera ocupa toda a área útil e mantém somente o botão de captura.
- PWA/viewport preparados para fullscreen/standalone em dispositivos compatíveis.
- Visualizador de selfie responsivo para ADMIN/RH e histórico do colaborador.
- Aprovação/rejeição exige conferência prévia da selfie, com validação também no backend.
- Painel principal da NH registra auditoria da visualização da selfie antes da decisão.

## V83.80 — 2026-10-02
- Entrada direta limitada à janela de 15 minutos antes / 15 minutos depois do início previsto.
- Após a tolerância, colaborador informa hora real de chegada e justificativa; a entrada só é criada após aprovação.
- Aprovação gera `APPROVED_CLOCK_IN_ADDITION`, preservando auditoria e permitindo continuidade da jornada/monitoramento.
- Demais ações ficam bloqueadas até existir uma entrada oficial.
- Mensagens da escala e do painel atualizadas para explicar a regra 15/15.

## V83.84 — 2026-10-03
- Banco de Horas passa a exibir horas extras em um único total, sem legenda 50%/100% nessa tela.
- Saldo total do mês = horas normais trabalhadas + horas extras - déficit efetivo.
- Saldo acumulado = soma histórica da mesma fórmula, sem misturar ajustes, compensações ou reservas.
- Saldo disponível para compensações permanece separado para preservar as regras de abono.
- Recalculo automático de competências e backfill inicial preservados.
