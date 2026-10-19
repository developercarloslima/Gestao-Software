# Gestão Software v102 — Implantação Guiada e Segurança de Lançamento

**Situação:** homologação / NÃO liberada para clientes externos. Este documento descreve implementações observadas no código e pendências que impedem marcar o produto como concluído.

## Experiência do operador (sem conhecimentos técnicos)

1. **Empresas > Cadastrar:** preencha os dados da organização, escolha os módulos, plano e identidade visual. Após salvar uma empresa nova, a interface abre automaticamente **Implantação guiada** e seleciona a empresa.
2. **Dimensionamento:** responda oito perguntas com exemplos (funcionários, pico de acessos, vendas e cobranças mensais em **quantidade**, dias, marcações, tamanho de selfie e retenção). Marque se há totem facial ou disponibilidade reforçada e escolha o provedor. Clique **Analisar capacidade** e **Confirmar e salvar análise**. Os módulos são preenchidos com base no contrato; não mudam sozinhos.
3. **Contrato:** conclua assinatura do cliente e da Gestão; o painel revela o próximo passo sem supor que um clique já representa assinatura.
4. **Cobrança:** escolha a fatura, gere a cobrança Asaas e obtenha o link. Verifique o recebimento; status PAID alterado manualmente **não** desbloqueia o deploy.
5. **Provisionamento:** a tela lista os pré-requisitos. Quando faltam, o botão permanece desabilitado e explica o motivo. Provedores sem conector ativo e grandes empresas são bloqueados para revisão.
6. **Entregar:** acompanhar estados `QUEUED`, `PROVISIONING`, `VERIFYING`, `ACTIVE`, `FAILED`. A URL só aparece após HTTPS e UUID do cliente confirmados. O operador pode copiar o endereço validado para envio por canal confiável.

Acessibilidade: títulos claros, rótulos explicativos, `aria-live` em mensagens, foco visível, botões de altura confortável, agrupamento responsivo de 2, 3 ou 6 passos conforme largura, preferência por movimento reduzido e avisos junto às ações.

## Backend: proteções novas

- **GET** `/api/platform/implantacao/capacidades`: retorna opções e se o provedor é *realmente provisionável*.
- `TENANT_TEMPLATE_CERTIFIED=false` impede solicitações a agentes antes de uma base liberada (valor padrão: false; não contorne apenas para remover o alerta).
- `PROVISIONING_HOST_SUFFIX` limita hosts novos ao domínio da Gestão autorizado. URLs de cliente fora do sufixo exigem outro fluxo de verificação/implantação, ainda não implementado.
- `X-Provisioner-Vps-Id` é obrigatório no registro de resultados e deve corresponder à VPS do job.
- Token exclusivo por VPS: `HMAC-SHA256(master-token, UUID-da-VPS)`. A chave mestra fica apenas na Gestão; token derivado vai para cada agente.
- Pagamento continua exigindo confirmação independente no Asaas, igualdade do valor e `externalReference` da fatura.
- Validação externa de HTTPS + endpoint `/api/health/tenant` antes de liberar a URL; ainda recomenda-se DNS pinning/anti-DNS rebinding antes de produção.

## Agente VPS: salvaguardas novas

- `--check` valida configuração sem exigir ativação nem criar recursos.
- `--validate-job caminho.json` verifica a identidade da VPS, host, perfil e CNPJ sem instalar.
- Requer **imagens Docker imutáveis referenciadas por SHA256**, não tags mutáveis `latest`.
- Rejeita CNPJ inválido (numérico, com dígitos verificadores) e não inventa documentos fictícios.
- Escrita de segredos por arquivo temporário, permissões 0600, `fsync`, renomeação atômica.
- Implantações conservam bancos/volumes específicos por empresa. Restauração de backups deve ser testada antes da produção.

## Validação realizada aqui

