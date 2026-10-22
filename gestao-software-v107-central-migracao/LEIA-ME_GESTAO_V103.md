# Gestão Software v103 — implantação e liberação por empresa

Atualização baseada na v102.1, preservando os cadastros, planos e contratos existentes.

## Entrega
- A API de provisionamento verifica a URL por DNS, HTTPS, resposta JSON da API e identidade `tenantId`.
- A partir desta versão exige também `readyForCustomers=true`. Um contêiner inicializado **não** significa que uma empresa pode receber clientes.
- Enquanto o agente relata `VERIFYING`, o painel orienta o operador a abrir `/admin/implantacao.html` da empresa, concluir catálogo/regulamento/checklist e retornar para conferir os requisitos.
- A URL destinada ao cliente aparece apenas no estado `ACTIVE`.

## Fluxo pretendido
1. Cadastrar empresa e módulos, preencher dimensionamento e salvar a análise.
2. Assinar contrato, pagar e **confirmar pagamento diretamente no provedor**.
3. Selecionar VPS gerenciada START aprovada; para os demais ambientes, informar revisão necessária.
4. O agente instala o template homologado num banco novo.
5. Entrar no admin da nova empresa e concluir marca, acessos, catálogo, regulamento, backup e integrações conforme os módulos.
6. Conferir a implantação no painel Gestão. Após validação, publicar URL.

## Bloqueio correto
`TENANT_TEMPLATE_CERTIFIED` continua **false** por padrão. O código de publicação não foi testado num ambiente real com provedor e DNS, então mantenha o bloqueio até executar todos os testes de CI e homologação.

## Dependências operacionais
- Backend Maven/Spring e banco PostgreSQL da Gestão devem migrar num banco de homologação primeiro.
- Para publicar em VPS, configurar agente com credenciais exclusivas, domínio autorizado, registry com digest de imagens e rede Traefik.
- AWS/Render permanecem sem conector de provisionamento automático homologado.

Não reescrever arquivos SQL Flyway já aplicados em produção e não executar `docker compose down -v` em bancos existentes.
