param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Require([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

$repository = (Resolve-Path -LiteralPath $RepositoryRoot).Path.TrimEnd('\', '/')
$canonicalTokens = 'shared/src/main/kotlin/com/hamhuo/tplanner/designsystem/TPlannerDesignTokens.kt'
$canonicalTokensPath = Join-Path $repository $canonicalTokens
Require (Test-Path -LiteralPath $canonicalTokensPath) "Missing canonical design tokens: $canonicalTokens"

# Raw ARGB/CSS literals belong only in the canonical token source. These are numeric masks, not
# colors, and are intentionally allowlisted by exact path and exact literal so the exception cannot
# silently grow into a renderer/UI escape hatch.
$numericHexAllowlist = @{
    'shared/src/main/kotlin/com/hamhuo/tplanner/WatchTaskProtocol.kt' = @('0xFFFF_FFFF_FFFFL')
    'app/src/main/java/com/hamhuo/tplanner/syncv3/SyncV3Uploader.kt' = @('0xFFFF_FFFF_FFFFL')
    'wear/src/main/kotlin/com/hamhuo/tplanner/watchface/FaceTide.kt' = @('0x00FFFFFF')
}
$sourceRoots = @(
    'app/src/main',
    'wear/src/main',
    'shared/src/main'
)
$violations = [System.Collections.Generic.HashSet[string]]::new()
$rawColorPattern = [regex]'(?i)(?:0x[0-9a-f_]{8,}L?|#[0-9a-f]{6}(?:[0-9a-f]{2})?)(?![0-9a-f])'
$composeColorPattern = [regex]'(?i)Color\s*\(\s*(0x[0-9a-f_]+)'

foreach ($sourceRoot in $sourceRoots) {
    $absoluteRoot = Join-Path $repository $sourceRoot
    if (-not (Test-Path -LiteralPath $absoluteRoot)) { continue }
    $files = Get-ChildItem -LiteralPath $absoluteRoot -Recurse -File |
        Where-Object { $_.Extension -in @('.kt', '.java') }
    foreach ($file in $files) {
        $relative = $file.FullName.Substring($repository.Length).TrimStart('\', '/') -replace '\\', '/'
        if ($relative -eq $canonicalTokens) { continue }
        $allowed = if ($numericHexAllowlist.ContainsKey($relative)) {
            $numericHexAllowlist[$relative]
        } else {
            @()
        }
        $lineNumber = 0
        foreach ($line in Get-Content -LiteralPath $file.FullName) {
            $lineNumber++
            foreach ($match in $rawColorPattern.Matches($line)) {
                if ($allowed -contains $match.Value) { continue }
                [void]$violations.Add("${relative}:${lineNumber}: raw color literal $($match.Value)")
            }
            foreach ($match in $composeColorPattern.Matches($line)) {
                [void]$violations.Add("${relative}:${lineNumber}: direct Compose color $($match.Groups[1].Value)")
            }
        }
    }
}

if ($violations.Count -gt 0) {
    $details = ($violations | Sort-Object) -join [Environment]::NewLine
    throw "Android design-token drift detected. Move colors to $canonicalTokens or document a precise non-color allowlist:$([Environment]::NewLine)$details"
}

$tokenContent = Get-Content -LiteralPath $canonicalTokensPath -Raw
Require ($tokenContent.Contains('object TPlannerColors')) 'Canonical product color tokens are missing'
Require ($tokenContent.Contains('object TPlannerTypography')) 'Canonical typography tokens are missing'
Require ($tokenContent.Contains('object TPlannerGeometry')) 'Canonical geometry tokens are missing'
Require ($tokenContent.Contains('object TPlannerWatchFacePalette')) 'Canonical watch-face palette is missing'

$densities = @('mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi')
$canonicalLauncher = Join-Path $repository 'wear/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png'
Require (Test-Path -LiteralPath $canonicalLauncher) "Missing canonical Wear launcher source: $canonicalLauncher"
foreach ($density in $densities) {
    $phone = Join-Path $repository "app/src/main/res/mipmap-$density/ic_launcher_foreground.png"
    $wear = Join-Path $repository "wear/src/main/res/mipmap-$density/ic_launcher_foreground.png"
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
    $content = Get-Content -LiteralPath (Join-Path $repository $relative) -Raw
    Require ($content.Contains('@mipmap/ic_launcher_foreground')) "$relative bypasses the canonical foreground"
    Require ($content.Contains('@drawable/ic_launcher_background')) "$relative bypasses the canonical background"
}

$phoneBackground = Get-Content -LiteralPath (Join-Path $repository 'app/src/main/res/drawable/ic_launcher_background.xml') -Raw
$wearBackground = Get-Content -LiteralPath (Join-Path $repository 'wear/src/main/res/drawable/ic_launcher_background.xml') -Raw
$backgroundPattern = [regex]'(?i)android:color="(#[0-9a-f]{6,8})"'
$phoneBackgroundColor = $backgroundPattern.Match($phoneBackground).Groups[1].Value.ToUpperInvariant()
$wearBackgroundColor = $backgroundPattern.Match($wearBackground).Groups[1].Value.ToUpperInvariant()
Require ($phoneBackgroundColor -eq '#1B1B1D') 'Phone launcher bypasses the canonical background color'
Require ($wearBackgroundColor -eq $phoneBackgroundColor) 'Phone/Wear launcher background drift'

$phoneManifest = Get-Content -LiteralPath (Join-Path $repository 'app/src/main/AndroidManifest.xml') -Raw
$manifest = Get-Content -LiteralPath (Join-Path $repository 'wear/src/main/AndroidManifest.xml') -Raw
foreach ($entry in @(
    @{ Name = 'Phone'; Content = $phoneManifest },
    @{ Name = 'Wear'; Content = $manifest }
)) {
    Require ($entry.Content.Contains('android:icon="@mipmap/ic_launcher"')) "$($entry.Name) manifest bypasses the canonical launcher"
    Require ($entry.Content.Contains('android:roundIcon="@mipmap/ic_launcher_round"')) "$($entry.Name) manifest bypasses the canonical round launcher"
}

$tideXml = Get-Content -LiteralPath (Join-Path $repository 'wear/src/main/res/xml/watch_face.xml') -Raw
$nextXml = Get-Content -LiteralPath (Join-Path $repository 'wear/src/main/res/xml/watch_face_next.xml') -Raw
Require ($manifest.Contains('@drawable/preview_tide_static')) 'Tide manifest preview is not the static PNG'
Require ($manifest.Contains('@drawable/preview_next')) 'Next manifest preview is not the static PNG'
Require ($tideXml.Contains('@drawable/preview_tide_static')) 'Tide watch-face thumbnail is not the static PNG'
Require ($nextXml.Contains('@drawable/preview_next')) 'Next watch-face thumbnail is not the static PNG'

Write-Host 'Android design tokens, launcher assets, and watch-face previews are consistent.'
