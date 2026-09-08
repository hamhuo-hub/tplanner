param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [switch]$UseExistingRenders
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path -LiteralPath $RepositoryRoot).Path
Push-Location -LiteralPath $repository
try {
    if (-not $UseExistingRenders) {
        & .\gradlew.bat :wear:testDebugUnitTest --tests 'com.hamhuo.tplanner.Hop*Test' --rerun-tasks --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Hop render/test verification failed; previews were not replaced.' }
    }
    $renders = Join-Path $repository 'wear/build/outputs/hop-preview'
    $assets = Join-Path $repository 'design-assets/hop'
    [System.IO.Directory]::CreateDirectory($assets) | Out-Null
    $names = @('stage-0', 'hero', 'reference-time', 'size-comparison', 'stages', 'edge-cases', 'motion', 'ambient')
    foreach ($name in ($names + 'picker')) {
        $render = Join-Path $renders "$name.png"
        if (-not (Test-Path -LiteralPath $render) -or (Get-Item -LiteralPath $render).Length -lt 1000) {
            throw "Missing native render: $render. Run without -UseExistingRenders."
        }
    }
    foreach ($name in $names) {
        Copy-Item -LiteralPath (Join-Path $renders "$name.png") -Destination (Join-Path $assets "$name.png")
    }
    Copy-Item -LiteralPath (Join-Path $renders 'picker.png') `
        -Destination (Join-Path $repository 'wear/src/main/res/drawable-nodpi/preview_hop.png')
    Write-Host 'Generated Hop picker and review sheets from the production Android Canvas painter.'
} finally {
    Pop-Location
}
