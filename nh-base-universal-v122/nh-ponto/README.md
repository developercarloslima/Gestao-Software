# Apontamento Digital — estado atual V83.50

## Regra atual da marcação

A V83.50 remove a geocerca do fluxo de ponto. Cada ação exige selfie e captura latitude/longitude do GPS do aparelho no mesmo registro. A localização é apenas evidência para análise humana: não existe raio, comparação com o endereço da unidade ou bloqueio automático por distância. Toda marcação fica pendente (`REVIEW`) e só passa a valer após ADM/RH abrir a selfie, conferir as coordenadas e aprovar com a proteção administrativa.

As descrições antigas de geocerca, Geoapify e validações automáticas abaixo são histórico técnico de versões anteriores e não representam a regra atual.

---


## Hierarquia, empresas e permissões

O sistema possui um **Super Adm global** (`comercial@nhprotecao.com.br` / `@Aguias2025`) que não participa da jornada. Ele cria empresas, vê todos os administradores e colaboradores e define quais empresas cada Admin pode gerenciar.

Administradores usam uma única Central de Gestão. Um seletor muda a empresa em contexto e todas as consultas são filtradas no backend pela permissão `AdminTenantAccess`. Um Admin pode receber uma ou várias empresas.

Na Novo Horizonte, a antiga conta **Adm Novo Horizonte** foi unificada à função do Super Adm. A antiga conta **Rh Novo Horizonte** passa a ser **Adm Novo Horizonte**, com senha temporária `nh2027` e troca obrigatória no primeiro acesso. O aviso de troca continua aparecendo até a senha ser alterada.

Colaboradores continuam vinculados a uma única empresa. **Administradores comuns (ADMIN) também participam da jornada e devem registrar ponto normalmente; somente o SUPER_ADMIN global é isento.** Para administradores com acesso a várias empresas, a jornada pessoal permanece vinculada a uma empresa-base e o seletor de empresa altera apenas o contexto de gestão.

## Autenticação e jornada

Administradores usam passkey nos recursos administrativos protegidos após concluírem a troca da senha temporária. Colaboradores usam usuário + senha e selfie simples nas marcações.

## Identidade visual por empresa

O Super Adm mantém a criação de empresas, cores e importação do Manual da Marca em PDF.


---

## Histórico técnico de versões anteriores

# PontoProof v0.3.8 — Primeiro acesso biométrico otimizado

**Sistema operacional de jornada com prova digital de identidade, presença e integridade.**

A v0.3 transforma a marcação em uma cerimônia verificável: o colaborador inicia/encerra jornada, intervalo ou pausa, realiza selfie ao vivo com desafio aleatório, o servidor compara o rosto com as referências cadastradas, cruza as provas de presença configuradas e só então grava o evento no ledger encadeado por hash.

> **Status:** MVP avançado para teste/piloto controlado. Não declare este projeto como REP-P homologado/pronto para produção trabalhista sem completar AFD/AEJ, requisitos formais aplicáveis, testes de segurança, revisão jurídica/LGPD e validação operacional.

## Regras obrigatórias da v0.3

Estas camadas não podem ser desligadas pelo Admin/RH:
- selfie em toda marcação;
- exatamente um rosto;
- cadastro facial prévio;
- face match no servidor;
- liveness + ação guiada;
- antispoof;
- bloqueio quando o núcleo biométrico falha;
- registro da tentativa bloqueada sem transformá-la em ponto;
- ledger imutável para marcações aceitas/revisadas.

Eventos cobertos: `CLOCK_IN`, `BREAK_START`, `BREAK_END`, `PAUSE_START`, `PAUSE_END`, `CLOCK_OUT`.

## Cadastro do colaborador e primeiro acesso

Admin/RH cria o usuário com senha temporária, mas **não cadastra o rosto em nome dele**. No primeiro login, cada usuário troca a senha, reconhece o aviso biométrico, envia uma selfie frontal e registra uma credencial WebAuthn/passkey do dispositivo. O perfil é liberado após essas etapas sem aguardar o processamento facial; a análise continua em segundo plano e o sistema notifica por pop-up quando aprovada ou quando exigir recaptura. A conclusão biométrica definitiva só é registrada quando a face fica `ACTIVE`.

