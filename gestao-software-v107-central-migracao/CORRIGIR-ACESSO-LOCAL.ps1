Write-Host "== Gestão Software - correção de acesso local ==" -ForegroundColor Cyan
Write-Host "1) Subindo banco/backend/web..."
docker compose up -d --build
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "`n2) Containers:" -ForegroundColor Cyan
docker compose ps
Write-Host "`n3) Usuários administrativos no banco:" -ForegroundColor Cyan
docker compose exec database psql -U gestao -d gestao_control -c "SELECT username, display_name, must_change_password, active FROM gs_users ORDER BY username;"
Write-Host "`n4) Últimas migrations:" -ForegroundColor Cyan
docker compose exec database psql -U gestao -d gestao_control -c "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
Write-Host "`nAbra: http://localhost:3100/admin/login.html" -ForegroundColor Green
Write-Host "Carlos: carlos.lima / nh2027"
Write-Host "Pedro:  pedro.henrique / nh2027"
