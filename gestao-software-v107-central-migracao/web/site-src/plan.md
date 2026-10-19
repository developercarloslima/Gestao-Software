# Plano — Gestão Software 2026

## Escopo e abordagem

Manter a landing page institucional criada a partir da referência como página pública principal na rota `/` do projeto Gestão Software v98. O frontend é React + TypeScript com Vite; o container `web` compila `web/site-src` e publica o conteúdo de `dist` na raiz servida pelo Nginx. Preservar o backend Java, o banco, o Control Plane e a área administrativa do projeto: `/api/`, `/admin/` e `/admin/login.html` continuam sendo servidos pelo roteamento existente. O botão “Área administrativa” aponta ao login real; os CTAs de contato/plano continuam sendo demonstrações sem inventar envio de formulário, checkout ou contratação.

## Direção de design

- **Movimento:** SaaS editorial de luxo tecnológico, com composição inspirada em painéis de controle premium e campanhas cinematográficas de infraestrutura.
- **Princípios centrais:** alto contraste e leitura imediata; densidade informativa controlada por cartões compactos; uma marca elétrica azul como fio condutor; conteúdo institucional prático e direto.
- **Filosofia de cor:** fundo quase preto/azul-marinho para sugerir profundidade e foco; ciano/azul elétrico como sinal de ação, segurança e modernização; o modo claro oferece superfícies luminosas e texto azul-escuro sem retirar o hero e o rodapé de suas âncoras escuras.
- **Paradigma de layout:** página de rolagem contínua organizada em faixas largas, alternando áreas tipográficas e painéis visuais assimétricos; no desktop, hero em duas zonas e blocos modulares; no celular, conteúdo e cartões são reorganizados em coluna.
- **Elementos assinatura:** logo oficial com monograma G azul, wordmark e assinatura institucional; bordas finas azuladas com brilho sutil; indicadores e ícones lineares com acento ciano.
- **Interação:** navegação por âncoras, estados de foco visíveis, hover de curta distância, menu expansível em telas pequenas e alternador acessível entre modo escuro (padrão) e claro. Guardar a escolha em `localStorage`. Ligar “Área administrativa” ao login real existente.
- **Animação:** transições suaves e discretas; respeitar `prefers-reduced-motion` e não comprometer leitura.
- **Tipografia:** Space Grotesk para títulos e marcas, Inter para texto corrido/UI, com fallbacks locais.
- **Essência da marca:** plataforma de gestão centralizada para empresas que precisam operar e crescer com autonomia; personalidade: confiável, moderna, resolutiva.
- **Tom de voz:** claro, confiante e específico, sem exageros. Exemplos: “Tecnologia completa para modernizar e escalar a gestão da sua empresa.”; “Uma plataforma. Várias empresas. Controle centralizado.”
- **Logotipo:** reutilizar a logo oficial local do projeto no cabeçalho, no rodapé e na composição da plataforma, em escala responsiva.
- **Cor proprietária:** azul elétrico `#078BFF`, acompanhado de ciano `#11C9F5`.

## Implementação e estrutura

- `web/site-src/`: aplicação React + TypeScript, dependências e build Vite. Não adicionar dependências desnecessárias.
- `web/site-src/src/main.tsx` inicializa a aplicação; `src/App.tsx` compõe as seções, guarda a preferência de tema e fornece feedback dos CTAs.
- `web/site-src/src/components/`: `Brand`, `Header`, `Hero`, `Metrics`, `ProductSections` e `PricingFooter`. O cabeçalho conserva o link a `/admin/login.html`.
- `web/site-src/src/styles.css`: identidade visual, temas escuro/claro, responsividade, contrastes e motion.
- `web/site-src/public/assets/`: imagens locais da landing page (`site-hero.webp`, `site-association.webp`, `site-infrastructure.webp`) e cópia da logo para o servidor Vite local. O build do Vite inclui esses arquivos no `dist`.
- `web/assets/`: logos da Gestão e de marcas do projeto, copiadas também pelo `web/Dockerfile` para `/assets/` no container.
- `web/Dockerfile` compila o site público para a raiz estática. `web/nginx.conf` serve `/`, encaminha `/api/` ao backend e preserva `/admin/` no Control Plane.
- `web/admin/`, `backend/`, `docker-compose*.yml` e os arquivos de infraestrutura permanecem inalterados.
- `public/manus-routes.json` declara a rota pública `/`.

## Restrições e decisões de conteúdo

- Manter números ilustrativos de resultados, oito módulos, arquitetura com empresas independentes, diferenciais de marca e disponibilidade de 99,9%.
- Os planos são Bronze, Silver, Gold e Enterprise, sem preços. Todos indicam hospedagem Cloud ou servidor físico, condições sob consulta e “Fale com especialista”; não inventar limites ou benefícios específicos por tier nem simular contratação ou checkout.
- Usar linguagem voltada a empresas; usar “cliente(s)” para o público que acessa o portal.
- Números e métricas de desempenho são demonstrativos, não promessas contratuais.
- Adaptar colunas e menu para celular; manter alt text descritivo para imagens informativas.
- Não usar caminhos `/manus-storage/...` no ZIP: todos os assets visuais da página principal devem estar disponíveis localmente no pacote.
