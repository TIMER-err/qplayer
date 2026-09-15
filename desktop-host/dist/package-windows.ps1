# Package the Windows desktop app with jpackage into a portable folder + zip.
# Run AFTER `mvn -pl desktop-host -Pdist package` (under a JDK 21) on Windows.
#   powershell -ExecutionPolicy Bypass -File desktop-host\dist\package-windows.ps1
# Output: desktop-host\target\QPlayer\ (qplayer.exe + app\ + runtime\)
#         desktop-host\target\QPlayer-windows-x64.zip
# The Inno Setup installer (qplayer.iss, built in CI) runs over the QPlayer\ folder.
$ErrorActionPreference = 'Stop'
$repo = Resolve-Path "$PSScriptRoot\..\.."
Set-Location $repo
$T = "desktop-host\target"
$app = "$T\app"
if (-not (Test-Path "$app\qplayer.jar")) { throw "$app\qplayer.jar not found - run 'mvn -pl desktop-host -Pdist package' first" }

# Version for the exe's file properties / Add-Remove Programs. CI passes the
# release tag; a local build falls back to the latest git tag, then 0.0.0.
$ver = $env:QPLAYER_VERSION
if (-not $ver) { $ver = $env:GITHUB_REF_NAME }
if (-not $ver) { $ver = (git describe --tags --abbrev=0 2>$null) }
$ver = "$ver" -replace '^v', ''
if (-not $ver) { $ver = '0.0.0' }
# jpackage only accepts a numeric --app-version (digits and periods). A tag suffix
# such as "-debug" is what version.properties reports to the update check, but it
# cannot go into the bundle metadata, so drop everything from the first
# non-numeric character.
$bundleVer = ($ver -replace '[^0-9.].*$', '') -replace '\.+$', ''
if (-not $bundleVer) { $bundleVer = '0.0.0' }
Write-Host "packaging QPlayer $ver (bundle version $bundleVer)"

# Shared module list (see jre-modules.txt), comments stripped. The Windows-only
# SMTC implementation serves cover art from a loopback HttpServer, so only this
# package carries jdk.httpserver.
$moduleList = @(Get-Content "$PSScriptRoot\jre-modules.txt" |
    ForEach-Object { ($_ -replace '#.*', '').Trim() } |
    Where-Object { $_ })
$moduleList += 'jdk.httpserver'
$mods = $moduleList -join ','

# Shared low-footprint JVM options, represented as repeated jpackage
# --java-options arguments.
$javaOptionArgs = @()
Get-Content "$PSScriptRoot\jvm-options.txt" |
    ForEach-Object { ($_ -replace '#.*', '').Trim() } |
    Where-Object { $_ } |
    ForEach-Object {
        $javaOptionArgs += '--java-options'
        $javaOptionArgs += $_
    }

$out = "$T\pkg"
$dir = "$T\QPlayer"
Remove-Item -Recurse -Force $out, $dir -ErrorAction SilentlyContinue

# jpackage jlinks the runtime itself from --add-modules. The default GUI-subsystem
# launcher means no console window on double-click; WinConsole re-attaches to a
# parent terminal's console when started from a shell, so logs still stream there.
& jpackage --type app-image `
    --name qplayer --app-version $bundleVer --vendor t1m3 --description "QPlayer" `
    --input $app --main-jar qplayer.jar --main-class dev.t1m3.qplayer.desktop.app.Main `
    --dest $out --icon "$PSScriptRoot\..\src\main\resources\app-icon.ico" `
    --add-modules $mods `
    @javaOptionArgs `
    --jlink-options "--strip-native-commands --strip-debug --no-man-pages --no-header-files --compress=zip-6 --dedup-legal-notices=error-if-not-same-content"
if ($LASTEXITCODE -ne 0) { throw "jpackage failed ($LASTEXITCODE)" }

Move-Item "$out\qplayer" $dir
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue

Compress-Archive -Path "$dir\*" -DestinationPath "$T\QPlayer-windows-x64.zip" -Force
Write-Host "-> $T\QPlayer-windows-x64.zip"
