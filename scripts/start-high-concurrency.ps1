param([switch]$TestMode)
$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
  & ./backend/mvnw.cmd -q -f backend/pom.xml -DskipTests package
  if ($LASTEXITCODE -ne 0) { throw 'Backend build failed' }
  $composeArgs = @('compose','-f','compose.high-concurrency.yaml')
  if ($TestMode) { $composeArgs += @('-f','compose.hc-test.yaml') }
  & docker @composeArgs up -d --build
  if ($LASTEXITCODE -ne 0) { throw 'Compose startup failed' }
  & python scripts/hc_ready.py
  if ($LASTEXITCODE -ne 0) { throw 'Service readiness failed' }
  Write-Output 'High concurrency environment: http://localhost:5180'
} finally { Pop-Location }
