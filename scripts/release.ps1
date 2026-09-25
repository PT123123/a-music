# release.ps1 — a-music signed-release pipeline (one script, four subcommands)
#
#   pwsh ./scripts/release.ps1            build  -> dist/amusic.apk (+ archive + build.txt) + self-check
#   pwsh ./scripts/release.ps1 verify             only self-check the APK already in dist/
#   pwsh ./scripts/release.ps1 bump               versionCode+1, versionName last segment+1
#   pwsh ./scripts/release.ps1 publish            tag + GitHub Release + server-side digest/size check
#
# The permanent direct link Obtainium tracks:
#   https://github.com/<owner>/<repo>/releases/latest/download/amusic.apk
#
# Requirements: gradlew, adb not needed; Android SDK (apksigner/aapt2 via ANDROID_HOME);
#               gh authenticated for `publish`. keystore.properties must exist for a signed build.

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('build', 'verify', 'bump', 'publish')]
    [string]$Command = 'build'
)

$ErrorActionPreference = 'Stop'

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$AppGradle = Join-Path $RepoRoot 'app/build.gradle.kts'
$DistDir   = Join-Path $RepoRoot 'dist'
$FixedApk = Join-Path $DistDir 'amusic.apk'

# ---- gh wrapper: stderr from gh must not be promoted to a terminating error ----
$gh = (Get-Command gh -ErrorAction SilentlyContinue).Source
function Invoke-Gh {
    param([string[]]$GhArgs)
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $script:GhOutput = ((& $script:gh @GhArgs 2>&1) | Out-String)
        return $LASTEXITCODE
    } finally { $ErrorActionPreference = $eap }
}

# ---- repo slug owner/repo from origin remote (never rely on gh's inference) ----
function Get-RepoSlug {
    $url = (git -C $RepoRoot config --get remote.origin.url)
    if ($url -match 'github\.com[:/]([^/]+)/(.+?)(?:\.git)?$') {
        return "$($Matches[1])/$($Matches[2])"
    }
    throw "could not parse owner/repo from remote.origin.url: $url"
}

# ---- latest Android build-tools dir (holds apksigner.bat + aapt2.exe) ----
function Get-BuildTools {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { throw 'ANDROID_HOME is not set' }
    $bt = Join-Path $sdk 'build-tools'
    $latest = Get-ChildItem $bt -Directory | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $latest) { throw "no build-tools under $bt" }
    return $latest.FullName
}

# ---- read versionName / versionCode from app/build.gradle.kts ----
function Get-Version {
    $txt = Get-Content $AppGradle -Raw
    if ($txt -notmatch 'versionCode\s*=\s*(\d+)') { throw 'versionCode not found' }
    $vc = [int]$Matches[1]
    if ($txt -notmatch 'versionName\s*=\s*"([^"]+)"') { throw 'versionName not found' }
    $vn = $Matches[1]
    return @{ Code = $vc; Name = $vn }
}