- Java: 17 classes do backend Gestão analisadas pelo parser do JDK sem erros **de sintaxe**. Não substitui compilação Maven nem teste de integração.
- JavaScript: `node --check` em `app.js` e `onboarding.js`.
- Estrutura HTML: IDs únicos na página administrativa, controles de seis etapas presentes, ausência de botões de implantação ativos por padrão.
- YAML: `docker-compose.yml`, `docker-compose.prod.yml`, workflow de publicação das seis imagens parseados.
- Cinco testes unitários de contrato/simulação de agente e UI estática passaram.
- Auditoria na base NH v83.116: **67 arquivos / 159 ocorrências** de identidade/dados/segredos legados. A certificação white-label **REPROVOU**, bloqueando a implantação automática corretamente.
- Browser real e renderização responsiva: execução bloqueada pelo navegador do ambiente de teste; **não validado**. Não há Docker/Maven disponível para executar o build completo nesta sessão.

## Roteiro técnico de homologação antes do lançamento

- Executar `docker compose up -d --build` em **ambiente isolado**, nunca alterar banco de clientes para teste; acompanhar logs e backup.
- Validar autenticação do painel Gestor, sessão, CSRF/CORS, MFA e permissões. Testar observação de consoles em 390px, 768px, 1440px com leitores de tela e usuários sem formação técnica.
- Aplicar migrations Flyway na Gestão e banco NH zerado; recuperar dump de banco existente separadamente. Validar também falha/retry e migrações fora de ordem.
- Configurar **Asaas Sandbox**; testar fatura recebida, atrasada, estornada, idempotência de webhook e replay.
- Construir e auditar seis imagens Docker. Conferir imagens pelo digest no registry e CVEs.
- Testar agente num servidor descartável com domínio de homologação e certificado válido; recuperar um backup antes de ativar a primeira empresa.
- Testar dois tenants com CNPJ/contas/domínios distintos; comprovar que tokens, selfie, arquivos, faturas, event logs e relatórios não vazam de um cliente para outro.
- Testar fluxo completo cadastro → assinatura → pagamento → job → containers → DNS/HTTPS → login inicial/troca de senha → módulos contratados ativos; testar rollback para versão anterior.
- Rodar testes de carga por porte, inclusive os horários de entrada de ponto, vídeos e anexos; observar CPU, RAM, IOPS, limites do banco e filas.

## Integrações ainda necessárias para o produto final

| Item | Estado e próximo trabalho |
|---|---|
| Base NH sem legado NH | **Bloqueado.** Converter PDFs, mensagens, fluxos comerciais, assets, seeds, passkey RP e migrações específicas em configurações independentes por tenant. Audit script não substitui revisão funcional e jurídica. |
| VPS gerenciada | Agente inicial e composição parametrizada. Falta configuração homologada Traefik/DNS/backups, CI verde e credenciais/entrega inicial. |
| VPS do cliente | Acesso autorizado, instalação guiada do agente e fluxo de diagnóstico/rede/sistema operacional. |
| Render | Criador/reconciliador de serviços, DB, env, domínio, TLS e rollback via API; proteger gastos e verificar quotas. |
| AWS | Automatização de ECS/Fargate, ECR, RDS, S3, balanceador, DNS, KMS/Secrets Manager, backup, observabilidade, autoscaling, quotas e orçamento. |
| Entrega segura ao cliente | Convite temporário por e-mail, identidade do destinatário, expiração de link e alteração de senha em primeiro acesso. Não enviar senhas em texto pelo painel. |
| Operações | Rotina de backups-restauração, certificados, alertas, logs, auditoria, atualização de imagens, filas órfãs/retries e disaster recovery. |
| Homologação UX | Sessões moderadas com pessoas leigas; testes por teclado, leitor de tela, celular; corrigir dúvidas observadas antes do lançamento. |

**ATENÇÃO:** alterar `TENANT_TEMPLATE_CERTIFIED=true` antes de concluir os testes e obter aprovação formal é uma falha de processo. Não criar serviços ou despesas reais automaticamente enquanto o produto estiver nesse estado.
