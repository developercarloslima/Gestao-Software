$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not (Test-Path ".env")) { Copy-Item ".env.example" ".env" }
docker compose up -d --build
docker compose ps
Write-Host ""
Write-Host "Gestao Software: http://localhost:3100" -ForegroundColor Cyan
Write-Host "Area Administrativa: http://localhost:3100/admin/" -ForegroundColor Cyan
Write-Host "Usuarios: carlos.lima ou pedro.henrique | senha inicial nh2027" -ForegroundColor Yellow
