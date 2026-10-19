# Novo Horizonte Proteção Veicular

<p align="center">
  <img src="./assets/simbolo-fundo-azul.png" alt="Logo Novo Horizonte Proteção Veicular" width="220" />
</p>

<p align="center">
  Site institucional responsivo com página de benefícios, escritórios, formulário de cotação e consulta de 2ª via de boleto integrada à API oficial Hinova SGA V2.
</p>

<p align="center">
  <img alt="HTML5" src="https://img.shields.io/badge/HTML5-E34F26?style=for-the-badge&logo=html5&logoColor=white" />
  <img alt="CSS3" src="https://img.shields.io/badge/CSS3-1572B6?style=for-the-badge&logo=css3&logoColor=white" />
  <img alt="JavaScript" src="https://img.shields.io/badge/JavaScript-F7DF1E?style=for-the-badge&logo=javascript&logoColor=111" />
  <img alt="Vercel" src="https://img.shields.io/badge/Vercel-000000?style=for-the-badge&logo=vercel&logoColor=white" />
</p>

---

## Sobre o projeto

Este projeto foi desenvolvido para a **Associação de Proteção Veicular Novo Horizonte**, com foco em apresentar a empresa de forma profissional, facilitar a captação de novos associados e oferecer uma experiência simples para consulta de 2ª via de boleto.

A aplicação combina um site institucional estático com funções serverless para integração com a **API oficial Hinova SGA V2**, mantendo credenciais sensíveis no backend e entregando ao usuário uma interface objetiva, responsiva e segura.

---

## Principais funcionalidades

### Site institucional

- Página inicial com hero section, chamada comercial e CTA para cotação.
- Seção de benefícios com cards visuais e ícones personalizados.
- Seção de funcionamento em etapas.
- Seção de aplicativo com links para App Store e Google Play.
- Área de contato com telefone, WhatsApp e formulário.
- Botão flutuante para rede social.
- Layout responsivo para desktop, tablet e mobile.

### Página de escritórios

- Listagem de unidades da Novo Horizonte em Alagoas.
- Cards com cidade, endereço, referência e link para rota no Google Maps.
- Navegação consistente com o restante do site.

### Cotação online

- Modal de cotação acionado pelos CTAs do site.
- Formulário com campos de nome, telefone e placa.
- Máscara de telefone e normalização de placa via JavaScript.
- Envio por FormSubmit usando requisição assíncrona.
- Feedback visual de envio, sucesso e erro.

### Consulta de 2ª via de boleto

- Página dedicada para consulta de boletos por CPF ou CNPJ.
- Integração serverless com a API oficial Hinova SGA V2.
- Autenticação via token de integração e `token_usuario`.
- Consulta de boletos em janelas de datas, respeitando limite da API.
- Agrupamento dos boletos por placa/veículo.
- Aplicação de regras financeiras por veículo.
- Exibição de status, vencimento, valor, linha digitável e link de boleto.
- Redirecionamento seguro para o boleto por uma rota backend intermediária.

---

## Regra de exibição dos boletos

A regra foi pensada para evitar que o associado emita boletos indevidos quando existe pendência financeira antiga em uma placa específica.

O sistema analisa os boletos **por veículo/placa**:

| Situação da placa | Comportamento no site |
| --- | --- |
| Placa sem atraso crítico | Lista os boletos disponíveis para emissão |
| Placa com 1 boleto vencido há mais de 6 dias | Exibe somente o boleto vencido mais antigo e orienta contato com o financeiro |
| Placa com 2 ou mais boletos vencidos há mais de 6 dias | Exibe somente o boleto vencido mais antigo e informa placa inativa |
| Placa cancelada | Exibe aviso de placa cancelada |
| Boletos pagos, baixados ou cancelados | Não são exibidos como boletos disponíveis |

O limite de dias pode ser configurado pela variável:

```env
BOLETO_MAX_DAYS_AFTER_DUE=6
```

---

## Arquitetura da integração

Fluxo principal da consulta:

```txt
Usuário informa CPF/CNPJ
        ↓
Frontend envia POST para /api/consultar-boletos-associado
        ↓
Serverless Function autentica/usa token da API Hinova
        ↓
Consulta boletos pela rota oficial da Hinova SGA V2
        ↓
Normaliza datas, valores, status, placa e links
        ↓
Agrupa os boletos por veículo
        ↓
Aplica regras de atraso, placa inativa/cancelada e disponibilidade
        ↓
Frontend renderiza os cards de resultado
```

Fluxo de abertura do boleto:

```txt
Usuário clica em "Baixar boleto"
        ↓
Frontend chama /api/baixar-boleto?url=...
        ↓
Backend valida se a URL pertence a domínio permitido da Hinova
        ↓
Backend redireciona o usuário para o boleto
```

---

## Tecnologias utilizadas

### Frontend

- **HTML5** com estrutura semântica.
- **CSS3** com variáveis, grid, flexbox e media queries.
- **JavaScript Vanilla** para interações, formulários e consumo das rotas internas.
- **Font Awesome** para ícones auxiliares.
- **Google Fonts** para tipografia.
- **FormSubmit** para envio de formulários sem backend próprio.

### Backend / Serverless

- **Vercel Serverless Functions**
- **Node.js**
- **Fetch API**
- Integração com **Hinova SGA V2**
- Variáveis de ambiente para credenciais e regras de negócio.

---

## Estrutura do projeto

