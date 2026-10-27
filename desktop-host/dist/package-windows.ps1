# Package the GraalVM native executable as a portable folder + zip, without a JVM.
# Run AFTER `mvn -pl desktop-host -am -Pdist package` under GraalVM JDK 25 on Windows.
#   powershell -ExecutionPolicy Bypass -File desktop-host\dist\package-windows.ps1
# Output: desktop-host\target\QPlayer\ (qplayer.exe + licenses\)
#         desktop-host\target\QPlayer-windows-x64.zip
# The Inno Setup installer (qplayer.iss, built in CI) runs over the QPlayer\ folder.
$ErrorActionPreference = 'Stop'
$repo = Resolve-Path "$PSScriptRoot\..\.."
Set-Location $repo
$T = "desktop-host\target"
$binary = "$T\qplayer.exe"
if (-not (Test-Path $binary)) { throw "$binary not found - run 'mvn -pl desktop-host -am -Pdist package' first" }
if (-not (Test-Path "$T\native-legal")) { throw "$T\native-legal not found - rebuild with -Pdist" }

# The executable's version and icon are embedded at link time by the Maven build.
# Keep the same release filenames and installer payload directory.
$ver = $env:QPLAYER_VERSION
if (-not $ver) { $ver = $env:GITHUB_REF_NAME }
if (-not $ver) { $ver = (git describe --tags --abbrev=0 2>$null) }
$ver = "$ver" -replace '^v', ''
if (-not $ver) { $ver = '0.0.0' }
Write-Host "packaging QPlayer $ver"

$dir = "$T\QPlayer"
Remove-Item -Recurse -Force $dir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $dir | Out-Null
Copy-Item $binary "$dir\qplayer.exe"
Get-ChildItem "$T\*.dll" -File -ErrorAction SilentlyContinue |
    ForEach-Object { Copy-Item $_.FullName $dir }
Copy-Item -Recurse "$T\native-legal" "$dir\licenses"

Compress-Archive -Path "$dir\*" -DestinationPath "$T\QPlayer-windows-x64.zip" -Force
Write-Host "-> $T\QPlayer-windows-x64.zip"
