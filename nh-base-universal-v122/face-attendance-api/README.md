# NH Face Attendance API

Serviço interno FastAPI + `face_recognition`/dlib usado pelo NH Ponto.

- `POST /api/v1/register`: cadastra/atualiza a referência pelo `external_id` do colaborador.
- `POST /api/v1/verify`: compara a selfie somente com o colaborador esperado.
- `DELETE /api/v1/employees/{external_id}`: remove a referência.
- `GET /health`: health check.

As imagens não são persistidas por este serviço; somente o embedding de 128 dimensões é salvo no SQLite persistente do container. Os endpoints biométricos usam `X-API-Key` quando `FACE_API_KEY` está configurada.
