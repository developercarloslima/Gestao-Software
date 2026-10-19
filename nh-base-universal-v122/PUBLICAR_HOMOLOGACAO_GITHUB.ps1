param([Parameter(Mandatory=$false)][string]$Repositorio = "developercarloslima/nh-tenant-render-homologacao-v122")
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'Git for Windows necessario: winget install Git.Git' }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'GitHub CLI necessario: winget install GitHub.cli' }
gh auth status
if ($LASTEXITCODE -ne 0) { throw 'Execute gh auth login para autenticar sua conta do GitHub' }
if (Test-Path .git) { throw 'Pasta ja e um repositorio. Nao altere o remoto sem revisar.' }
$existing = gh repo view $Repositorio --json name 2>$null
if ($LASTEXITCODE -eq 0) { throw "Repositorio $Repositorio ja existe. Nao sobrescrevi nenhum arquivo." }
$env:GIT_TERMINAL_PROMPT = '0'
git init -b main
if ($LASTEXITCODE -ne 0) { throw 'git init falhou' }
git add .
if ($LASTEXITCODE -ne 0) { throw 'git add falhou' }
git commit -m 'Homologacao Render isolada; nao alterar projetos operacionais'
if ($LASTEXITCODE -ne 0) { throw 'Configure git config --global user.name e user.email antes do commit' }
gh repo create $Repositorio --private --source . --remote origin --push
if ($LASTEXITCODE -ne 0) { throw 'Falha ao criar/publicar repositorio; confira a saida' }
Write-Host "Repositorio criado e enviado: https://github.com/$Repositorio"
