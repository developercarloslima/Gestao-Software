# V83.43 — Gestão de locais, empresas e usuários pelo Super Adm

- Super Adm pode editar unidades/locais, inclusive nome, endereço, cidade, UF, latitude e longitude.
- A validação endereço ↔ coordenadas continua obrigatória quando o geocoder consegue localizar o endereço; raio da geocerca permanece fixo em 20 m.
- A sincronização automática das unidades da Novo Horizonte deixa de sobrescrever edições manuais feitas pelo Super Adm.
- Nos cards de locais, os botões **Editar**, **Ativar/Desativar** e **Excluir** ficam lado a lado no mobile; os locais continuam listados um abaixo do outro.
- Os chips de status da equipe, incluindo **SENHA PENDENTE** e **SELFIE + APROVAÇÃO**, não quebram em duas linhas; a tabela mantém rolagem horizontal segura em telas pequenas.
- Super Adm pode editar empresas cadastradas (nome, razão social, documento e fuso horário).
- Cada card de empresa ganhou o botão **Ver usuários**.
- A lista de usuários da empresa mostra somente contas ativas vinculadas diretamente àquela empresa.
- O Super Adm pode editar usuário, nome, e-mail de contato e senha, além de definir se a troca de senha será exigida no próximo login.
- O contador de usuários nos cards das empresas considera somente contas ativas, evitando contas legadas/inativas na contagem.
- Removida a mensagem visual de unificação entre contas administrativas; registros legados inativos não aparecem na lista normal de usuários.
