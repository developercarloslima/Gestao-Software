# Gestão Software + Base Universal — proteção dos dados de clientes

Este documento registra os controles introduzidos no endurecimento de segurança e os
requisitos operacionais para produção. **Não representa certificação, pentest nem
garantia de ausência de vulnerabilidades.** O tratamento de CPF, endereço, vídeos,
informações financeiras, contratos e biometria exige revisão contínua de segurança e privacidade.

## Controles implementados nesta etapa

1. **Assinatura de sessões:** `AUTH_TOKEN_SECRET` obrigatório, exclusivo de cada
   aplicação/empresa, nunca uma chave previsível incluída no código. A aplicação
   rejeita chaves de exemplo e sessões superiores a 24 horas.
2. **Anti-força-bruta:** limites por conta e endereço remoto para erros de login;
   limitação separada de solicitações de reset, confirmação e verificações sensíveis.
   Logins bem-sucedidos não consomem cota da conta genérica de colaboradores.
   O controle em memória é apenas uma camada de defesa: não substitui limites
   distribuídos (WAF/API gateway/Redis) e reinicia a cada nova instância.
3. **2ª via financeira:** rotas legadas que retornavam boletos a partir de CPF
   sem autenticação ou baixavam URL arbitrária respondem HTTP 410. A 2ª via válida
   exige sessão da Área do Associado em `/api/associate-portal/second-copy`.
4. **Cabeçalhos do navegador:** no Gestão, `nosniff`, bloqueio de framing,
   `Referrer-Policy: no-referrer`, restrição de capacidades e CSP mínima; na Base,
   a política de referenciador também evita propagar URLs com tokens de aceite.

## Antes de liberar empresas reais

- Configurar um segredo aleatório distinto para o Control Plane e para **cada**
  instância da Base via Render/VPS; nunca usar o segredo em GitHub, frontend ou
  arquivos públicos. A ausência do segredo agora impede que o backend inicie.
- Adicionar proteção distribuída por IP, ASN e conta no ponto de entrada,
  com alerta de tentativas anômalas e retenção limitada de IPs.
- Habilitar MFA/passkey obrigatório para Carlos, Pedro e administradores de empresas,
  incluindo recuperação segura e registro de auditoria; avaliar a política de
  contas genéricas, pois senhas compartilhadas limitam a atribuição de ações.
- Auditar autorização por empresa e função para **cada endpoint**, incluindo
  recursos identificados por UUID, PDFs, imagens, contratos, upload e eventos;
  criar testes negativos interempresas para evitar IDOR e isolamento rompido.
- Conduzir análise de vulnerabilidades e teste de intrusão autorizado em ambiente
  de homologação, com verificação de dependências (SCA), SAST, secrets scanning e DAST.
- Garantir backup criptografado e restauração ensaiada do PostgreSQL e dos objetos,
  com política de retenção/descarte e controle de acesso mínimo por serviço.
- Proteger dados sensíveis em trânsito (TLS) e repouso, restringir acesso ao Neon,
  proteger credenciais de provedores financeiros, revogar segredos periodicamente,
  evitar CPF/tokens/boletos em logs, métricas ou URLs de terceiros.
- Definir políticas de LGPD (bases legais, finalidade, consentimento quando aplicável,
  acesso, correção, eliminação, incidente e resposta ao titular) com assessoria jurídica.

## Verificação de implantação

1. Confirmar `AUTH_TOKEN_SECRET` nos serviços **sem mostrar seu valor**.
2. Testar login de um usuário único e da conta genérica; múltiplos logins válidos
   não devem bloquear usuários. Após falhas repetidas, deve retornar 429.
3. Testar a solicitação de reset com conta existente e inexistente; resposta não
   deve revelar se existe usuário.
4. Verificar que `/api/consultar-boletos-associado` e `/api/baixar-boleto`
   respondem 410 e que `/api/associate-portal/second-copy` requer autorização.
5. Conferir cabeçalhos nas páginas e reconfirmar login, implantação, cotação,
   vistoria, upload, termos e relatórios por perfil/empresa após o deploy.

**Observação:** ao alterar um segredo de sessão, os tokens antigos deixam de ser
válidos e as pessoas precisarão entrar novamente. Planejar a rotação em janela
operacional e comunicar os clientes.
