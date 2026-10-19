# V83.120 — Férias, feriados municipais e status de 30 dias de faltas

## Objetivo
Adicionar à Central de Gestão do Apontamento Digital uma aba exclusiva **Férias e feriados**, disponível para Administrador e Super Adm.

## Férias
- Lista colaboradores da empresa atualmente em gestão.
- Status: **ATIVO**, **DESLIGADO**, **DE FÉRIAS** ou **30 DIAS DE FALTAS**.
- Permite definir início e término das férias.
- Períodos de férias fecham a jornada do colaborador e não geram falta/déficit.
- Férias retroativas recalculam o banco de horas dos dias afetados.
- Durante férias, o início da jornada fica bloqueado com mensagem amigável.
- Períodos podem ser removidos com auditoria e recálculo.

## 30 dias de faltas
- Conta dias de jornada prevista sem registro.
- Folgas da escala e feriados não entram na contagem.
- Férias e justificativas aprovadas interrompem a sequência.
- Nunca conta dias anteriores à admissão.
- Ao atingir 30 dias aparecem as ações **Entrar em contato** e **Colaborador estava de férias**.
- O botão de férias retroativas abre o período já preparado para ajuste pelo administrador.

## Contato
- O cadastro do colaborador ganhou **Telefone / WhatsApp** opcional.
- `Entrar em contato` abre WhatsApp quando há telefone; se não houver, usa o e-mail de contato.
- O clique é registrado em auditoria.

## Feriados
- Feriados nacionais e estaduais existentes continuam automáticos.
- Administrador/Super Adm pode cadastrar **feriado municipal por cidade** das unidades da empresa.
- O feriado municipal fecha a jornada de todos os colaboradores das unidades daquela cidade.
- Cadastro ou exclusão retroativa recalcula a apuração do dia.

## Banco / Prisma
Novos modelos:
- `VacationPeriod`
- `MunicipalHoliday`

Novo campo em `Employee`:
- `contactPhone`

O deploy do NH Ponto já executa `prisma db push --skip-generate` no `entrypoint-nh.sh`, preservando os dados existentes e adicionando as novas estruturas.

## Responsividade
- Cards de colaboradores com altura automática e conteúdo compacto.
- Grade responsiva de 1 a várias colunas conforme a tela.
- Ações viram uma coluna no celular.
- Formulário de feriado se reorganiza para 2 colunas no tablet e 1 no celular.
- Removidos espaços mínimos/alturas artificiais nos cards desta aba.
