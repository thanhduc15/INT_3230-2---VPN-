[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$BashPath = (Join-Path $env:ProgramFiles 'Git/bin/bash.exe')
)

$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $BashPath -PathType Leaf)) {
    throw 'Git Bash was not found. Install Git for Windows or pass -BashPath.'
}

if ([string]::IsNullOrWhiteSpace($JdkHome)) {
    $java = Get-Command java.exe -ErrorAction Stop
    # Java prints settings on stderr, which Windows PowerShell treats as error records.
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $settings = & $java.Source -XshowSettings:properties -version 2>&1 | Out-String
        $javaStatus = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedPreference
    }
    if ($javaStatus -ne 0) {
        throw 'Unable to inspect Java. Pass -JdkHome with a JDK 17 or newer directory.'
    }
    $JdkHome = [regex]::Match($settings, '(?m)^\s*java\.home = (.+?)\s*$').Groups[1].Value
}

if ([string]::IsNullOrWhiteSpace($JdkHome)) {
    throw 'Unable to locate a JDK. Pass -JdkHome with a JDK 17 or newer directory.'
}
$jdkBin = Join-Path $JdkHome 'bin'
foreach ($tool in @('java.exe', 'javac.exe', 'keytool.exe')) {
    if (!(Test-Path -LiteralPath (Join-Path $jdkBin $tool) -PathType Leaf)) {
        throw "Missing $tool in $jdkBin. Pass -JdkHome with a complete JDK."
    }
}

$originalPath = $env:Path
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $env:Path = $jdkBin + ';' + $originalPath
    Write-Host "Using JDK: $JdkHome"
    & $BashPath scripts/run-demo.sh
    $demoStatus = $LASTEXITCODE
} finally {
    $env:Path = $originalPath
    Pop-Location
}
exit $demoStatus
