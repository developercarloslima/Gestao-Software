# Agente VPS v120 — homologação

Continua compatível com o protocolo de implantação v119. Exige token HMAC individual por VPS.

Melhorias: validação rigorosa de hostname, recusa link simbólico e alteração de host em workspace existente, preserva segredos após retentativa idempotente, testes automatizados adicionais.

## Testes

`python -m unittest discover -v -p 'test_*.py'`

## Operação

A Gestão só envia jobs ao agente da VPS correspondente. O agente não participa da escolha da VPS. O Cloud Orchestrator v105 compara os recursos e faz reserva antes do envio.

`ALLOW_RESOURCE_CREATION=no` é o padrão seguro. **Não mudar para yes** antes de homologar Docker, TLS, certificados, imagens por digest, PostgreSQL, isolamento, backup e restauração.

Não instalar com a chave mestra da Gestão. Use o token HMAC correspondente ao UUID da VPS.
