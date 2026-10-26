Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$LabRoot = Split-Path -Parent $PSScriptRoot

function Resolve-LabJdk {
    param([string]$JdkHome, [switch]$AllowOtherJdk)
    if (-not $JdkHome) { $JdkHome = $env:JAVA_HOME }
    if (-not $JdkHome) {
        $javaCommand = Get-Command java -ErrorAction SilentlyContinue
        if ($javaCommand) { $JdkHome = Split-Path -Parent (Split-Path -Parent $javaCommand.Source) }
    }
    if (-not $JdkHome) { throw 'No JDK found. Supply -JdkHome pointing to a full JDK 21 installation.' }
    $resolved = (Resolve-Path -LiteralPath $JdkHome).Path
    foreach ($tool in @('java', 'javac', 'jcmd', 'jfr')) {
        if (-not (Test-Path -LiteralPath (Join-Path $resolved "bin/$tool.exe"))) {
            throw "Missing bin/$tool.exe in '$resolved'. Supply the actual JDK directory, not javapath or a JRE."
        }
    }
    $versionText = (& (Join-Path $resolved 'bin/java.exe') --version | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $versionText -notmatch '(?m)^(?:openjdk|java)\s+(\d+)') {
        throw "Cannot determine JDK version: $versionText"
    }
    $major = [int]$Matches[1]
    if ($major -lt 21) { throw "JDK $major is too old; JDK 21 is required." }
    if ($major -ne 21) {
        if (-not $AllowOtherJdk) {
            throw "Found JDK $major, but this lab targets JDK 21. Use -JdkHome <JDK21 directory>, or explicitly pass -AllowOtherJdk for compatibility practice."
        }
        Write-Warning "Compatibility run on JDK $major. Results are NOT JDK 21 measurements; do not compare different JDK versions."
    }
    [pscustomobject]@{
        Home = $resolved
        Java = Join-Path $resolved 'bin/java.exe'
        Javac = Join-Path $resolved 'bin/javac.exe'
        Jcmd = Join-Path $resolved 'bin/jcmd.exe'
        Jfr = Join-Path $resolved 'bin/jfr.exe'
        Version = $versionText
        Major = $major
    }
}

function Build-Lab {
    param($Jdk)
    $classes = Join-Path $LabRoot 'build/classes'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $LabRoot 'src/main/java') -Recurse -Filter '*.java' |
        ForEach-Object { $_.FullName })
    # Explicit classpath avoids stale CLASSPATH entries such as removed tools.jar.
    & $Jdk.Javac --release 21 -encoding UTF-8 -Xlint:all -classpath $classes -d $classes @sources
    if ($LASTEXITCODE -ne 0) { throw 'javac failed.' }
    return $classes
}
