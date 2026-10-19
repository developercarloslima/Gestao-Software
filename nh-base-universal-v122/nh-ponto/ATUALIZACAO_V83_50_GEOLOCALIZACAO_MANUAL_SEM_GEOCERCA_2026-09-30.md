# ATUALIZAÇÃO V83.50 — Geocerca removida; GPS junto da selfie

## Regra funcional

O cliente optou por validação manual da localização. O sistema não usa mais geocerca.

1. O colaborador escolhe a ação da jornada.
2. O sistema abre a câmera e captura uma selfie simples.
3. Ao confirmar a selfie, o navegador captura latitude e longitude do GPS.
4. Selfie, coordenadas, precisão informada e horário da localização são salvos juntos na evidência da marcação.
5. A marcação nasce como `REVIEW`.
6. ADM/RH abre a selfie e vê latitude/longitude logo abaixo da foto.
7. O responsável aprova ou rejeita manualmente; somente após aprovação a marcação passa a valer na jornada.

## Removido

- raio de geocerca por unidade;
- ativar/desativar geocerca;
- bloqueio `OUTSIDE_GEOFENCE`;
- exigência de precisão máxima do GPS;
- comparação automática entre GPS do colaborador e unidade;
- Geoapify e conferência de coordenadas pelo endereço;
- variáveis `GEOAPIFY_*` / `PONTO_GEOAPIFY_*`.

## Preservação de banco

Campos legados de geocerca permanecem no schema para compatibilidade com bancos já existentes, mas não participam do fluxo operacional da V83.50. Não é necessário apagar o banco.
