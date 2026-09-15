[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Jtl,

    [switch]$Json
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$jtlPath = (Resolve-Path -LiteralPath $Jtl -ErrorAction Stop).Path
$rows = @(Import-Csv -LiteralPath $jtlPath -Encoding UTF8)
if ($rows.Count -eq 0) {
    throw "JTL contains no samples: $Jtl"
}

function Get-Percentile {
    param(
        [double[]]$Values,
        [double]$Percent
    )

    if ($Values.Count -eq 0) {
        return 0
    }
    $index = [int][Math]::Ceiling($Values.Count * $Percent) - 1
    $index = [Math]::Max(0, [Math]::Min($index, $Values.Count - 1))
    return $Values[$index]
}

$elapsed = @($rows | ForEach-Object { [double]$_.elapsed } | Sort-Object)
$successCount = @($rows | Where-Object { $_.success -match '(?i)^true$' }).Count
$errorCount = $rows.Count - $successCount
$average = ($elapsed | Measure-Object -Average).Average
$sumOfSquares = 0.0
foreach ($value in $elapsed) {
    $sumOfSquares += [Math]::Pow(($value - $average), 2)
}
$stddev = [Math]::Sqrt($sumOfSquares / $elapsed.Count)

$timestampValues = @(
    $rows |
        Where-Object { $_.timeStamp -match '^\d+$' } |
        ForEach-Object { [int64]$_.timeStamp } |
        Sort-Object
)
if ($timestampValues.Count -ge 2) {
    $spanMs = [Math]::Max(1, $timestampValues[$timestampValues.Count - 1] - $timestampValues[0] + 1)
    $throughput = $rows.Count * 1000.0 / $spanMs
} else {
    $throughput = 0
}

$metrics = [PSCustomObject]@{
    samples = $rows.Count
    success = $successCount
    errors = $errorCount
    errorRatePercent = [Math]::Round(($errorCount * 100.0 / $rows.Count), 3)
    averageMs = [Math]::Round($average, 2)
    minMs = [Math]::Round($elapsed[0], 2)
    p50Ms = [Math]::Round((Get-Percentile $elapsed 0.50), 2)
    p90Ms = [Math]::Round((Get-Percentile $elapsed 0.90), 2)
    p95Ms = [Math]::Round((Get-Percentile $elapsed 0.95), 2)
    p99Ms = [Math]::Round((Get-Percentile $elapsed 0.99), 2)
    p999Ms = [Math]::Round((Get-Percentile $elapsed 0.999), 2)
    maxMs = [Math]::Round($elapsed[$elapsed.Count - 1], 2)
    stddevMs = [Math]::Round($stddev, 2)
    throughput = [Math]::Round($throughput, 2)
}

if ($Json) {
    $metrics | ConvertTo-Json -Compress
} else {
    $metrics | Format-List | Out-String | Write-Output
}

