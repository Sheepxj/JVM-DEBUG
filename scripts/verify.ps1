param([string]$JdkHome, [switch]$AllowOtherJdk)
. (Join-Path $PSScriptRoot 'common.ps1')
$jdk = Resolve-LabJdk -JdkHome $JdkHome -AllowOtherJdk:$AllowOtherJdk
$classes = Build-Lab -Jdk $jdk
$testClasses = Join-Path $LabRoot 'build/test-classes'
New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
$testSource = Join-Path $LabRoot 'src/test/java/lab/LabChecks.java'
& $jdk.Javac --release 21 -encoding UTF-8 -Xlint:all -cp $classes -d $testClasses $testSource
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
& $jdk.Java -Xmx128m -cp "$classes;$testClasses" lab.LabChecks
if ($LASTEXITCODE -ne 0) { throw 'LabChecks failed.' }
$verifyRoot = Join-Path $LabRoot ('artifacts/verify-' + [guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $verifyRoot -Force | Out-Null
$culture = [System.Globalization.CultureInfo]::InvariantCulture
foreach ($scenario in @('allocation','leak','cpu','lock','report')) {
    $modes = if ($scenario -eq 'report') { @('bad','cpu-fixed','fixed') } else { @('bad','fixed') }
    foreach ($mode in $modes) {
        $target = Join-Path $verifyRoot "$scenario-$mode"
        & $jdk.Java -Xms128m -Xmx128m -XX:+UseG1GC -cp $classes lab.JvmTuningLab `
            --scenario $scenario --mode $mode --seconds 1 --warmup 0 --threads 2 --output-dir $target
        if ($LASTEXITCODE -ne 0) { throw "Smoke test failed: $scenario/$mode" }
        $rows = @(Import-Csv -LiteralPath (Join-Path $target 'summary.csv'))
        if ($rows.Count -ne 1 -or [long]$rows[0].operations -le 0) { throw 'Invalid summary/zero work.' }
        $elapsed = [double]::Parse($rows[0].elapsed_s, $culture)
        if ($elapsed -lt 1 -or $elapsed -gt 10) { throw 'Unexpected run duration.' }
        Write-Host "PASS smoke: $scenario/$mode"
    }
}
Write-Host "All checks passed. Smoke artifacts: $verifyRoot"
