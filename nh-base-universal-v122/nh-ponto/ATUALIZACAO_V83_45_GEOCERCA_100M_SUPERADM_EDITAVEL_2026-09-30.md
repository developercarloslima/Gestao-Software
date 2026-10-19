# V83.45 — Geocerca padrão de 100 m e edição pelo Super Adm

Data: 30/09/2026

## Regra nova

- Toda nova unidade passa a usar geocerca padrão de **100 metros**.
- As unidades existentes que ainda estavam no padrão legado de **20 metros** são migradas para **100 metros uma única vez**.
- O **Super Adm** pode editar, por unidade:
  - latitude;
  - longitude;
  - raio da geocerca em metros;
  - demais dados do local já disponíveis no formulário.
- Administradores comuns e RH continuam podendo ativar/desativar unidades, mas não podem alterar a configuração da geocerca via API.

## Persistência e segurança

- O raio usado no pré-check do ponto deixou de ser fixo no código e agora é lido do campo `Worksite.radiusM`.
- A validação completa de presença também usa o mesmo `radiusM`, evitando divergência entre pré-check e gravação da marcação.
- A sincronização automática das unidades oficiais da Novo Horizonte não sobrescreve latitude, longitude ou raio alterados pelo Super Adm.
- O seed não restaura mais as coordenadas/raio de Maceió a cada reinício.
- A migração de 20 m para 100 m grava um marcador em auditoria; depois disso, valores personalizados não são alterados em novos reinícios/deploys.
- Alterações de latitude, longitude ou raio pelo Super Adm geram o evento de auditoria `WORKSITE_GEOFENCE_UPDATED_BY_SUPER_ADMIN` com valores anterior e posterior.

## Coordenadas manuais

O geocodificador continua sendo usado como conferência entre endereço e coordenadas. Quando houver divergência relevante, o Super Adm recebe o aviso, mas pode manter a coordenada manual caso tenha confirmado que ela é a correta.

## Interface

Na aba **Gestão > Locais**, o Super Adm passa a visualizar o campo **Raio da geocerca (metros)**. A lista de unidades mostra a latitude, longitude e o raio efetivamente salvo em cada unidade.
