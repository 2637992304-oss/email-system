param(
  [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_-]+$')][string]$Label,
  [switch]$Pilot
)
$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')

function Invoke-Checked {
  param([string]$Executable, [string[]]$Arguments)
  & $Executable @Arguments
  if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE" }
}

function Start-Topology {
  param([switch]$Single)
  $compose = @('compose', '-f', 'compose.high-concurrency.yaml')
  if ($Single) {
    Invoke-Checked docker ($compose + @('stop', 'backend-b'))
    $compose += @('-f', 'compose.hc-single.yaml')
  }
  Invoke-Checked docker ($compose + @('up', '-d', '--no-build'))
  Invoke-Checked docker ($compose + @('restart', 'frontend'))
  Invoke-Checked python @('scripts/hc_ready.py')
}

function Measure-Load {
  param([string]$Group, [string]$Mode, [int]$Rate, [string]$Duration, [int]$Repeats)
  Invoke-Checked python @('scripts/hc_run_load.py', '--label', "$Label-$Group", '--mode', $Mode,
      '--rate', $Rate, '--duration', $Duration, '--repeats', $Repeats)
}

$env:HC_FAULTS_ENABLED = 'false'
$env:RATE_CAPACITY = '1000000'
$env:RATE_PER_MINUTE = '1000000'
try {
  Start-Topology -Single
  if ($Pilot) {
    Measure-Load single-warm mixed 50 1m 1
    Measure-Load single mixed 100 3m 1
  } else {
    Measure-Load single-warm mixed 100 3m 1
    Measure-Load single mixed 100 10m 3
  }

  Start-Topology
  if ($Pilot) {
    Measure-Load double-warm mixed 50 1m 1
    Measure-Load double mixed 100 3m 1
    Measure-Load search search 1 1m 1
  } else {
    Measure-Load double-warm mixed 100 3m 1
    Measure-Load double mixed 100 10m 3
    Measure-Load search search 1 10m 3
    Measure-Load attachment attachment 20 10m 3
    Invoke-Checked python @('scripts/hc_backlog.py')
  }
} finally {
  try {
    $env:RATE_CAPACITY = '10'
    $env:RATE_PER_MINUTE = '10'
    Start-Topology
  } finally {
    Pop-Location
  }
}
