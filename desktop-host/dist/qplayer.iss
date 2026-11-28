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
#ifdef PerUser
AppId={{F642344E-5942-428F-9EA5-1EAA0BB9B832}
#else
AppId={{8F3A1C2E-5B6D-4E7F-9A0B-1C2D3E4F5A6B}
#endif
AppName=QPlayer
AppVersion={#AppVersion}
VersionInfoVersion={#NumericAppVersion}
VersionInfoTextVersion={#AppVersion}
AppPublisher=t1m3
#ifdef PerUser
PrivilegesRequired=lowest
UninstallDisplayName=QPlayer (current user)
DefaultDirName={localappdata}\Programs\QPlayer
; Never switch an existing machine-wide installation to a different scope.
UsePreviousAppDir=no
DisableDirPage=yes
DisableStartupPrompt=yes
CloseApplications=no
RestartApplications=no
#else
DefaultDirName={autopf}\QPlayer
#endif
DefaultGroupName=QPlayer
DisableProgramGroupPage=yes
UninstallDisplayIcon={app}\qplayer.exe
OutputDir=.
#ifdef PerUser
#ifdef PortableUpdate
Uninstallable=no
OutputBaseFilename=QPlayer-windows-x64-portable-update
#else
OutputBaseFilename=QPlayer-windows-x64-user-setup
#endif
#else
OutputBaseFilename=QPlayer-windows-x64-setup
#endif
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
#ifdef PortableUpdate
Source: "{#SourceFolder}\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion
#else
Source: "{#SourceFolder}\*"; Excludes: "qplayer-portable.marker"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion
#endif
#ifndef PortableUpdate
Source: "{#IconFile}"; DestDir: "{app}"; DestName: "qplayer.ico"; Flags: ignoreversion

#ifdef PerUser
[UninstallDelete]
Type: files; Name: "{app}\.qplayer-user-install"

[Code]
function IsNormalUserInstall: Boolean;
begin
  Result := ExpandConstant('{param:QPLAYERMIGRATION|0}') <> '1';
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    if not SaveStringToFile(ExpandConstant('{app}\.qplayer-user-install'), 'QPlayer', False) then
      Log('Could not write the QPlayer user installation marker.');
end;
#endif

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
#ifdef PerUser
Filename: "{app}\qplayer.exe"; Description: "{cm:LaunchProgram,QPlayer}"; Flags: nowait postinstall skipifsilent; Check: IsNormalUserInstall
#else
Filename: "{app}\qplayer.exe"; Description: "{cm:LaunchProgram,QPlayer}"; Flags: nowait postinstall skipifsilent
#endif

#endif

#ifdef PortableUpdate
[Code]
function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  if not FileExists(ExpandConstant('{app}\qplayer-portable.marker')) or
     not FileExists(ExpandConstant('{app}\qplayer.exe')) then
    Result := 'The destination is not an existing QPlayer portable installation.'
  else
    Result := '';
end;
#endif
