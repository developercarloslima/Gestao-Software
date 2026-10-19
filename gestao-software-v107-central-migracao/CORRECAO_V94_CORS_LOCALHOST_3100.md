# V94 - Correção CORS local

O Docker Compose da v93 sobrescrevia `app.allowed-origins` sem incluir `http://localhost:3100`.
Isso fazia `POST /api/auth/login` retornar `403 Invalid CORS request`.

A v94 inclui:
- http://localhost:3100
- http://127.0.0.1:3100
- http://localhost:3000
- http://127.0.0.1:3000
- http://localhost:5173
- http://127.0.0.1:5173
- domínios de produção da Gestão Software

Como a alteração é de ambiente, não é necessário resetar o banco. Basta recriar o backend/web.
