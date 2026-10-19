# V83.76 — Leandro / Responsável de ponto

## Objetivo
Criar um usuário específico chamado **Leandro** para acompanhar a operação diária de ponto da Novo Horizonte sem receber permissões administrativas amplas.

## Credenciais iniciais
- Usuário: `leandro`
- Senha temporária: `nh2027`
- No primeiro acesso, a troca de senha é obrigatória.
- Na primeira aprovação/reprovação de jornada, o sistema solicita o cadastro da passkey individual de Leandro. Depois de cadastrada, a passkey também protege os logins seguintes da conta.

## Novo perfil
Foi criado o papel interno `TIME_REVIEWER` (Responsável de ponto).

O usuário Leandro é criado/garantido automaticamente pelo `prisma/seed.ts` no tenant **Novo Horizonte**. Ele não possui vínculo `Employee`, portanto:
- não bate ponto;
- não aparece como colaborador;
- não possui jornada própria;
- não acessa cadastro/edição de colaboradores.

## Áreas visíveis
O painel de Gestão do Leandro mostra somente:
1. **Presença hoje**
2. **Aprovação de jornadas**
3. **Espelho de pontos**

## Presença hoje
Leandro pode visualizar os colaboradores e administradores operacionais com jornada prevista no dia, incluindo:
- quem ainda não iniciou;
- quem está trabalhando;
- quem já finalizou;
- horário previsto;
- entrada/última marcação;
- detalhes da jornada do dia;
- selfies e GPS das marcações, quando disponíveis.

O `SUPER_ADMIN` continua fora da jornada e não aparece como colaborador.

## Aprovação de jornadas
Leandro pode:
- abrir jornadas finalizadas pendentes;
- conferir todas as marcações do dia;
- conferir selfie e GPS;
- visualizar a saída automática sem selfie quando ocorrer a exceção de +15 minutos;
- aprovar a jornada;
- reprovar com justificativa obrigatória.

A decisão exige a passkey individual do próprio Leandro e permanece registrada na auditoria.

## Espelho de pontos
Foi criada uma visualização específica para o Responsável de ponto:
- seleção de colaborador/administrador;
- seleção de competência mensal;
- geração do espelho;
- prévia;
- impressão/salvamento em PDF.

Por segurança, essa área **não retorna nem exibe salário, valor/hora, valores de folha ou estimativas financeiras**.

## Restrições
O perfil `TIME_REVIEWER` não foi adicionado aos grupos amplos `ADMIN_ROLES` ou `MANAGEMENT_ROLES`. Portanto ele não recebe acesso de backend a:
- cadastro/edição/exclusão de colaboradores;
- empresas;
- locais;
- departamentos;
- modelos de jornada;
- supervisores;
- folha/salários;
- banco de horas;
- configurações;
- dispositivos;
- QR de presença;
- monitoramento de tela;
- justificativas de falta;
- auditoria global;
- reset de senha/biometria de usuários.

## Banco de dados
O enum Prisma `Role` ganhou `TIME_REVIEWER`. O deploy existente executa `prisma db push`, que sincroniza o novo valor antes de executar o seed.