## Marcação

1. Sessão autenticada e colaborador ativo.
2. Ciência do aviso biométrico vigente.
3. Challenge/nonce único e expiração curta quando online.
4. Câmera frontal e ação aleatória: piscar/virar/olhar para cima ou baixo.
5. Exatamente um rosto, qualidade mínima, liveness e antispoof.
6. Face match calculado no servidor contra embeddings criptografados que não são devolvidos ao colaborador.
7. GPS/precisão/geocerca e telemetria de plausibilidade.
8. Dispositivo lógico e, se exigido, dispositivo previamente confiável.
9. Opcionalmente WebAuthn com Windows Hello/Touch ID/Face ID/passkey, QR dinâmico, gateway de rede local, Bluetooth beacon e NFC.
10. ProofScore explicável + decisão `APPROVED`, `REVIEW` ou `BLOCKED`.
11. Marcações aceitas/revisadas entram no ledger SHA-256 encadeado; tentativa bloqueada fica em trilha separada.

## ProofScore

`100/100 — INTEGRIDADE_MÁXIMA` significa que **todas as provas aplicáveis/configuradas para o modo web foram satisfeitas**. Não significa fraude matematicamente impossível.

O PWA não finge possuir capacidades nativas. Play Integrity/App Attest, detecção forte de root/jailbreak e leitura confiável de SSID ficam explicitamente fora do modo web. Em compensação, esta versão usa HTTPS/localhost, WebAuthn, biometria facial, liveness, antispoof, geolocalização, challenge, provas ambientais e ledger.

## Proteções extras

- bloqueio biométrico temporário após N falhas consecutivas;
- alerta para RH quando o bloqueio é acionado;
- plausibilidade de deslocamento entre marcações;
- sinalização de GPS com precisão inválida, posição antiga ou velocidade reportada extrema;
- `clientEventId` idempotente;
- fila offline cifrada em IndexedDB com chave não extraível;
- offline nunca recebe integridade máxima automática e vai para revisão quando configurado;
- selfies armazenadas cifradas em AES-256-GCM;
- retenção automática de selfie, tentativas bloqueadas e referências faciais revogadas;
- expurgo de selfie oficial sem alterar campos que participam do hash do ledger;
- dados de auditoria de IP/User-Agent armazenados somente como hash quando usados no aviso biométrico;
- imagens biométricas disponíveis somente para Admin/RH nos fluxos de revisão.

## Provas ambientais opcionais

### QR dinâmico
Admin/RH gera um QR HMAC de curta duração por unidade. O token contém unidade, expiração e nonce.

### Gateway da rede local
`apps/presence-gateway` é um serviço opcional que roda dentro da LAN/VLAN da unidade e emite token HMAC de 60 segundos contendo `worksiteId` + `gatewayId`. Use HTTPS/reverse proxy local em produção.

### Bluetooth/NFC
Podem ser exigidos por política quando navegador e dispositivo suportarem. São sinais complementares, não substituem o núcleo facial.

## Biometria do dispositivo

WebAuthn usa o autenticador da plataforma (por exemplo, Windows Hello, Touch ID ou Face ID) com verificação do usuário. O PontoProof armazena a chave pública/contador da credencial, não a impressão digital bruta.

## Privacidade

Antes da primeira marcação o colaborador precisa reconhecer o aviso de tratamento biométrico vigente. O sistema registra versão + hash do aviso + data/hora. Isso é evidência de ciência do produto e **não substitui a definição da base legal e demais obrigações da organização**.

## Stack

- Web/PWA: React 19 + TypeScript + Vite
- ML facial web: `@vladmandic/human`
- WebAuthn: `@simplewebauthn/browser` + `@simplewebauthn/server`
- API: Fastify + TypeScript
- Banco: PostgreSQL 16 + Prisma
- Integridade: SHA-256 + payload canônico + advisory lock PostgreSQL
- Biometria em repouso: AES-256-GCM
- Deploy: Docker Compose + Nginx

