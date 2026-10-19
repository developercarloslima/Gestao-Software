# RESTRITO A HOMOLOGACAO: sem parar ou remover containers da Gestão/NH existentes.
param([switch]$SomenteAnalise)
$ErrorActionPreference='Stop'
Write-Host 'Gestão Cloud v106 - Testes isolados' -ForegroundColor Cyan
if(-not (Test-Path 'backend/pom.xml') -or -not (Test-Path 'docker-compose.homologacao.yml')) {
  throw 'Abra o PowerShell na raiz da Gestão Software v106, não na pasta da instalação da NH.'
}
if(-not (Get-Command docker -ErrorAction SilentlyContinue)) {throw 'Instale e inicie o Docker Desktop.'}
docker info | Out-Null
if($LASTEXITCODE -ne 0){throw 'Docker Desktop indisponível.'}
Write-Host 'Validando docker compose...' -ForegroundColor Yellow
docker compose -p gs-cloud-v106-homologacao -f docker-compose.homologacao.yml config --quiet
if($LASTEXITCODE -ne 0){throw 'Configuração inválida.'}
if($SomenteAnalise){
 Write-Host 'Configuração validada. Nenhuma aplicação iniciada.' -ForegroundColor Green
 exit 0
}
Write-Host 'Iniciando banco descartável e API isolada (sem exposição de portas)...' -ForegroundColor Yellow
docker compose -p gs-cloud-v106-homologacao -f docker-compose.homologacao.yml up --build --abort-on-container-exit --exit-code-from validator
if($LASTEXITCODE -ne 0){throw 'Homologação falhou. Não aplicar atualização em produção. Verifique os logs.'}
Write-Host 'Testes Docker/PostgreSQL concluídos. Ainda falta homologar DNS, agente real, backup e isolamento.' -ForegroundColor Green
Write-Host 'Os containers do projeto separado permanecem disponíveis para inspeção.'
