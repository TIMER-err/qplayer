; Inno Setup script for the QPlayer Windows installer.
; Built in CI (see .github/workflows/release.yml) over the QPlayer/ folder that
; package-windows.ps1 produces (the GraalVM native qplayer.exe + licenses\).
; No JVM or Java jars are installed. Overridable defines:
;   /DAppVersion=x.y.z   version shown in Add/Remove Programs
;   /DSourceFolder=...   the packaged app folder
;   /DIconFile=...       the .ico for the setup + shortcuts
; Local test (with Inno Setup installed):
;   iscc /DSourceFolder=..\target\QPlayer desktop-host\dist\qplayer.iss

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
; Windows' binary version is numeric; retain the full RC version in display text.
#define VersionWithoutPrerelease Copy(AppVersion, 1, Pos("-", AppVersion + "-") - 1)
#define NumericAppVersion Copy(VersionWithoutPrerelease, 1, Pos("+", VersionWithoutPrerelease + "+") - 1)
#ifndef SourceFolder
  #define SourceFolder "..\target\QPlayer"
#endif
#ifndef IconFile
  ; The multi-size icon now lives with the app resources (the tray loads the
  ; same file at runtime), so there is one .ico in the repo, not two.
  #define IconFile "..\src\main\resources\app-icon.ico"
#endif

[Setup]
AppId={{8F3A1C2E-5B6D-4E7F-9A0B-1C2D3E4F5A6B}
AppName=QPlayer
AppVersion={#AppVersion}
VersionInfoVersion={#NumericAppVersion}
VersionInfoTextVersion={#AppVersion}
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
; AppUserModelID must match Main.java's configureWindowsAppIdentity() call
; (SetCurrentProcessExplicitAppUserModelID("dev.t1m3.qplayer")) exactly --
; without a Start Menu/desktop shortcut carrying the SAME id, Windows has no
; registered app to resolve a friendly name/icon for, and shows "未知应用"
; (Unknown app) in the SMTC media flyout even though the process correctly
; tagged its own identity and the track title/artist/thumbnail still publish
; fine (only the app-identity line is affected).
Name: "{group}\QPlayer"; Filename: "{app}\qplayer.exe"; IconFilename: "{app}\qplayer.ico"; AppUserModelID: "dev.t1m3.qplayer"
Name: "{autodesktop}\QPlayer"; Filename: "{app}\qplayer.exe"; IconFilename: "{app}\qplayer.ico"; Tasks: desktopicon; AppUserModelID: "dev.t1m3.qplayer"

[Run]
Filename: "{app}\qplayer.exe"; Description: "{cm:LaunchProgram,QPlayer}"; Flags: nowait postinstall skipifsilent
