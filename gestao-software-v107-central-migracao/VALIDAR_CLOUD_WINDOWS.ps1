# Diagnóstico somente leitura: não instala, não migra banco e não remove volumes.
$ErrorActionPreference = 'Stop'
Write-Host 'Gestão Cloud v104 — diagnóstico seguro (sem alterações)' -ForegroundColor Cyan
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker não encontrado. Inicie o Docker Desktop.' }
Write-Host "Pasta atual: $((Get-Location).Path)"
if (-not ((Test-Path 'docker-compose.yml') -or (Test-Path 'compose.yaml'))) { throw 'Abra o PowerShell na raiz do projeto Gestão Software.' }
Write-Host 'Validando configuração Compose...' -ForegroundColor Cyan
docker compose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Falha na configuração Docker Compose.' }
Write-Host 'Serviços:' -ForegroundColor Cyan
docker compose ps
Write-Host 'AVISO: antes de atualizar backend em produção, faça backup do PostgreSQL e do .env. Não execute down -v.' -ForegroundColor Yellow
Write-Host 'Este script não realizou nenhuma atualização.' -ForegroundColor Green