# ---- source fingerprint: git blob SHA-1 of tracked build-relevant files ----
function Get-SourceFingerprint {
    # Content fingerprint = git blob SHA-1 of every tracked build-relevant file.
    # Recomputed at publish time; if source changed since the APK was built the two
    # fingerprints differ and publish refuses (forces a rebuild).
    $files = @('app/src', 'app/build.gradle.kts', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties') |
        ForEach-Object { git -C $RepoRoot ls-files $_ }
    return (git -C $RepoRoot hash-object @files) -join "`n"
}

# ------------------------------------------------------------------ BUILD
function Build {
    $v = Get-Version
    Write-Host ">> Building signed release APK (version $($v.Name) / code $($v.Code))"

    if (-not (Test-Path (Join-Path $RepoRoot 'keystore.properties'))) {
        Write-Warning 'keystore.properties not found — release APK will be UNSIGNED (not suitable for distribution).'
    }

    Push-Location $RepoRoot
    try {
        if (Test-Path ./gradlew.bat) { ./gradlew.bat assembleRelease } else { ./gradlew assembleRelease }
        if ($LASTEXITCODE -ne 0) { throw "assembleRelease failed (exit $LASTEXITCODE)" }
    } finally { Pop-Location }

    $built = Join-Path $RepoRoot 'app/build/outputs/apk/release/app-release.apk'
    if (-not (Test-Path $built)) { throw "expected release APK not found: $built" }

    New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
    $archive = Join-Path $DistDir "amusic-$($v.Name).apk"
    Copy-Item $built $FixedApk -Force
    Copy-Item $built $archive -Force
    Set-Content -Path (Join-Path $DistDir 'amusic.apk.build.txt') -Value (Get-SourceFingerprint) -Encoding UTF8

    Write-Host ">> Wrote $FixedApk and $archive"
    Verify
}

# ------------------------------------------------------------------ VERIFY
function Verify {
    $bt = Get-BuildTools
    $apk = if (Test-Path $FixedApk) { $FixedApk } else { throw "$FixedApk not found — run 'build' first" }

    Write-Host ">> Self-check: $apk"
    $apksigner = Join-Path $bt 'apksigner.bat'
    # Redirect to a file (NOT a pipe): apksigner.bat spawns Java, whose inherited
    # stdout handle would otherwise keep a PowerShell pipeline open and hang it.
    $sigOut = Join-Path $env:TEMP 'amusic-apksigner.txt'
    & $apksigner verify --print-certs $apk > $sigOut 2>&1
    $out = Get-Content $sigOut -Raw
    Write-Host $out
    if ($out -match 'CN=Android Debug') {
        throw 'REJECTED: APK is signed with the DEBUG certificate — not distributable.'
    }
    if ($out -notmatch 'Signer #1 certificate') {
        throw 'REJECTED: APK does not appear to be signed at all.'
    }

    $aapt2 = Join-Path $bt 'aapt2.exe'
    $badgeOut = Join-Path $env:TEMP 'amusic-aapt2.txt'
    & $aapt2 dump badging $apk > $badgeOut 2>&1
    $badging = Get-Content $badgeOut -Raw
    $pkg = if ($badging -match "package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'") {
        "package=$($Matches[1]) versionName=$($Matches[3]) versionCode=$($Matches[2])"
    } else { '(package/versionName/versionCode not parsed)' }
    $native = if ($badging -match "native-code: '([^']+)'") { "native-code=$($Matches[1])" } else { '' }
    Write-Host ">> $pkg $native"

    Write-Host '>> OK: APK is signed (non-debug) and ready to distribute.' -ForegroundColor Green
}

# ------------------------------------------------------------------ BUMP
function Bump {
    $v = Get-Version
    $newCode = $v.Code + 1
    $parts = $v.Name.Split('.')
    $parts[-1] = ([int]$parts[-1] + 1).ToString()
    $newName = $parts -join '.'

    $txt = Get-Content $AppGradle -Raw
    $txt = $txt -replace 'versionCode\s*=\s*\d+', "versionCode = $newCode"
    $txt = $txt -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$newName`""
    Set-Content -Path $AppGradle -Value $txt -Encoding UTF8 -NoNewline

    Write-Host ">> Bumped versionName $($v.Name) -> $newName, versionCode $($v.Code) -> $newCode"
    Write-Host '   Re-run the build step to produce the new APK before publishing.' -ForegroundColor Cyan
}

# ------------------------------------------------------------------ PUBLISH
function Publish {
    $slug = Get-RepoSlug
    $v = Get-Version
    $tag = "v$($v.Name)"
    $url = "https://github.com/$slug/releases/latest/download/amusic.apk"

    # Gate 1: clean working tree
    $status = (git -C $RepoRoot status --porcelain)
    if ($status) { throw "working tree is not clean — commit/stash first:`n$status" }

    # Gate 2: HEAD already pushed to origin
    $branch = (git -C $RepoRoot rev-parse --abbrev-ref HEAD)
    $head = (git -C $RepoRoot rev-parse HEAD)
    $remoteHead = (git -C $RepoRoot rev-parse "origin/$branch")
    if ($head -ne $remoteHead) { throw "HEAD is not pushed to origin/$branch — push first." }

    # Gate 3: APK matches current source
    if (-not (Test-Path $FixedApk)) { throw "$FixedApk missing — run 'build' first." }
    $recorded = (Get-Content (Join-Path $DistDir 'amusic.apk.build.txt') -Raw).Trim()
    $current = Get-SourceFingerprint
    if ($recorded -ne $current) {
        throw 'APK is stale vs current source — re-run the build step before publishing.'
    }

    # Release notes (changelog since last tag, else just the version)
    $notes = Join-Path $DistDir 'release-notes.md'
    $lastTag = (git -C $RepoRoot describe --tags --abbrev=0 2>$null)
    if ($lastTag) {
        $log = (git -C $RepoRoot log --oneline "$lastTag..HEAD" | Out-String).Trim()
        Set-Content -Path $notes -Value "# $tag`n`n## Changes since $lastTag`n`n$log" -Encoding UTF8
    } else {
        Set-Content -Path $notes -Value "# $tag`n`nInitial signed release (version $($v.Name), code $($v.Code))." -Encoding UTF8
    }

    # Create the GitHub Release with a FIXED asset name for the permanent link
    $archive = Join-Path $DistDir "amusic-$($v.Name).apk"
    Write-Host ">> Creating GitHub Release $tag in $slug"
    $rc = Invoke-Gh @('release', 'create', $tag,
        $FixedApk, $archive,
        '-R', $slug,
        '--title', $tag,
        '--notes-file', $notes,
        '--target', $head)
    if ($rc -ne 0) { throw "gh release create failed:`n$GhOutput" }
    Write-Host $GhOutput

    # Gate 4: confirm what GitHub actually serves, without downloading the APK.
    # Release assets are HTTPS-only (SSH has no transport for them), so the old
    # "curl the permanent link" leg died on this machine's direct-connection resets
    # *after* the release was already public — leaving a published-but-unverified state.
    # The API instead reports a server-computed digest per asset; compare that plus the
    # size against the local APK, and require this release to be "latest", since that is
    # exactly what /releases/latest/download/amusic.apk resolves through.
    $localHash = (Get-FileHash -Algorithm SHA256 $FixedApk).Hash.ToLower()
    $localSize = (Get-Item $FixedApk).Length

    # What the permanent link actually resolves to. `gh release view latest` does not
    # exist as a name, so ask the REST endpoint behind that URL (it means "latest
    # published, non-draft, non-prerelease" -- i.e. exactly the link's semantics).
    $rc = Invoke-Gh @('api', "repos/$slug/releases/latest", '--jq', '.tag_name')
    if ($rc -ne 0) { throw "gh api releases/latest failed:`n$GhOutput" }
    $latestTag = $GhOutput.Trim()
    if ($latestTag -ne $tag) { throw "/latest/ points at $latestTag, not $tag — the permanent link would serve a different build." }
    Write-Host ">> Permanent link resolves to $latestTag."

    $rc = Invoke-Gh @('release', 'view', $tag, '-R', $slug, '--json', 'assets')
    if ($rc -ne 0) { throw "gh release view failed:`n$GhOutput" }
    $served = $GhOutput | ConvertFrom-Json
    $asset = @($served.assets) | Where-Object { $_.name -eq 'amusic.apk' } | Select-Object -First 1
    if (-not $asset) { throw "amusic.apk not among the uploaded assets: $(@($served.assets).name -join ', ')" }
    Write-Host ">> Asset on GitHub: $($asset.name) size=$($asset.size) digest=$($asset.digest)"

    if ($asset.digest) {
        if ($asset.digest.ToLower() -ne "sha256:$localHash") { throw "GitHub digest != local APK! github=$($asset.digest) local=sha256:$localHash" }
        if ([long]$asset.size -ne [long]$localSize) { throw "GitHub asset size $($asset.size) != local $localSize" }
        Write-Host ">> GitHub-side digest and size match the local APK." -ForegroundColor Green
    } else {
        Write-Warning 'gh reported no digest — falling back to downloading the asset.'
        $tmp = Join-Path $env:TEMP 'amusic-dl-check.apk'
        curl.exe -sSL -o $tmp -w "http=%{http_code} bytes=%{size_download}`n" $url
        if (-not (Test-Path $tmp)) { throw 'download failed — permanent link not reachable.' }
        $dlHash = (Get-FileHash -Algorithm SHA256 $tmp).Hash.ToLower()
        Remove-Item $tmp -Force
        if ($dlHash -ne $localHash) { throw "sha256 mismatch! local=$localHash dl=$dlHash" }
    }

    Write-Host ">> Published & verified. Obtainium link:`n   $url" -ForegroundColor Green
    Write-Host "   sha256: $localHash" -ForegroundColor Green
    if (-not ($env:HTTPS_PROXY -or $env:ALL_PROXY)) {
        Write-Host '   (permanent link not fetched. Live check needs a proxy on this network:' ` -ForegroundColor DarkGray
        Write-Host '    HTTPS_PROXY=http://<proxy>:<port> curl -sSI <url> -o /dev/null -w "%{http_code}")' -ForegroundColor DarkGray
    }
}

switch ($Command) {
    'build'   { Build }
    'verify'  { Verify }
    'bump'    { Bump }
    'publish' { Publish }
}
