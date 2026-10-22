# Gestão Software v96 — gestao2026 como site principal

## Alteração principal
O projeto `gestao2026.zip` passou a ser o site institucional público principal da Gestão Software.

- `/` → novo site React/Vite Gestão Software 2026.
- `/admin/login.html` → login real da Área Administrativa.
- `/admin/` → Control Plane preservado da v95.
- `/api/` → continua sendo encaminhado ao backend Java.

## Integração
O build do container `web` agora tem duas etapas:
1. Node 22 compila o site React/Vite.
2. Nginx publica o `dist` no `/` e copia a Área Administrativa existente para `/admin/`.

O botão **Área administrativa** do cabeçalho foi ligado diretamente a `/admin/login.html`.

## Compatibilidade
Backend, banco, migrations, calculadora, empresas-clientes, planos, módulos, financeiro, VPS/hospedagem e autenticação da v95 foram preservados.
