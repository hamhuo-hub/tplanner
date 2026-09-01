param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Require([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

$densities = @('mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi')
foreach ($density in $densities) {
    $phone = Join-Path $RepositoryRoot "app/src/main/res/mipmap-$density/ic_launcher_foreground.png"
    $wear = Join-Path $RepositoryRoot "wear/src/main/res/mipmap-$density/ic_launcher_foreground.png"
    Require (Test-Path -LiteralPath $phone) "Missing Phone launcher foreground: $phone"
    Require (Test-Path -LiteralPath $wear) "Missing Wear launcher foreground: $wear"
    $phoneHash = (Get-FileHash -LiteralPath $phone -Algorithm SHA256).Hash
    $wearHash = (Get-FileHash -LiteralPath $wear -Algorithm SHA256).Hash
    Require ($phoneHash -eq $wearHash) "Launcher foreground drift at $density"
}

$adaptiveFiles = @(
    'app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml',
    'app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml',
    'wear/src/main/res/mipmap-anydpi-v26/ic_launcher.xml',
    'wear/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml'
)
foreach ($relative in $adaptiveFiles) {
    $content = Get-Content -LiteralPath (Join-Path $RepositoryRoot $relative) -Raw
    Require ($content.Contains('@mipmap/ic_launcher_foreground')) "$relative bypasses the canonical foreground"
    Require ($content.Contains('@drawable/ic_launcher_background')) "$relative bypasses the canonical background"
}

$manifest = Get-Content -LiteralPath (Join-Path $RepositoryRoot 'wear/src/main/AndroidManifest.xml') -Raw
$tideXml = Get-Content -LiteralPath (Join-Path $RepositoryRoot 'wear/src/main/res/xml/watch_face.xml') -Raw
$nextXml = Get-Content -LiteralPath (Join-Path $RepositoryRoot 'wear/src/main/res/xml/watch_face_next.xml') -Raw
Require ($manifest.Contains('@drawable/preview_tide_static')) 'Tide manifest preview is not the static PNG'
Require ($manifest.Contains('@drawable/preview_next')) 'Next manifest preview is not the static PNG'
Require ($tideXml.Contains('@drawable/preview_tide_static')) 'Tide watch-face thumbnail is not the static PNG'
Require ($nextXml.Contains('@drawable/preview_next')) 'Next watch-face thumbnail is not the static PNG'

Write-Host 'Android brand assets are consistent.'