```txt
.
├── api/
│   ├── consultar-boletos-associado.js
│   └── baixar-boleto.js
│
├── assets/
│   ├── logo-nh-oficial.png
│   ├── favicon-nh.png
│   ├── carro-hero.png
│   ├── app-phone-mockup.png
│   ├── app-store-badge.png
│   ├── google-play-badge.png
│   └── icons...
│
├── index.html
├── boleto.html
├── escritorios.html
├── script.js
├── style.css
├── .gitignore
└── README.md
```

---

## Variáveis de ambiente

Crie um arquivo `.env.local` para desenvolvimento ou configure as variáveis diretamente na Vercel.

> Nunca publique tokens, senhas ou arquivos `.env` no GitHub.

```env
HINOVA_API_BASE_URL=https://api.hinova.com.br/api/sga/v2

HINOVA_API_TOKEN=SEU_TOKEN_GERADO_NO_SGA
HINOVA_API_USER=SEU_USUARIO_DE_INTEGRACAO
HINOVA_API_PASSWORD=SUA_SENHA_DE_INTEGRACAO
HINOVA_API_USER_TOKEN=SEU_TOKEN_USUARIO_AUTENTICADO

HINOVA_ASSOCIADO_CPF_PATH=/associado/buscar-por-permissao/{documento}/cpf
HINOVA_ASSOCIADO_DEFAULT_PASSWORD=

BOLETO_MAX_DAYS_AFTER_DUE=6
BOLETO_SEARCH_DAYS_PAST=180
BOLETO_SEARCH_DAYS_FUTURE=420
BOLETO_SEARCH_CHUNK_DAYS=180

HINOVA_DEBUG_RESPONSE=false
```

### Descrição das variáveis

| Variável | Descrição |
| --- | --- |
| `HINOVA_API_BASE_URL` | URL base da API oficial Hinova SGA V2 |
| `HINOVA_API_TOKEN` | Token gerado no SGA em Área Cliente → APIs |
| `HINOVA_API_USER` | Usuário exclusivo de integração |
| `HINOVA_API_PASSWORD` | Senha do usuário de integração |
| `HINOVA_API_USER_TOKEN` | Token autenticado retornado por `/usuario/autenticar` |
| `HINOVA_ASSOCIADO_CPF_PATH` | Rota usada para consulta complementar de associado por CPF |
| `BOLETO_MAX_DAYS_AFTER_DUE` | Limite de dias após vencimento para bloquear emissão pelo site |
| `BOLETO_SEARCH_DAYS_PAST` | Quantidade de dias passados considerados na busca |
| `BOLETO_SEARCH_DAYS_FUTURE` | Quantidade de dias futuros considerados na busca |
| `BOLETO_SEARCH_CHUNK_DAYS` | Tamanho máximo de cada bloco de consulta de boletos |
| `HINOVA_DEBUG_RESPONSE` | Ativa retorno detalhado de erro para depuração |

---

## Como executar localmente

### 1. Clone o repositório

```bash
git clone https://github.com/developercarloslima/SiteInstitucional-NH.git
cd SiteInstitucional-NH
```

### 2. Configure as variáveis de ambiente

Crie um arquivo `.env.local` na raiz do projeto com as variáveis necessárias.

```bash
cp .env.example .env.local
```

Preencha os valores reais no `.env.local`.

### 3. Instale a Vercel CLI

```bash
npm i -g vercel
```

### 4. Rode o projeto

```bash
vercel dev
```

Acesse:

```txt
http://localhost:3000
```

---

## Deploy na Vercel

1. Faça o push do projeto para o GitHub.
2. Importe o repositório na Vercel.
3. Configure as variáveis em:

```txt
Settings → Environment Variables
```

4. Faça o deploy.

Importante: as rotas dentro de `/api` são executadas como **Serverless Functions** na Vercel. Por isso, as credenciais da API Hinova ficam protegidas no ambiente do servidor e não são expostas no navegador.

---

## Segurança

Este projeto segue alguns cuidados importantes:

- Credenciais da Hinova ficam em variáveis de ambiente.
- `.env`, `.env.local` e arquivos similares são ignorados pelo Git.
- O frontend não acessa diretamente a API da Hinova.
- O download do boleto passa por uma rota intermediária que valida a URL.
- A rota de boleto aceita apenas domínios permitidos da Hinova.
- O modo debug deve ficar desativado em produção.
- Tokens reais não devem ser compartilhados em prints, commits ou mensagens.

Antes de publicar o repositório, recomenda-se:

```txt
1. Conferir se nenhum arquivo .env foi commitado.
2. Remover credenciais do histórico, caso tenham sido versionadas.
3. Regenerar tokens/senhas expostos durante testes.
4. Configurar os segredos apenas na Vercel.
```

---

## Boas práticas aplicadas

- Separação entre camada visual, interações e integração serverless.
- Normalização de dados recebidos da API externa.
- Regras de negócio centralizadas no backend.
- Máscaras e validações básicas no frontend.
- Layout responsivo e adaptado para dispositivos móveis.
- Componentização visual por seções.
- Uso de rotas internas para proteger integrações sensíveis.
- Tratamento de erros da API externa.
- Fallback de mensagens amigáveis para o usuário final.

---

## Autor

Desenvolvido por **Carlos Lima**.

- GitHub: [@developercarloslima](https://github.com/developercarloslima)
- LinkedIn: [Carlos Lima](https://www.linkedin.com/in/devcarloslima/)

---

## Licença

Este projeto foi desenvolvido para uso institucional da **Novo Horizonte Proteção Veicular**.  
O uso, cópia ou distribuição deve respeitar as permissões do proprietário do projeto.