## Teste local no Windows

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\local-test.ps1
```

O script executa `npm install`, copia os modelos faciais para o PWA, sobe PostgreSQL, gera Prisma, faz `db push`, seed e **executa o build completo antes de iniciar**.

Acesse:
- Web: `http://localhost:5173`
- API: `http://localhost:3333`
- Health: `http://localhost:3333/health`

Contas demo: `admin@demo.com`, `admin2@demo.com`, `rh@demo.com`, `rh2@demo.com`, `colaborador@demo.com`, `colaborador2@demo.com` — senha temporária `Demo@123`.

O seed não cria rosto fictício. Cada conta faz o próprio onboarding no primeiro login: troca a senha temporária, reconhece o aviso biométrico, cadastra a própria leitura facial e registra a biometria/passkey do dispositivo.

Veja `TESTE-LOCAL-V0.3.md` e `docs/QA_V0.3.md`.

## Deploy

Produção exige HTTPS para câmera/WebAuthn e uma chave própria `BIOMETRIC_ENCRYPTION_KEY`. O `docker-compose.prod.yml` usa volume persistente separado para evidências biométricas.

```bash
cp .env.production.example .env.production
# configure os segredos/domínio
set -a
. ./.env.production
set +a
./deploy/first-deploy.sh
```

> O deploy de teste ainda usa `prisma db push`. Antes de produção formal, gere migrations versionadas e troque o fluxo para `prisma migrate deploy`.

## Documentação

- `docs/IMPLEMENTED_V0.3.md`: matriz dos 20 pontos + etapas A/B.
- `docs/SECURITY_BIOMETRICS.md`: modelo de segurança e limitações honestas do PWA.
- `docs/QA_V0.3.md`: roteiro de teste funcional/antifraude.
- `docs/VALIDATION_V0.3.md`: validações executadas nesta entrega.
- `docs/ARCHITECTURE.md`, `docs/API.md`, `docs/COMPLIANCE.md`: documentação anterior + base arquitetural.


## Correção v0.3.2

O schema Prisma foi corrigido para enums multilinha. O teste local agora executa `prisma validate` antes de gerar o client.

## Primeiro acesso obrigatório (v0.3.4)
O ADM/RH cria a conta com uma senha temporária, mas não cadastra a foto do usuário. No primeiro login, qualquer perfil precisa concluir: (1) troca de senha, (2) ciência do aviso biométrico, (3) envio da selfie facial e (4) biometria/passkey do dispositivo via WebAuthn. Depois da etapa 4 o perfil é liberado mesmo que a análise facial ainda esteja `PENDING/PROCESSING`; o resultado chega por pop-up. Marcações sensíveis continuam aplicando a política facial: enquanto pendente usam WebAuthn em modo provisório e, se a análise pedir recaptura, a marcação fica bloqueada até a correção.


## Contas demo v0.3.5

Para testes locais, o seed cria seis contas ativas, todas com senha temporária `Demo@123` e onboarding obrigatório no primeiro acesso:

| Perfil | Login 1 | Login 2 |
|---|---|---|
| ADM | `admin@demo.com` | `admin2@demo.com` |
| RH | `rh@demo.com` | `rh2@demo.com` |
| Colaborador | `colaborador@demo.com` | `colaborador2@demo.com` |

O papel Supervisor continua disponível no produto, mas não é criado como conta demo neste seed.



## Recuperação automática das fotos antigas no Render (v0.4.5)

Se um primeiro acesso foi iniciado numa versão que armazenava as fotos faciais em `/tmp`, o PontoProof detecta automaticamente esse job legado. O sistema **não reinicia o onboarding inteiro**: mantém a senha nova, a ciência biométrica e a digital/passkey já cadastrada e volta somente para **frontal → esquerda → direita**.

