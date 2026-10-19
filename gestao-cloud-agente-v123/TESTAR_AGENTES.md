# Gestão Software — conferência do agente VPS v102

## Antes de qualquer provisionamento

1. O host deve ser **VPS Linux autorizada**, com usuário restrito e acesso ao Docker auditado. Docker concede privilégios elevados; nunca exponha a API Docker publicamente.
2. Crie um token de agente com 32+ caracteres, restrito à VPS cadastrada no painel. Neste protótipo ainda existe uma chave-mestra compartilhada entre os agentes: **não usar múltiplos clientes ou provedores em produção sem credenciais por agente**.
3. Publique as seis imagens da base **auditadas e sem branding NH** e use exclusivamente referências `registry/servico@sha256:<digest>`. Nunca use `latest`.
4. Configure DNS e TLS, rede Traefik externa, política de backup, restauração, monitoração, armazenamento e autenticação inicial.
5. Execute `python3 provisioner_agent.py --check` com `ALLOW_RESOURCE_CREATION` desligada. Isso não cria nem baixa contêineres.
6. Execute `python3 provisioner_agent.py --validate-job /caminho/job-exemplo.json` para validar campos de um job, sem instalar.
7. Somente depois da auditoria white-label, testes E2E, integração Asaas Sandbox, testes de ataque e restauração: habilite `TENANT_TEMPLATE_CERTIFIED=true` na Gestão e `ALLOW_RESOURCE_CREATION=yes` no agente. Estes passos exigem autorização expressa do administrador.

## Não há atalho seguro para uma base universal

A versão NH v83.116 ainda contém nomes, imagens, PDFs, templates e regras específicas da Novo Horizonte. As imagens correspondentes **não podem** ser promovidas a `NH_*_IMAGE` de produção para uma nova empresa. Primeiro elimine essas dependências, valide CNPJ, termos jurídicos, conteúdos dos PDFs, integrações e leis aplicáveis, e execute testes com duas empresas isoladas.
