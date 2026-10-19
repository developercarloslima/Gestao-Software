# Atualização V83.49 — Geoapify para confirmação das coordenadas das geocercas

Data: 30/09/2026

## Objetivo

Usar a Geoapify somente para confirmar/corrigir latitude e longitude das unidades a partir do endereço cadastrado. A Geoapify não participa da batida de ponto do colaborador e não é chamada para calcular a distância da geocerca.

## Fluxo

1. Endereço, cidade e UF da unidade são enviados pelo backend para `https://api.geoapify.com/v1/geocode/search`.
2. A consulta usa `text`, filtro `countrycode:br`, idioma `pt`, `limit=1` e a chave mantida exclusivamente no backend.
3. O primeiro resultado fornece latitude e longitude.
4. Se a unidade não tiver coordenadas ou a diferença em relação ao ponto salvo for maior que 10 m, as coordenadas salvas são atualizadas.
5. A geocerca continua usando somente as coordenadas já salvas no banco e o raio configurado na unidade.

## Segurança

A chave não é gravada no frontend nem neste pacote. Configure:

- Local: `PONTO_GEOAPIFY_API_KEY` no ambiente usado pelo Docker Compose.
- VPS: `PONTO_GEOAPIFY_API_KEY` em `/root/nh.env`.

Variável opcional:

- `PONTO_GEOAPIFY_BASE_URL=https://api.geoapify.com/v1/geocode/search`

## Comportamento existente preservado

- Raio padrão de geocerca: 100 m.
- Super Admin pode editar latitude, longitude e raio.
- Super Admin pode ativar/desativar a geocerca por unidade.
- Se a geocerca estiver desativada, o ponto não é bloqueado por GPS/distância.
- ADMIN bate ponto; SUPER_ADMIN continua isento.
- Se a Geoapify estiver indisponível, coordenadas manuais existentes não são apagadas.

## Auditoria

Correções automáticas ficam registradas como:

`WORKSITE_COORDINATES_CONFIRMED_BY_GEOAPIFY`

A conferência em massa fica registrada como:

`WORKSITES_GEOAPIFY_VERIFICATION_COMPLETED`

No primeiro start da V83.49 com a chave configurada, a aplicação executa uma conferência única dos locais da Novo Horizonte e marca:

`WORKSITES_GEOAPIFY_V8349_BOOTSTRAP_COMPLETED`
