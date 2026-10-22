# Correção de build — PlatformUserService

Erro corrigido:

`Map<...intersection types...> cannot be converted to Map<String,Object>`

Arquivo:
`backend/src/main/java/br/com/gestaosoftware/control/service/PlatformUserService.java`

Correção:
`Map.of(...)` passou a usar type witness explícito:

`Map.<String,Object>of(...)`

Isso impede o Java 21 de inferir o tipo de interseção gerado pela mistura de String e Boolean no mapa.
