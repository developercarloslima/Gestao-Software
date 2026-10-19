# Agente VPS v121 — mudanças de segurança

- Recebe jobs apenas para a VPS identificada, com token derivado da chave mestra.
- Aceita somente os cinco módulos publicados no catálogo oficial atual da Gestão.
- Rejeita subdomínios reservados, múltiplos níveis e strings fora da zona autorizada.
- Usa trava de arquivo Linux `flock` por empresa: execução paralela não recria segredos.
- Continua reutilizando `c` + 16 dígitos do UUID como diretório por **compatibilidade** com v120; confere sempre o UUID completo em `secrets.env`.
- Arquivos `.env` usam aspas escapadas para preservar nomes com apostrofos, `#`, aspas e barras.
- Recusa scripts de interpolação, valores com newline e configuração de imagem sem digest.

Instalação: somente Linux. Executar em VPS de homologação com Docker e Traefik já configurados, e com usuário de serviço restrito. Não habilitar `ALLOW_RESOURCE_CREATION=yes` antes da certificação do tenant NH e dos testes de backup e restauração.

Verifique localmente em Linux: `python3 -m unittest discover -v -p 'test_*.py'`.
