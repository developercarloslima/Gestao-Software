# V83.46 — Conferência e correção das coordenadas pelo geocoder

Data: 30/09/2026

## Regra implementada

Cada local/unidade passa a ter o endereço como origem para a validação geográfica. Ao criar ou editar um local, o backend consulta o geocoder. Se o endereço for localizado, a latitude e a longitude retornadas são usadas como coordenadas oficiais da geocerca.

As coordenadas digitadas manualmente continuam disponíveis como fallback para indisponibilidade/falha do geocoder, mas não substituem um resultado válido retornado pelo serviço.

## Conferência de todos os locais

O Super Adm possui a ação **Conferir todos os locais com o geocoder**. A API `POST /admin/worksites/verify-geocodes` percorre todas as unidades não arquivadas da empresa em gestão, consulta o endereço de cada uma e:

- preenche coordenadas ausentes;
- corrige automaticamente coordenadas com divergência superior a 10 metros em relação ao geocoder;
- mantém coordenadas cuja diferença seja de até 10 metros;
- não apaga coordenadas existentes se o endereço não puder ser localizado;
- retorna resumo com verificados, corrigidos, já corretos, ignorados e falhas.

A sincronização das unidades oficiais da Novo Horizonte também executa a conferência quando usada pelo Super Adm.

No primeiro start da V83.46, após a API abrir a porta, a Novo Horizonte executa uma conferência única em segundo plano. O evento `WORKSITES_GEOCODER_V8346_BOOTSTRAP_COMPLETED` impede repetição automática a cada reinício; novas varreduras ficam sob ação explícita do Super Adm.

## Auditoria

Cada correção gera `WORKSITE_COORDINATES_CORRECTED_BY_GEOCODER`, contendo coordenadas anteriores e novas, distância aproximada entre elas, consulta usada e nome/endereço retornado pelo provedor. A conclusão da varredura gera `WORKSITES_GEOCODER_VERIFICATION_COMPLETED`.

## Provedor e controle de uso

O padrão continua sendo Nominatim/OpenStreetMap. As consultas são serializadas, com intervalo mínimo de 1,1 segundo, e os resultados ficam em cache em memória por 7 dias para evitar chamadas repetidas. O endpoint do geocoder e o User-Agent são configuráveis por variáveis de ambiente.

## Geocerca

O raio padrão continua em 100 metros e permanece editável pelo Super Adm. A alteração desta versão afeta apenas a confirmação/correção do centro da geocerca (latitude/longitude).
