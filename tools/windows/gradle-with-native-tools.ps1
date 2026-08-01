param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

$ErrorActionPreference = 'Stop'

$gitBin = 'C:\Program Files\Git\bin'
$cmakeBin = Join-Path $env:LOCALAPPDATA 'Android\Sdk\cmake\3.22.1\bin'

foreach ($required in @($gitBin, $cmakeBin)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Required Android native build tool path is missing: $required"
    }
}

$env:Path = "$gitBin;$cmakeBin;$env:Path"
& (Join-Path $PSScriptRoot '..\..\gradlew.bat') @GradleArgs
exit $LASTEXITCODE
