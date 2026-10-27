param(
    [Parameter(Mandatory = $true)][string]$AppVersion,
    [Parameter(Mandatory = $true)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$rc = (Get-Command rc.exe -ErrorAction Stop).Source
$out = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($out) | Out-Null
$match = [regex]::Match($AppVersion, '^(\d+)\.(\d+)\.(\d+)')
if (-not $match.Success) { throw "Native executable version requires major.minor.patch: $AppVersion" }
$parts = @($match.Groups[1].Value, $match.Groups[2].Value, $match.Groups[3].Value, '0')
foreach ($part in $parts) {
    if ([long]$part -gt 65535) { throw 'Windows executable version components must fit in 16 bits' }
}
$numeric = $parts -join ','
$icon = (Resolve-Path "$PSScriptRoot\..\src\main\resources\app-icon.ico").Path.Replace('\', '\\')
$version = $AppVersion.Replace('\', '\\').Replace('"', '\"')
$source = @"
#include <winver.h>
1 ICON "$icon"
VS_VERSION_INFO VERSIONINFO
 FILEVERSION $numeric
 PRODUCTVERSION $numeric
 FILEFLAGSMASK VS_FFI_FILEFLAGSMASK
 FILEFLAGS 0
 FILEOS VOS_NT_WINDOWS32
 FILETYPE VFT_APP
 FILESUBTYPE 0
BEGIN
 BLOCK "StringFileInfo"
 BEGIN
  BLOCK "040904b0"
  BEGIN
   VALUE "CompanyName", "t1m3\0"
   VALUE "FileDescription", "QPlayer\0"
   VALUE "FileVersion", "$version\0"
   VALUE "InternalName", "qplayer\0"
   VALUE "OriginalFilename", "qplayer.exe\0"
   VALUE "ProductName", "QPlayer\0"
   VALUE "ProductVersion", "$version\0"
  END
 END
 BLOCK "VarFileInfo"
 BEGIN
  VALUE "Translation", 0x0409, 1200
 END
END
"@
$inputFile = Join-Path $out 'qplayer-version.rc'
$outputFile = Join-Path $out 'qplayer-version.res'
[IO.File]::WriteAllText($inputFile, $source, [Text.Encoding]::Unicode)
& $rc /nologo /fo $outputFile $inputFile
if ($LASTEXITCODE -ne 0) { throw "Windows resource compilation failed ($LASTEXITCODE)" }
