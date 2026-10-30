# Recuperação de senha com Gmail API (Render Free)

## Estado da integração

- Gestão API: `gestao-software-v107-central-migracao/backend/.../PasswordResetMailer.java`
- NH API: `nh-base-universal-v122/backend/.../PasswordResetMailer.java`
- Envio por HTTPS, sem SMTP, pelo endpoint oficial `POST https://gmail.googleapis.com/gmail/v1/users/me/messages/send`.
- Remetente: `gestaosoftware2026@gmail.com`.
- O banco armazena apenas SHA-256 do token de redefinição, expirado em 20 minutos e uso único.
- A interface retorna mensagem genérica para não revelar cadastros.
- Logs não podem conter segredos, links de recuperação, token OAuth nem respostas completas do Google.

## Autorização no Google (uma única conta remetente)

1. Faça login como `gestaosoftware2026@gmail.com` em https://console.cloud.google.com/ .
2. Crie um projeto ou selecione um projeto dedicado aos envios da Gestão.
3. No catálogo de APIs, habilite **Gmail API**.
4. Configure Google Auth Platform: marca, público (Externo, caso conta Gmail pessoal), e adicione o remetente como usuário de teste se estiver em **Testing**.
5. Em **Data Access**, adicione **apenas** o escopo `https://www.googleapis.com/auth/gmail.send`.
6. Crie **OAuth client ID** do tipo **Web application** e cadastre como redirect URI autorizado `https://developers.google.com/oauthplayground`.
7. Acesse https://developers.google.com/oauthplayground/ , abra a engrenagem, marque **Use your own OAuth credentials**, informe client ID/secret de seu projeto, selecione **Offline** e **Consent Screen**.
8. No Step 1 informe apenas `https://www.googleapis.com/auth/gmail.send`. Clique em **Authorize APIs** e use `gestaosoftware2026@gmail.com`.
9. No Step 2 clique em **Exchange authorization code for tokens** e guarde o **refresh token** (NUNCA no GitHub, arquivos, capturas de tela ou chat).
10. Em **Render > Environment** de **cada** serviço `gs107-hml-api-virginia` e `nh122-demo-api`, configure as variáveis abaixo diretamente no painel:
   - `GMAIL_SENDER=gestaosoftware2026@gmail.com`
   - `GMAIL_CLIENT_ID=<client ID do seu projeto>`
   - `GMAIL_CLIENT_SECRET=<client secret do seu projeto>`
   - `GMAIL_REFRESH_TOKEN=<refresh token do passo 9>`
11. Salve e confirme que ambos os serviços estão **Live**. Faça pedido real pelo **Esqueci minha senha** e confirme mensagem recebida e link funcional.

> **Importante:** aplicativos OAuth Externos em status **Testing** emitem refresh tokens que geralmente expiram após **7 dias**. Para manter integração mais estável, avalie publicar o app em **In production** conforme as exigências da Google; aplicações de uso pessoal com poucos usuários conhecidos podem ter exceções à verificação de escopos sensíveis. O escopo `gmail.send` é sensível; não confunda publicação com verificação automática. A Google também pode revogar tokens em situações específicas.

## Proteção dos segredos

Nunca incluir client secret ou refresh token no código, variáveis `PUBLIC_*`, logs, URLs públicas, README com valores reais, capturas de tela ou tickets. Se expostos, revogue imediatamente no Google e substitua no Render. Não configure senha de aplicativo/SMTP: o Render Free bloqueia 25/465/587.

## Testes funcionais

- Gestão: solicite recuperação de `carlos.lima` e `pedro.henrique`. O remetente é `gestaosoftware2026@gmail.com`; o destinatário é o e-mail de recuperação já vinculado a cada conta.
- NH: teste o administrador da empresa, cujo destinatário é `contactEmail` cadastrado na Gestão para o tenant e sincronizado com a NH.
- Confira caixa de entrada/spam e a pasta **Enviados** do Gmail remetente.
- O link deve ser de uso único, válido por 20 minutos; após uso, falhar na segunda tentativa.
- Falhas de token OAuth ou acesso HTTPS são registradas apenas com categoria/HTTP, sem informações sensíveis.

Referências: https://developers.google.com/workspace/gmail/api/guides/sending ,
https://developers.google.com/identity/protocols/oauth2 ,
https://developers.google.com/oauthplayground/ .
