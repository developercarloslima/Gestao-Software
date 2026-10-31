# Personalização de identidade visual da Base Universal

A Gestão Software é a autoridade de marca de cada empresa. A Base Universal consulta
`/api/public/platform/branding?host=` e exibe logo, título, nome, cores e ícones.

A tela de Implantação da Base oferece um modal de personalização que chama
`PUT /api/admin/branding`, autenticado com o papel `ADMIN`. O backend da Base
envia a mudança a `PUT /api/public/platform/tenant-branding` na Gestão.
Este último endpoint não é público para escrita: exige token HMAC específico do
host, validado em tempo constante, e só edita a empresa ativa vinculada ao host.

## Credenciais (não armazenar no Git)

- **Gestão**: configure `GESTAO_TENANT_BRANDING_MASTER` (segredo aleatório de pelo
  menos 32 caracteres) no serviço da API.
- **Cada Base**: configure `GESTAO_TENANT_BRANDING_TOKEN` com o HMAC-SHA256
  hexadecimal do host específico da empresa, usando a master como chave:
  `HMAC_SHA256(master, lower(tenant_host))`.
- `tenant_host` deve corresponder a `app.gestao-control.tenant-host` na Base
  e a `platform_company_hosting.domain/subdomain` na Gestão. Nunca derive o
  token do host informado pelo navegador.

Credenciais e logos não são fornecidos ao JavaScript de origem do cliente.
A logo PNG/JPEG/WebP (até 2 MB) é armazenada na Gestão; o favicon passa a usar
a mesma imagem. O tenant mantém cache somente após confirmação da Gestão;
uma falha na sincronização não altera cores localmente.

## Importação CSV de veículos

Novas colunas opcionais: `whatsapp`, `placa`, `veiculo0km`,
`tipoveiculo`, `marca`, `modelo`, `anodomodelo`, `versaofipe`,
`codigofipe`, `valorfipe`. Também são aceitos nomes semelhantes aos
rótulos exibidos na tela de vistoria.

Quando uma linha tem veículo, `modelo` é obrigatório. A importação só cria
veículos de **novos clientes** e pula placas já cadastradas; não modifica
registros existentes. Veículos são criados como `DRAFT`, sem plano, assinatura
ou cobrança. Boletos e documentos anteriores não são importados por esse CSV.

Para verificar: executar `CustomerCsvParserTest`, importação de teste no
tenant de homologação, comparar prévia e banco, testar salvamento do modal
e verificar Control Plane e favicon em cada rota.
