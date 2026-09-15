# Builds the minimal PDAL C ABI shim (pdal_ffi.dll) on Windows with MSVC.
#
# Usage (PowerShell):
#   tools/ffi/build-ffi.ps1 -Classifier windows-x86_64 [-PdalRoot <dir>] [-StageDir <dir>]
#
# -PdalRoot  Directory containing Library\include + Library\lib of the pinned
#            conda package (usually the extracted libpdal-core package root
#            or $env:CONDA_PREFIX).
# -StageDir  Native bundle directory the shim is copied into. Defaults to
#            pdal-ffm-natives\src\main\resources\META-INF\pdal-native\<classifier>.
#
# The MSVC environment is discovered through vswhere and initialized with
# vcvars64.bat, so the script also works outside a Developer Command Prompt.
param(
    [Parameter(Mandatory = $true)][string]$Classifier,
    [string]$PdalRoot = $env:CONDA_PREFIX,
    [string]$StageDir = ""
)

$ErrorActionPreference = "Stop"

$RootDir = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$SourceFile = Join-Path $RootDir "native\pdal-ffi\pdal_ffi.cpp"
if ([string]::IsNullOrEmpty($StageDir)) {
    $StageDir = Join-Path $RootDir "pdal-ffm-natives\src\main\resources\META-INF\pdal-native\$Classifier"
}

if ($Classifier -ne "windows-x86_64") {
    Write-Error "Unsupported classifier for this script: $Classifier"
    exit 1
}
if ([string]::IsNullOrEmpty($PdalRoot)) {
    Write-Error "No -PdalRoot given and CONDA_PREFIX is unset. Activate the 'pdal' conda environment."
    exit 1
}

$PayloadRoot = $PdalRoot
if (-not (Test-Path (Join-Path $PayloadRoot "include\pdal\pdal_export.hpp"))) {
    $candidate = Join-Path $PdalRoot "Library"
    if (Test-Path (Join-Path $candidate "include\pdal\pdal_export.hpp")) {
        $PayloadRoot = $candidate
    } else {
        Write-Error "Could not locate PDAL headers below: $PdalRoot"
        exit 1
    }
}

$IncludeDir = Join-Path $PayloadRoot "include"
$LibDir = Join-Path $PayloadRoot "lib"
$BinDir = Join-Path $StageDir "bin"
New-Item -ItemType Directory -Force -Path $BinDir | Out-Null

$Vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
if (-not (Test-Path $Vswhere)) {
    Write-Error "vswhere.exe not found. A Visual Studio installation with the C++ workload is required."
    exit 1
}
$VsPath = & $Vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if ([string]::IsNullOrEmpty($VsPath)) {
    Write-Error "No Visual Studio installation with C++ tools found."
    exit 1
}
$VcVars = Join-Path $VsPath "VC\Auxiliary\Build\vcvars64.bat"
if (-not (Test-Path $VcVars)) {
    Write-Error "vcvars64.bat not found: $VcVars"
    exit 1
}

$OutDll = Join-Path $BinDir "pdal_ffi.dll"
$Implib = Join-Path $env:TEMP "pdal_ffi_$PID.lib"
Write-Host "Building $OutDll with MSVC from $VsPath"

$Command = @"
call "$VcVars" >nul
cl /nologo /std:c++17 /O2 /EHsc /MD /LD /D PDAL_FFI_BUILD ^
  /I "$IncludeDir" /I "$(Split-Path $SourceFile -Parent)" ^
  "$SourceFile" ^
  /link /LIBPATH:"$LibDir" pdalcpp.lib /OUT:"$OutDll" /IMPLIB:"$Implib"
"@

& cmd.exe /d /s /c $Command
if ($LASTEXITCODE -ne 0) {
    Write-Error "MSVC build failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

Remove-Item -Force $Implib -ErrorAction SilentlyContinue
Write-Host "FFI shim built: $OutDll"
