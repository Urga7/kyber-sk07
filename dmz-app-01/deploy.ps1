<#
.SYNOPSIS
    Deploy the kyber REST API source to kyber-app-01 and restart the service.

.DESCRIPTION
    Copies the local app/ directory to a staging dir in the kyber user's home via
    the VyOS jump-host, then installs it into /opt/kyber-api/app with the correct
    ownership and restarts kyber-api. Run this after editing the source in PyCharm.

    Login is as 'kyber', but /opt/kyber-api/app is owned by 'kyberapi', so the copy
    lands in ~/kyber-app-staging first and a sudo step moves it into place.

.PARAMETER Jump
    SSH jump-host (VyOS WAN), user@host. Default vyos@88.200.24.237.

.PARAMETER Target
    app-01 login, user@host. Default kyber@192.168.7.10.

.EXAMPLE
    .\deploy.ps1
#>
[CmdletBinding()]
param(
    [string]$Jump   = 'vyos@88.200.24.237',
    [string]$Target = 'kyber@192.168.7.10'
)

$ErrorActionPreference = 'Stop'
$src = Join-Path $PSScriptRoot 'app'

if (-not (Test-Path $src)) {
    throw "Source directory not found: $src"
}

Write-Host "==> Staging $src -> ${Target}:~/kyber-app-staging (via $Jump)" -ForegroundColor Cyan

# Fresh staging dir so deletions propagate (scp has no --delete).
ssh -J $Jump $Target 'rm -rf ~/kyber-app-staging && mkdir -p ~/kyber-app-staging'
if ($LASTEXITCODE -ne 0) { throw "Failed to reset staging dir (exit $LASTEXITCODE)" }

scp -r -J $Jump "$src/*" "${Target}:~/kyber-app-staging/"
if ($LASTEXITCODE -ne 0) { throw "scp failed (exit $LASTEXITCODE)" }

Write-Host "==> Installing into /opt/kyber-api/app and restarting kyber-api" -ForegroundColor Cyan

# Single-quoted here-string: nothing is expanded locally; runs verbatim on the host.
$remote = @'
set -e
sudo rsync -a --delete ~/kyber-app-staging/ /opt/kyber-api/app/
sudo chown -R kyberapi:kyberapi /opt/kyber-api/app
sudo systemctl restart kyber-api
sudo systemctl --no-pager --lines=0 status kyber-api
'@

ssh -t -J $Jump $Target $remote
if ($LASTEXITCODE -ne 0) { throw "Remote install/restart failed (exit $LASTEXITCODE)" }

Write-Host "==> Deployed." -ForegroundColor Green
