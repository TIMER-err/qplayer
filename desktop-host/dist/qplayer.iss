; Inno Setup script for the QPlayer Windows installer.
; Built in CI (see .github/workflows/release.yml) over the QPlayer/ folder that
; package-windows.ps1 produces. Overridable defines:
;   /DAppVersion=x.y.z   version shown in Add/Remove Programs
;   /DSourceFolder=...   the packaged app folder (exe + DLLs)
;   /DIconFile=...       the .ico for the setup + shortcuts
; Local test (with Inno Setup installed):
;   iscc /DSourceFolder=..\target\QPlayer desktop-host\dist\qplayer.iss

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef SourceFolder
  #define SourceFolder "..\target\QPlayer"
#endif
#ifndef IconFile
  #define IconFile "app-icon.ico"
#endif

[Setup]
AppId={{8F3A1C2E-5B6D-4E7F-9A0B-1C2D3E4F5A6B}
AppName=QPlayer
AppVersion={#AppVersion}
AppPublisher=t1m3
DefaultDirName={autopf}\QPlayer
DefaultGroupName=QPlayer
DisableProgramGroupPage=yes
UninstallDisplayIcon={app}\qplayer.exe
OutputDir=.
OutputBaseFilename=QPlayer-windows-x64-setup
Compression=lzma2/max
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
SetupIconFile={#IconFile}
WizardStyle=modern

[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#SourceFolder}\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion
Source: "{#IconFile}"; DestDir: "{app}"; DestName: "qplayer.ico"; Flags: ignoreversion

[Icons]
Name: "{group}\QPlayer"; Filename: "{app}\qplayer.exe"; IconFilename: "{app}\qplayer.ico"
Name: "{autodesktop}\QPlayer"; Filename: "{app}\qplayer.exe"; IconFilename: "{app}\qplayer.ico"; Tasks: desktopicon

[Run]
Filename: "{app}\qplayer.exe"; Description: "{cm:LaunchProgram,QPlayer}"; Flags: nowait postinstall skipifsilent
