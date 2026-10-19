# V83.34 — Geocercas fixas das 10 unidades da Novo Horizonte

## Objetivo
Eliminar a dependência de geocodificação externa para as unidades oficiais da Novo Horizonte e tornar a validação da geocerca determinística.

## Regra
- raio fixo: 20 metros;
- unidade do colaborador é validada antes da abertura da câmera/selfie;
- fora do raio: bloqueia a marcação e exibe "Você está fora da área da empresa.";
- GPS sem permissão ou sem precisão suficiente: bloqueia a marcação (fail closed);
- as 10 unidades oficiais são sempre sincronizadas como ativas com coordenadas fixas;
- coordenadas oficiais não podem ser alteradas acidentalmente pelo painel;
- registro legado "Unidade Maceió" é migrado/desativado em favor de "Maceió/AL".

## Coordenadas oficiais
1. Taquarana/AL: -9.640300, -36.491500
2. Maceió/AL: -9.576000, -35.739158
3. Arapiraca/AL: -9.761109, -36.653400
4. Girau do Ponciano/AL: -9.884040, -36.831600
5. Campo Alegre/AL: -9.784398, -36.350934
6. Olivença/AL: -9.518611, -37.190556
7. Teotônio Vilela/AL: -9.910078, -36.354227
8. Coruripe/AL: -10.126547, -36.176078
9. São Sebastião/AL: -9.933611, -36.554167
10. Murici/AL: -9.306940, -35.942800

## GPS no celular
A captura usa watchPosition em alta precisão por até 15 segundos, guardando a melhor amostra. Ao atingir precisão de 20 metros ou melhor, a validação pode prosseguir imediatamente. Se ao final a precisão ainda não atender à política (máximo 50 m), a marcação continua bloqueada.
