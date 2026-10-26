param([string]$JdkHome, [switch]$AllowOtherJdk)
. (Join-Path $PSScriptRoot 'common.ps1')
$jdk = Resolve-LabJdk -JdkHome $JdkHome -AllowOtherJdk:$AllowOtherJdk
$classes = Build-Lab -Jdk $jdk
Write-Host "Compiled Java 21 bytecode: $classes"
