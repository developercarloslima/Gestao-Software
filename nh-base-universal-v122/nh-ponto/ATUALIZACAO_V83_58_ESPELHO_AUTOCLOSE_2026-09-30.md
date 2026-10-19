# V83.58 — Espelho oficial + autoencerramento com prazo de selfie

## Espelho de ponto
- O PDF usa um único componente compartilhado entre gestão e colaborador.
- Layout segue o padrão documental aprovado: cabeçalho institucional, logo, número do documento, período, identificação, resumo, detalhamento diário, conferência e rodapé.
- A seção "Apuração gerencial do RH" não aparece no PDF.
- Salário, valor/hora, valor/minuto, valor/segundo, desconto financeiro e estimativa de folha não aparecem no PDF.
- Texto do corpo documental usa o azul institucional NH (#070C40); textos do cabeçalho azul permanecem claros por contraste e os destaques seguem a cor secundária cadastrada.
- A logo e a faixa superior continuam respeitando a identidade visual cadastrada da empresa.
- O colaborador pode escolher a competência, gerar o próprio espelho e salvar/imprimir o PDF na aba "Minha jornada".
- A rota /time/me não expõe remuneração ao colaborador.

## Trava e encerramento automático
- 10 minutos após o horário previsto de saída, a jornada fica travada e a selfie de saída é solicitada.
- A partir da trava, o colaborador possui 5 minutos para concluir a selfie + GPS.
- Sem conclusão dentro do prazo, o servidor cria automaticamente o CLOCK_OUT em REVIEW.
- Observação registrada: "Jornada finalizada pelo sistema  após trava automatica e falta de selfie dentro do prazo pedido".
- A saída automática não exige selfie/GPS por definição, mas fica explicitamente marcada como evidência ausente para análise humana.
- A jornada continua exigindo decisão diária do Administrador da empresa ou Super Adm.
- Em caso de reprovação, a justificativa continua obrigatória.
- O cálculo de horas limita a saída automática ao corte da trava para não transformar o período de espera da selfie em hora extra.
- O scheduler executa mesmo se o app do colaborador estiver fechado; as rotas de marcação também forçam a checagem do prazo para evitar corrida após o vencimento.

## Distribuição das informações
- ADMIN da empresa: fila de aprovação diária + Central de Risco + detalhes completos.
- SUPER_ADMIN: mesma Central de Gestão, filtrada pela empresa selecionada.
- Administrador do projeto principal: aba Apontamento Digital mostra jornadas pendentes e botão "Ver informações", com horários, GPS, referência da unidade, observações e acesso às selfies disponíveis.

## Banco de dados
- Nenhuma migração de schema é necessária para a V83.58.
