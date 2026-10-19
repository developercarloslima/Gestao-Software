$ErrorActionPreference = "Stop"
Write-Host "[1/6] Parando containers e removendo volumes locais..." -ForegroundColor Cyan
docker compose down -v --remove-orphans

Write-Host "[2/6] Conferindo migrations Flyway duplicadas..." -ForegroundColor Cyan
$migrations = Get-ChildItem -Path ".\backend\src\main\resources\db\migration" -Filter "V*__*.sql"
$dupes = $migrations | ForEach-Object {
  if ($_.Name -match '^V([^_]+)__') { [PSCustomObject]@{ Version=$Matches[1]; Name=$_.Name } }
} | Group-Object Version | Where-Object Count -gt 1
if ($dupes) {
  Write-Host "ERRO: existem migrations com versão repetida:" -ForegroundColor Red
  $dupes | ForEach-Object { $_.Group | Format-Table -AutoSize }
  exit 1
}
Write-Host "Migrations OK." -ForegroundColor Green

Write-Host "[3/6] Construindo backend sem cache..." -ForegroundColor Cyan
docker compose build --no-cache backend

Write-Host "[4/6] Construindo web..." -ForegroundColor Cyan
docker compose build web

Write-Host "[5/6] Subindo a plataforma..." -ForegroundColor Cyan
docker compose up -d

Write-Host "[6/6] Status dos containers:" -ForegroundColor Cyan
docker compose ps
Write-Host "Abra: http://localhost:3100/admin/login.html" -ForegroundColor Green
Write-Host "Login inicial: carlos.lima / nh2027 ou pedro.henrique / nh2027" -ForegroundColor Yellow
