param(
    [ValidateSet('allocation','leak','cpu','lock','report')][string]$Scenario = 'allocation',
    [ValidateSet('bad','fixed','cpu-fixed')][string]$Mode = 'bad',
    [ValidateRange(1,300)][int]$Seconds = 30,
    [ValidateRange(-1,60)][int]$WarmupSeconds = -1,
    [ValidateRange(1,32)][int]$Threads = 4,
    [ValidateRange(64,2048)][int]$HeapMiB = 128,
    [ValidateRange(1,256)][int]$RetainedLimitMiB = 64,
    [ValidateRange(1,256)][int]$CacheLimitMiB = 8,
    [ValidateSet('G1','GenerationalZGC','Parallel')][string]$Collector = 'G1',
    [ValidateRange(0,1000)][int]$MaxGCPauseMillis = 0,
    [switch]$Recording,
    [string]$JfrSettings,
    [string]$JfrRepositoryRoot,
    [string]$JdkHome,
    [switch]$AllowOtherJdk
)
. (Join-Path $PSScriptRoot 'common.ps1')
if ($Mode -eq 'cpu-fixed' -and $Scenario -ne 'report') { throw '-Mode cpu-fixed requires -Scenario report.' }
$jdk = Resolve-LabJdk -JdkHome $JdkHome -AllowOtherJdk:$AllowOtherJdk
if ($WarmupSeconds -eq -1) { $WarmupSeconds = if ($Scenario -eq 'leak') { 0 } else { 5 } }
if ($Scenario -eq 'leak') {
    $payloadLimit = if ($Mode -eq 'bad') { $RetainedLimitMiB } else { $CacheLimitMiB }
    if ($payloadLimit + 32 -gt $HeapMiB) {
        throw 'Leave at least 32 MiB of heap above the retained payload limit. Increase -HeapMiB or lower the cache/retention limit.'
    }
}
if ($Collector -ne 'G1' -and $MaxGCPauseMillis -ne 0) {
    throw '-MaxGCPauseMillis in this lab is only offered for G1.'
}
if (($JfrSettings -or $JfrRepositoryRoot) -and -not $Recording) { throw '-JfrSettings / -JfrRepositoryRoot require -Recording.' }
$classes = Build-Lab -Jdk $jdk
$runId = '{0}-{1}-{2}-{3}' -f (Get-Date -Format 'yyyyMMdd-HHmmss-fff'), $Scenario, $Mode,
    ([guid]::NewGuid().ToString('N').Substring(0,6))
$runDir = Join-Path $LabRoot "artifacts/$runId"
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$jvmArgs = @("-Xms${HeapMiB}m", "-Xmx${HeapMiB}m")
switch ($Collector) {
    'G1' { $jvmArgs += '-XX:+UseG1GC' }
    'GenerationalZGC' { $jvmArgs += @('-XX:+UseZGC', '-XX:+ZGenerational') }
    'Parallel' { $jvmArgs += '-XX:+UseParallelGC' }
}
if ($MaxGCPauseMillis -gt 0) { $jvmArgs += "-XX:MaxGCPauseMillis=$MaxGCPauseMillis" }
$jvmArgs += '-Xlog:gc*,safepoint:file=gc.log:time,uptime,level,tags:filecount=3,filesize=10M'
if ($Recording) {
    # This machine's JDK 23 needs an ASCII JFR repository to avoid empty recordings.
    # The final recording stays in runDir. A single native argument supports spaces.
    $repositoryRoot = if ($JfrRepositoryRoot) { [System.IO.Path]::GetFullPath($JfrRepositoryRoot) }
        else { Join-Path ([System.IO.Path]::GetTempPath()) 'jvm-tuning-jfr' }
    if ($repositoryRoot -match '[^\x00-\x7F]|,') {
        throw 'Use -JfrRepositoryRoot with a writable ASCII path without commas for JFR working data.'
    }
    $repository = Join-Path $repositoryRoot $runId
    New-Item -ItemType Directory -Path $repository -Force | Out-Null
    $jvmArgs += "-XX:FlightRecorderOptions=repository=$repository"
    $settings = 'profile'
    if ($JfrSettings) {
        Copy-Item -LiteralPath $JfrSettings -Destination (Join-Path $runDir 'settings.jfc')
        $settings = 'settings.jfc'
    }
    $jvmArgs += "-XX:StartFlightRecording=name=lab,settings=$settings,filename=recording.jfr,dumponexit=true,maxsize=64m"
}
$appArgs = @('--scenario', $Scenario, '--mode', $Mode, '--seconds', "$Seconds",
    '--warmup', "$WarmupSeconds", '--threads', "$Threads", '--retained-limit-mb', "$RetainedLimitMiB",
    '--cache-limit-mb', "$CacheLimitMiB", '--output-dir', '.')
$manifest = [ordered]@{
    created_at = (Get-Date).ToString('o')
    java_home = $jdk.Home
    java_version = $jdk.Version
    collector = $Collector
    jvm_args = $jvmArgs
    app_args = $appArgs
    working_directory = $runDir
    load_model = 'fixed-concurrency closed-loop'
}
if ($Scenario -eq 'report') {
    Copy-Item -LiteralPath (Join-Path $LabRoot 'src/main/java') -Destination (Join-Path $runDir 'source') -Recurse
    $manifest['source_snapshot'] = 'source'
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runDir 'run.json') -Encoding UTF8
Write-Host "Artifacts: $runDir"
Push-Location -LiteralPath $runDir
try {
    # Relative output paths avoid drive-letter conflicts in JVM option syntax.
    & $jdk.Java @jvmArgs -cp $classes lab.JvmTuningLab @appArgs |
        Tee-Object -FilePath (Join-Path $runDir 'console.log')
    if ($LASTEXITCODE -ne 0) { throw "Java exited with code $LASTEXITCODE. Artifacts: $runDir" }
} finally {
    Pop-Location
}
$summaryPath = Join-Path $runDir 'summary.csv'
foreach ($resultName in @('summary.csv', 'metrics.csv', 'gc.log')) {
    $resultPath = Join-Path $runDir $resultName
    if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf) -or
        (Get-Item -LiteralPath $resultPath).Length -eq 0) {
        throw "Experiment did not produce a non-empty $resultName. Check console.log and run.json. Artifacts: $runDir"
    }
}
$summaryRows = @(Import-Csv -LiteralPath $summaryPath)
if ($summaryRows.Count -ne 1 -or $summaryRows[0].scenario -ne $Scenario -or
    $summaryRows[0].mode -ne $Mode -or [long]$summaryRows[0].operations -le 0) {
    throw "Invalid experiment summary. Artifacts: $runDir"
}
if ($Recording) {
    $recordingPath = Join-Path $runDir 'recording.jfr'
    if (-not (Test-Path -LiteralPath $recordingPath) -or (Get-Item -LiteralPath $recordingPath).Length -eq 0) {
        throw "JFR is missing/empty. Try a writable ASCII -JfrRepositoryRoot. Artifacts: $runDir"
    }
    & $jdk.Jfr summary $recordingPath | Set-Content -LiteralPath (Join-Path $runDir 'jfr-summary.txt') -Encoding UTF8
    if ($LASTEXITCODE -ne 0) { throw "JFR cannot be parsed. Artifacts: $runDir" }
}
Write-Host "Finished. Summary: $summaryPath"
