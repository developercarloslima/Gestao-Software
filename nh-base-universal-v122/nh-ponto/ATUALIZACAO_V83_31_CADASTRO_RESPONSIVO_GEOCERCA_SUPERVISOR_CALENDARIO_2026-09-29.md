# V83.31 — Cadastro responsivo, geocerca antes da selfie, supervisor automático e calendário

Data: 29/09/2026

## Cadastro de colaborador
- O card de cadastro passa a ocupar a largura disponível e foi reorganizado para mobile, tablet, notebook, desktop e telas grandes.
- Campos, selects e horários da jornada não extrapolam mais o card.
- A grade da jornada muda progressivamente de 5 colunas para 2 e depois 1 coluna em telas pequenas.
- A lista de unidades da Novo Horizonte é sincronizada antes de ser entregue ao painel quando alguma das 10 unidades oficiais ainda estiver ausente.
- As 10 unidades oficiais continuam com raio fixo de geocerca de 20 metros.

## Supervisor automático por unidade
- Unidade Maceió/AL: supervisor esperado `Pedro Henrique`.
- Demais unidades: supervisora esperada `Deborah Galdino`.
- O campo Supervisor fica somente leitura e é preenchido a partir da unidade escolhida.
- Quando o cadastro correspondente já existe no Apontamento Digital, o vínculo `supervisorId` é aplicado automaticamente também no backend.

## Geocerca antes da selfie
- Antes de abrir a câmera, o navegador captura a localização com GPS de alta precisão.
- A API executa `/punches/precheck` e compara a posição com a unidade vinculada ao colaborador.
- O raio validado é fixo em 20 m.
- Fora da área, a câmera não abre e o colaborador recebe o popup: `Você está fora da área da empresa.`
- Localização ausente ou imprecisa também bloqueia a abertura da câmera com mensagem amigável.
- A posição aprovada no pré-check é reutilizada na marcação e continua sendo validada no fluxo final de integridade.

## Calendário de aniversários
- O dia atual recebe fundo amarelo.
- Quando o dia atual possui conteúdo, textos ganham azul e maior destaque.
- Havendo aniversariantes hoje, aparece o botão `Veja os aniversariantes de hoje` dentro do quadrado.
- O botão abre um modal responsivo com nome, setor e unidade de cada aniversariante.
