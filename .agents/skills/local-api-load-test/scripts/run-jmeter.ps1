[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Jmx,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ResultFile,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$LogFile,

    [hashtable]$Properties = @{},

    [string]$JMeter = 'D:\software\develop software\apache-jmeter-5.6.3\bin\jmeter.bat'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$jmxPath = (Resolve-Path -LiteralPath $Jmx -ErrorAction Stop).Path
if ([IO.Path]::GetExtension($jmxPath) -ne '.jmx') {
    throw 'Jmx must point to a .jmx file'
}
if (-not (Test-Path -LiteralPath $JMeter -PathType Leaf)) {
    throw 'JMeter executable not found'
}

foreach ($outputPath in @($ResultFile, $LogFile)) {
    $parent = Split-Path -Parent $outputPath
    if ([string]::IsNullOrWhiteSpace($parent)) {
        $parent = (Get-Location).Path
    }
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
        throw "Output directory does not exist: $parent"
    }
}

$arguments = [System.Collections.Generic.List[string]]::new()
[void]$arguments.Add('-n')
[void]$arguments.Add('-t')
[void]$arguments.Add($jmxPath)

foreach ($entry in ($Properties.GetEnumerator() | Sort-Object Name)) {
    $key = [string]$entry.Key
    $value = [string]$entry.Value
    if ($key -notmatch '^[A-Za-z0-9_.-]+$') {
        throw "Invalid JMeter property name: $key"
    }
    if ($value -match '[\r\n]') {
        throw "JMeter property contains a newline: $key"
    }
    [void]$arguments.Add("-J$key=$value")
}

[void]$arguments.Add('-Jjmeter.save.saveservice.output_format=csv')
[void]$arguments.Add('-Jjmeter.save.saveservice.print_field_names=true')
[void]$arguments.Add('-Jjmeter.save.saveservice.response_data=false')
[void]$arguments.Add('-Jjmeter.save.saveservice.response_data.on_error=false')
[void]$arguments.Add('-l')
$resolvedResult = Resolve-Path -LiteralPath $ResultFile -ErrorAction SilentlyContinue
if ($null -eq $resolvedResult) {
    $resultArgument = $ResultFile
} else {
    $resultArgument = $resolvedResult.Path
}
[void]$arguments.Add($resultArgument)
[void]$arguments.Add('-j')
$resolvedLog = Resolve-Path -LiteralPath $LogFile -ErrorAction SilentlyContinue
if ($null -eq $resolvedLog) {
    $logArgument = $LogFile
} else {
    $logArgument = $resolvedLog.Path
}
[void]$arguments.Add($logArgument)

$previousPath = $env:Path
$javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME')
if (-not [string]::IsNullOrWhiteSpace($javaHome)) {
    $candidateJavaHomeBin = Join-Path $javaHome 'bin'
    if (Test-Path -LiteralPath (Join-Path $candidateJavaHomeBin 'java.exe') -PathType Leaf) {
        $env:Path = $candidateJavaHomeBin + ';' + $previousPath
    }
}

try {
    $output = @(& $JMeter @arguments 2>&1)
    $exitCode = $LASTEXITCODE
} finally {
    $env:Path = $previousPath
}

$output | ForEach-Object { Write-Output $_ }

if ($exitCode -ne 0) {
    throw "JMeter exited with code $exitCode"
}
if (-not (Test-Path -LiteralPath $ResultFile -PathType Leaf)) {
    throw "JTL was not created: $ResultFile"
}
if (-not (Test-Path -LiteralPath $LogFile -PathType Leaf)) {
    throw "JMeter log was not created: $LogFile"
}

Write-Output "JMeter completed: $ResultFile"
