# Guia rápido: colocar uma nova empresa em operação

Este guia destina-se ao **operador do painel**, não ao administrador do servidor.

1. Entre no painel da Gestão Software → **Empresas** → **+ Cadastrar empresa**.
2. Preencha as informações da empresa, escolha o plano e marque os módulos. Informe a identidade visual e salve.
3. O painel abrirá **Implantação guiada** e mostrará a empresa escolhida. Se isso não ocorrer, clique nessa aba e selecione a empresa.
4. Responda às perguntas com os números aproximados do cliente. O próprio formulário explica cada campo; para venda use QUANTIDADE, não R$.
5. Clique **Analisar capacidade** e, depois, **Confirmar e salvar análise**. Leia o perfil recomendado e as restrições da hospedagem.
6. Abra o cadastro da empresa e confira o contrato. O painel só avançará quando estiver realmente assinado pelas duas partes.
7. Selecione a fatura, gere a cobrança Asaas e aguarde confirmação pelo servidor. Não marque como pago manualmente para habilitar provisionamento.
8. Na lista de pré-requisitos, os itens com `!` mostram o que falta. Clique em **Atualizar situação** quando corrigir um deles.
9. Se **Iniciar implantação** estiver desabilitado, a etapa ainda não está pronta. A v102 não permite implantar empresas reais enquanto a base universal não estiver certificada.
10. Depois que a equipe técnica habilitar os conectores homologados e o ambiente passar no teste de identidade e HTTPS, o histórico exibirá **Pronto para uso** e o botão **Copiar endereço para o cliente**.

## Como interpretar a fila

- **Na fila:** solicitação autorizada aguardando agente.
- **Instalando serviços:** contêineres e bancos estão sendo preparados.
- **Verificando endereço:** servidor valida certificado, domínio e UUID da empresa.
- **Pronto para uso:** somente nessa condição é liberada a URL.
- **Falhou:** não tente reinstalar/duplicar por conta própria; encaminhe a mensagem à equipe técnica.

## Privacidade e segurança

Nunca compartilhe token de agente, senha de banco, credenciais Asaas ou chaves de assinatura com clientes ou operadores. Não cadastre uma empresa nova usando bancos de dados e contas da Novo Horizonte.
