# Atualização V83.75 — Reset de senha e biometria pelo Super Adm

Data: 2026-10-01

## Regra implementada

O SUPER_ADMIN global pode abrir **Empresas e permissões → Ver usuários** e executar, para qualquer usuário da empresa (ativo ou inativo):

1. **Resetar senha**
   - gera senha temporária aleatória;
   - força troca no próximo acesso;
   - limpa challenges WebAuthn pendentes;
   - bloqueia o uso normal de sessões antigas pelo onboarding obrigatório;
   - registra auditoria.

2. **Resetar biometria**
   - exclui passkeys/WebAuthn;
   - exclui challenges WebAuthn;
   - exclui referências e submissões faciais;
   - redefine o status facial para `NOT_ENROLLED`;
   - zera bloqueios/tentativas biométricas;
   - solicita remoção também na Face API;
   - preserva selfies históricas das marcações de ponto;
   - registra auditoria.

## Segurança

As rotas são protegidas no servidor e rejeitam qualquer perfil diferente de `SUPER_ADMIN`. O usuário alvo também precisa pertencer à empresa informada e não pode ser uma conta `SUPER_ADMIN` global.

## Banco de dados

Nenhuma migration é necessária.
