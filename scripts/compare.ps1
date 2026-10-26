param(
    [Parameter(Mandatory=$true)][string]$Before,
    [Parameter(Mandatory=$true)][string]$After
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$beforeRows = @(Import-Csv -LiteralPath $Before)
$afterRows = @(Import-Csv -LiteralPath $After)
if ($beforeRows.Count -ne 1 -or $afterRows.Count -ne 1) { throw 'Each summary.csv must contain exactly one result row.' }
$b = $beforeRows[0]
$a = $afterRows[0]
foreach ($field in @('scenario','java_version','threads','warmup_s','requested_s','retained_limit_mib','cache_limit_mib')) {
    if ($b.$field -ne $a.$field) {
        throw "Incomparable configuration: $field differs ($($b.$field) vs $($a.$field)). Keep load and runtime identical."
    }
}
Write-Host "Scenario: $($b.scenario); mode $($b.mode) -> $($a.mode); heap $($b.max_heap_mib) -> $($a.max_heap_mib) MiB"
Write-Host 'Positive change means increase, not necessarily improvement. -1 means unsupported; check run.json for JVM flags.'
$culture = [System.Globalization.CultureInfo]::InvariantCulture
$fields = @('ops_per_s','p95_us','p99_us','worker_alloc_mib_s','worker_alloc_bytes_per_op',
    'gc_count','gc_time_ms','process_cpu_cores','retained_payload_mib')
$rows = foreach ($field in $fields) {
    $bv = [double]::Parse($b.$field, $culture)
    $av = [double]::Parse($a.$field, $culture)
    $change = if ($bv -lt 0 -or $av -lt 0) { 'unsupported' }
        elseif ($bv -eq 0) { 'n/a (zero baseline)' }
        else { '{0:+0.0;-0.0;0.0}%' -f (($av - $bv) / $bv * 100) }
    [pscustomobject]@{ Metric = $field; Before = $bv; After = $av; Change = $change }
}
$rows | Format-Table -AutoSize
