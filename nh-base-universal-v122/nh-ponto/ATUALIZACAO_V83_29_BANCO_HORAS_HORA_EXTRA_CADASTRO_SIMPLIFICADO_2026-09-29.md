# V83.29 — Banco de horas / hora extra + cadastro simplificado

Data: 2026-09-29

## Regras por empresa

- Cada empresa escolhe como tratar horas excedentes:
  - **Banco de horas**: extras viram crédito e déficits viram débito no saldo.
  - **Pagamento de hora extra**: banco fica desativado e as extras entram no cálculo financeiro da folha.
- A opção pode ser definida pelo SuperAdm na criação da empresa e alterada depois por ADM/RH em Configurações.
- Ajuste manual de banco é bloqueado quando a empresa estiver no modo de pagamento de hora extra.

## Cadastro do colaborador

Campos obrigatórios:
- Usuário
- Senha inicial
- Nome completo
- Setor
- Unidade
- Jornada

Campos opcionais:
- Matrícula (gerada automaticamente se vazia)
- E-mail
- Perfil (padrão Colaborador)
- Cargo
- CPF/identificação
- Data de admissão
- Salário
- Supervisor
- Foto frontal do Totem

O login do colaborador continua sendo por **usuário + senha**, sem necessidade de e-mail.

## Salário

- Campo iniciado e tratado como moeda brasileira (`R$ 0,00`).
- Não é obrigatório para criar o colaborador.
- Quando não informado, os valores financeiros ficam em `R$ 0,00` até ADM/RH preencher o salário.
- A interface explica que o salário é necessário para calcular valor/hora e a estimativa de quanto o colaborador receberá conforme as horas trabalhadas.

## Segurança e histórico

- A senha inicial definida no cadastro é armazenada com hash bcrypt.
- No primeiro acesso, o colaborador continua obrigado a trocar a senha inicial.
- Alterações de salário e cadastro continuam auditadas.
