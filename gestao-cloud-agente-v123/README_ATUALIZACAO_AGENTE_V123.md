# Agente VPS v123 — FIPE independente

Base: v122. Adicionada a imagem por digest `NH_FIPE_IMAGE` e serviços
`fipe-api` e `fipe-updater` sob profile Docker `fipe`, isolados por tenant.
É ativado automaticamente quando o job inclui COTA ou VISTORIA_EVENTOS.

Atenção: nenhum deploy ocorreu. O agente exige imagens reais e certificadas,
TLS/Traefik, DNS, payment verification, catálogo e autorização explícita.

Executar testes sem iniciar serviços:

```bash
python3 -m unittest discover -p 'test_*.py' -v
```

`ALLOW_RESOURCE_CREATION=no` permanece como padrão. Não publicar até
compilação completa, migrações e execução E2E aprovadas.
