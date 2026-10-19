# FIPE isolada na VPS (agente v123)

A imagem `NH_FIPE_IMAGE` precisa ser construída de `api-fipe/Dockerfile` no pipeline,
publicada num registro privado e referenciada por digest SHA256 na VPS.
Quando o plano ativa Cotação ou Vistoria, o agente inclui o profile `fipe` e cria
um volume exclusivo por tenant. A API não publica porta ao exterior.

O dataset precisa ser importado e validado antes de lançar a primeira cotação.
A configuração da base de dados é operacional, não comercial: preços e coberturas
são definidos pelo administrador de cada empresa no sistema.

Não ativar em produção antes de testes de deploy e consulta FIPE real.
