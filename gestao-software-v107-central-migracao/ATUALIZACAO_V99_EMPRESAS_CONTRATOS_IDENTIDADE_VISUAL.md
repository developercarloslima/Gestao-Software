# Atualização v99

Esta versão adiciona cadastro comercial completo de empresas-clientes, anexos de identidade visual, formas de pagamento, contratos com duas assinaturas, ações de edição/desativação/exclusão e atualização dos planos públicos.

## Fluxo de contrato
1. Carlos ou Pedro cadastra a empresa, módulos, mensalidade e forma de pagamento.
2. O backend cria automaticamente um contrato com snapshot comercial.
3. Na tela da empresa é possível copiar o link de assinatura da empresa-cliente.
4. A empresa assina pelo link público `/contract/?token=...`.
5. Carlos Lima ou Pedro Henrique assina pelo Control Plane.
6. Quando as duas assinaturas existem, o contrato fica `SIGNED`.

## Observação
A assinatura implementada é uma assinatura eletrônica interna da plataforma com identificação e data/hora. Para requisitos jurídicos específicos (ICP-Brasil, certificado digital ou assinatura qualificada), deve-se integrar um provedor especializado.
