# Package the Windows native image into a portable zip.
# Run AFTER `mvn -pl desktop-host -Pnative package` (under a GraalVM JDK) on Windows.
#   powershell -ExecutionPolicy Bypass -File desktop-host\dist\package-windows.ps1
# Output: desktop-host\target\QPlayer-windows-x64.zip
$ErrorActionPreference = 'Stop'
$repo = Resolve-Path "$PSScriptRoot\..\.."
Set-Location $repo
$T = "desktop-host\target"
$bin = "$T\qplayer.exe"
if (-not (Test-Path $bin)) { throw "native binary $bin not found - run the native build first" }

$dir = "$T\QPlayer"
if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
New-Item -ItemType Directory -Force -Path $dir | Out-Null

# binary + the JDK native DLLs native-image emits next to it, MINUS the AWT/Java2D
# DLLs. AWT is statically reachable (the macOS tray + placeholder still reference it)
# so native-image emits awt.dll/fontmanager.dll/... but the Windows runtime never
# calls AWT — the tray is JNA (WinTray) and the icon ships as app-icon.png, so no
# Java2D placeholder is drawn. Shipping these would just bloat the zip.
Copy-Item $bin "$dir\qplayer.exe"
$awtDlls = @('awt.dll','jawt.dll','fontmanager.dll','freetype.dll','javajpeg.dll',
             'lcms.dll','mlib_image.dll','splashscreen.dll')
Get-ChildItem "$T\*.dll" -ErrorAction SilentlyContinue |
    Where-Object { $awtDlls -notcontains $_.Name } |
    Copy-Item -Destination $dir

# Skija + LWJGL native DLLs straight from the local Maven repo (Windows resolves
# DLLs from the exe directory, so everything goes next to qplayer.exe).
Add-Type -AssemblyName System.IO.Compression.FileSystem
$m2 = if ($env:MAVEN_REPO_LOCAL) { $env:MAVEN_REPO_LOCAL } else { "$env:USERPROFILE\.m2\repository" }
$jars = @()
$jars += Get-ChildItem "$m2\io\github\humbleui\skija-windows-x64" -Recurse -Filter 'skija-windows-x64-*.jar' -ErrorAction SilentlyContinue
$jars += Get-ChildItem "$m2\org\lwjgl" -Recurse -Filter '*-natives-windows.jar' -ErrorAction SilentlyContinue
foreach ($j in $jars) {
  if ($j.Name -match 'sources|javadoc') { continue }
  $zip = [System.IO.Compression.ZipFile]::OpenRead($j.FullName)
  foreach ($e in $zip.Entries) {
    # icudtl.dat is Skija's ICU data — on Windows it must sit beside skija.dll
    # (Linux/macOS bake it into the lib), or FontMgr init dies at load time.
    if ($e.Name -like '*.dll' -or $e.Name -eq 'icudtl.dat') {
      [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, "$dir\$($e.Name)", $true)
    }
  }
  $zip.Dispose()
}
if (-not (Get-ChildItem "$dir\*.dll" -ErrorAction SilentlyContinue)) { throw "no Skija/LWJGL DLLs found under $m2" }

# No launcher .cmd: qplayer.exe self-configures its native-lib path from its own
# location (Main.defaultNativePath), so it runs fine double-clicked or from any
# working directory — the wrapper was just a redundant shell.

Compress-Archive -Path "$dir\*" -DestinationPath "$T\QPlayer-windows-x64.zip" -Force
Write-Host "-> $T\QPlayer-windows-x64.zip"