As novas imagens ficam temporariamente criptografadas no PostgreSQL até o processamento terminar, então sleep, restart ou redeploy do Render não fazem o job perder as fotos novamente. Depois da decisão, as imagens temporárias são removidas e o reconhecimento usa o embedding facial criptografado.

## Persistência do onboarding facial no Render (v0.4.4)

As fotos do cadastro facial assíncrono ficam criptografadas temporariamente no PostgreSQL enquanto aguardam o worker. Isso evita perda de evidência quando o filesystem efêmero do Render é reiniciado. Após a decisão biométrica, as imagens temporárias são removidas e apenas os embeddings criptografados necessários ao reconhecimento permanecem.

## Desempenho facial v0.4.9

O cadastro facial usa uma única selfie frontal compacta para gerar o template. O servidor pré-aquece os modelos, inicia o job imediatamente após o envio e encerra automaticamente qualquer processamento que ultrapasse o watchdog.

A meta em instância já ativa é concluir o cadastro facial em poucos segundos; o watchdog encerra jobs presos em até 12 segundos. Login e marcação facial também possuem deadlines próprios. O PontoProof registra `processingMs` para medição em produção.

**Importante:** o plano Free do Render dorme após inatividade. Um cold start da plataforma está fora do controle do código e pode exceder 10 segundos. Para uma garantia operacional de 10 s, utilize um serviço Render always-on (Starter ou superior) ou outro compute sempre ativo.

### Leitura facial rápida nas marcações

A v0.4.9 mantém o pipeline Human fora do navegador durante a batida de ponto. O funcionário confirma primeiro a biometria/passkey do aparelho, a câmera abre imediatamente e captura uma selfie após uma ação simples (virar à esquerda/direita ou olhar para cima/baixo). O backend, com o motor facial pré-aquecido, calcula face match, qualidade, liveness e antispoof. O cliente não baixa/compila modelos de IA para registrar ponto.

O endpoint de marcação possui deadline de 8,5 segundos para o processamento facial; se a infraestrutura estiver lenta, a tentativa retorna erro em vez de ficar presa indefinidamente.


## Integração Novo Horizonte

Esta cópia foi integrada ao projeto principal como **Apontamento Digital** sem alterar os identificadores internos dos workspaces (`@pontoproof/*`), evitando quebrar o build original.

Acessos padrão:
- `comercial@nhprotecao.com.br` / `@Aguias2025` — Super Administrador global
- `Adm Novo Horizonte` / `nh2027` — Administrador da Novo Horizonte (troca obrigatória no primeiro login)
- `Rh Novo Horizonte` / `nh2027` — RH da Novo Horizonte (troca obrigatória no primeiro login)
- `colaborador` / `nh2027` — Colaborador

O acesso web integrado usa `/ponto/` e a API usa `/ponto-api/`. O banco do Apontamento Digital é separado do banco principal da plataforma.


## Gestão administrativa V83.11

Antes de aprovar ou rejeitar uma justificativa, ADMIN/RH/SUPERVISOR podem abrir **Visualizar marcações** e conferir as batidas do colaborador na data solicitada; supervisores continuam limitados à própria equipe. A integração do painel principal da Novo Horizonte usa API interna autenticada por token e não cria conta técnica nem sessão artificial. Para entrar na gestão completa do Apontamento Digital, o administrador usa sua própria conta do Apontamento Digital.


## Privacidade biométrica v0.6.2

Selfies de marcação são processadas de forma efêmera e não são persistidas. A selfie do onboarding fica criptografada somente enquanto o job facial está pendente e é apagada assim que o processamento termina ou exige recaptura. A V83.12 expurga automaticamente as fotos legadas na primeira inicialização, preservando os templates vetoriais necessários ao reconhecimento e os hashes históricos das marcações.

ADMIN pode gerenciar as biometrias de cada colaborador em Gestão > Equipe > Biometrias: excluir facial, revogar credenciais WebAuthn/passkeys individualmente, revogar todas ou excluir todas as biometrias. O servidor nunca recebe a impressão digital/Face ID/Touch ID brutos do aparelho.
