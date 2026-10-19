# Gestão Software v95

## Interface
- Todos os dialogs da Área Administrativa ficam centralizados horizontal e verticalmente.
- Tipografia ajustada para usar negrito somente em títulos/cabeçalhos.

## Empresas-clientes
- Edição de empresa agora inclui plano por catálogo: Start, Business, Pro, Scale, Enterprise, Dedicated, Hybrid e Personalizado.
- Prazo contratual: mensal, semestral (8%) e anual (15%).
- Plano + prazo + módulos recalculam a mensalidade automaticamente.
- Plano Personalizado permite valor manual.
- Alteração de mensalidade atualiza cobranças PENDING do mês atual em diante; pagos e vencidos não são reescritos.
- Migration V4 adiciona `plan_code` e `contract_term`.

## Calculadora
- Asaas passou a ser tratado como repasse financeiro, não custo da Gestão Software.
- Tarifa de boleto/Pix é somada ao valor cobrado do pagador.
- Tarifa de saque é descontada do valor enviado ao consultor.
- A margem bruta usa somente receita da licença contra custos diretos da operação (AWS + outros custos diretos).
- Tela mostra exemplos de cobrança e saque líquido.

## Margem bruta alvo
`(receita da licença - custos diretos) / receita da licença`.

Exemplo: mensalidade de R$ 10.000 e custos diretos de R$ 4.000 = margem bruta de 60%.
Não representa lucro líquido: impostos, folha, comercial, contabilidade e despesas administrativas ficam fora desta métrica.
