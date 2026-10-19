$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw "Git nao encontrado. Instale Git for Windows." }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw "GitHub CLI (gh) nao encontrado. Instale com: winget install --id GitHub.cli" }
gh auth status
if ($LASTEXITCODE -ne 0) { gh auth login }
if (-not (Test-Path .git)) {
    git init -b main
    git add .
    git commit -m "Preparar deploy Render"
}
$targetRepo = "developercarloslima/gestao-software-control"
$existing = gh repo view $targetRepo --json name 2>$null
if ($LASTEXITCODE -eq 0) { throw "Repositorio $targetRepo ja existe; confira antes de enviar para evitar sobrescrever outro projeto." }
gh repo create $targetRepo --private --source . --remote origin --push
if ($LASTEXITCODE -ne 0) { throw "Falha ao publicar no GitHub. Leia a mensagem acima." }
Write-Host "Publicado: https://github.com/$targetRepo"
