param([switch]$SomenteVerificar, [switch]$Executar)
$ErrorActionPreference = 'Stop'
$Project='gs-nh-v122-homologacao'
$Compose=Join-Path $PSScriptRoot 'docker-compose.tenant-homologacao.yml'
Set-Location $PSScriptRoot
Write-Host 'Gestao NH v122 - homologacao em containers ISOLADOS' -ForegroundColor Cyan
Write-Host 'Este teste NAO utiliza volumes da NH nem da Gestao atuais.'
if (!(Test-Path $Compose)) { throw 'Compose de homologacao ausente.' }
if (!(Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker nao encontrado. Instale/inicie Docker Desktop.' }
& docker compose version
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose indisponivel.' }
Write-Host "Projeto: $Project. Banco descartavel em RAM. Porta local 3188." -ForegroundColor Yellow
& docker compose -p $Project -f $Compose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Compose invalido. Nenhum ambiente foi iniciado.' }
if (-not $Executar) {
 Write-Host 'Pre-requisitos validados. Para construir e executar, use -Executar.' -ForegroundColor Green
 exit 0
}
Write-Host 'Compilando com Maven -Ptenant e inicializando PostgreSQL de teste...' -ForegroundColor Cyan
& docker compose -p $Project -f $Compose up -d --build postgres-test api-test
if ($LASTEXITCODE -ne 0) {
 & docker compose -p $Project -f $Compose logs --tail=150 api-test
 throw 'Falha no build ou inicio da API; nada foi aplicado na NH atual.'
}
$ok=$false
for ($i=0; $i -lt 60; $i++) {
 try {
  $response = Invoke-RestMethod -Uri 'http://localhost:3188/api/health/tenant' -TimeoutSec 4
  if ($response.status -eq 'UP' -and $response.readyForCustomers -eq $false) { $ok=$true;break }
 } catch { }
 Start-Sleep -Seconds 3
}
if (-not $ok) {
 & docker compose -p $Project -f $Compose logs --tail=200 api-test
 throw 'Falha de integracao ou migração Flyway: reveja logs.'
}
Write-Host 'API inicializou, Flyway aplicou e tenant NAO esta liberado para clientes.' -ForegroundColor Green
$history=& docker compose -p $Project -f $Compose exec -T postgres-test psql -U tenant_test -d tenant_homologacao -Atc 'select count(*) from flyway_schema_history where success=true and version in (''1'',''2'',''3'',''4'',''5'',''6'',''7'',''8'',''9'',''10'',''11'');'
if ($LASTEXITCODE -ne 0 -or "${history}".Trim() -ne '11') { throw "Esperadas 11 migracoes validas; resultado: $history" }
$catalog=& docker compose -p $Project -f $Compose exec -T postgres-test psql -U tenant_test -d tenant_homologacao -Atc 'select count(*) from plans;'
if ($LASTEXITCODE -ne 0 -or "${catalog}".Trim() -ne '0') { throw 'Base de homologacao contem planos antigos. Revisao obrigatoria.' }
Write-Host '11 migracoes e zero planos historicos: aprovados para proxima fase de teste.' -ForegroundColor Green
Write-Host 'Ambiente descartavel permanece ativo. Para encerrar SEM volumes: docker compose -p gs-nh-v122-homologacao -f docker-compose.tenant-homologacao.yml down'
