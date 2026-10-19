# V83.33 — 10 unidades oficiais e geocerca segura

## Correção
- As 10 unidades da Novo Horizonte passam a ser persistidas sempre no Apontamento Digital, mesmo se o serviço de geocodificação estiver temporariamente indisponível.
- Endereços e CEPs padronizados foram incorporados ao catálogo oficial.
- O cadastro de colaborador lista as 10 unidades ativas em ordem fixa.
- Unidade sem latitude/longitude válida aparece como `geocerca pendente` e NÃO autoriza nenhuma marcação.
- O precheck da jornada bloqueia antes da selfie quando a geocerca da unidade estiver pendente.
- O raio permanece fixo em 20 metros.

## Unidades
1. Taquarana/AL — Rua Pedro Rosa da Silva, 136 - Centro — CEP 57640-000
2. Maceió/AL — Avenida Comendador Francisco de Amorim Leão, 364, Sala 04 - Pinheiro — CEP 57057-780
3. Arapiraca/AL — Rua Estudante José de Oliveira Leite, 1289 - Ouro Preto — CEP 57301-060
4. Girau do Ponciano/AL — Avenida Tiradentes, s/nº - Centro — CEP 57360-000
5. Campo Alegre/AL — Avenida João Fernandes Vieira, 801 - Centro — CEP 57250-000
6. Olivença/AL — Rua Padre Cícero, 57 - Centro — CEP 57550-000
7. Teotônio Vilela/AL — Rua Pedro Cavalcante, s/nº - Centro — CEP 57265-000
8. Coruripe/AL — Avenida Floriano Peixoto, 241 - Centro — CEP 57230-000
9. São Sebastião/AL — Avenida Antônio Custódio Pôrto, 67 - Centro — CEP 57275-000
10. Murici/AL — Avenida Doutor José Marques Luz, s/nº (Pista Nova) — CEP 57820-000

## Segurança
Nenhuma coordenada aproximada ou `0,0` é usada como fallback. Se o geocoder não resolver o endereço exato, a unidade continua visível no cadastro, mas a jornada permanece bloqueada até a geocerca ser válida.
