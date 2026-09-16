#define MyAppName "Cover Art Video Maker"
#define MyAppVersion "1.0.0"
#define MyAppExeName "CoverArtVideoMaker.exe"
[Setup]
AppId={{86A4D5B8-34B8-4CF6-9F37-5F26493AE2AB}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
DefaultDirName={autopf}\Cover Art Video Maker
DefaultGroupName={#MyAppName}
OutputDir=..\installer-output
OutputBaseFilename=Cover-Art-Video-Maker-Setup
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
[Files]
Source: "..\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName}"; Flags: nowait postinstall skipifsilent
