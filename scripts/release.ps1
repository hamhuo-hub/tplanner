# Release script: tagging = release.
# The git tag is the single source of truth for versioning; scripts/version.mjs
# derives the package.json version via `git describe` at build time.
#
# Usage:
#   .\scripts\release.ps1 4.2.1          # create tag v4.2.1 locally
#   .\scripts\release.ps1 4.2.1 -Push    # create tag and push to origin
#
# After tagging, check the version that will be built: node scripts/version.mjs
param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [switch]$Push
)

$ErrorActionPreference = 'Stop'

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "Version must be x.y.z (e.g. 4.2.1), got: $Version"
}
$newParts = [int[]]($Version -split '\.')
$tag = "v$Version"

# Zero-pad each semver part to 4 digits so that 4.0.10 > 4.0.2 compares correctly.
function Pad([int[]]$parts) {
    ($parts | ForEach-Object { '{0:D4}' -f $_ }) -join ''
}

# 1. Working tree must be clean, otherwise the tag would not include pending changes.
if (git status --porcelain) {
    throw 'Working tree is dirty; commit or discard changes before tagging.'
}

# 2. The target tag must not already exist.
if (git tag -l $tag) {
    throw "Tag $tag already exists."
}

# 3. Version must be strictly higher than the latest release tag
#    (v* plus legacy desktop_*, so the derived version stays monotonic).
$latest = git tag -l |
    Where-Object { $_ -match '^(v|desktop_)\d+\.\d+\.\d+$' } |
    Sort-Object -Descending { Pad ([int[]](($_ -replace '^(v|desktop_)', '') -split '\.')) } |
    Select-Object -First 1

if ($latest) {
    $latestNum = $latest -replace '^(v|desktop_)', ''
    if ((Pad $newParts) -le (Pad ([int[]]($latestNum -split '\.')))) {
        throw "Version $Version is not higher than the latest tag $latest; bump the version."
    }
}
else {
    Write-Warning 'No release tags found; this will be the first release tag.'
}

# 4. Confirm the current branch.
$branch = git rev-parse --abbrev-ref HEAD
if ($branch -ne 'master') {
    Write-Warning "Current branch is $branch (releases are usually tagged on master)."
}

# 5. Create an annotated tag (git describe prefers annotated tags).
git tag -a $tag -m "Release $tag"
Write-Host "Tag created: $tag"
Write-Host "Next build will stamp package.json with: $Version"

# 6. Optional push.
if ($Push) {
    git push origin $tag
    Write-Host "Pushed $tag to origin."
}
