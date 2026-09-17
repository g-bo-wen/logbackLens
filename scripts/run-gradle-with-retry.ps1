[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string[]] $Task = @('test'),

    [ValidateRange(1, 5)]
    [int] $MaxAttempts = 3,

    [string] $GradleJavaHome
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$gradleWrapper = Join-Path $projectRoot 'gradlew.bat'
$logRoot = Join-Path ([System.IO.Path]::GetTempPath()) 'logbackLens-gradle-logs'
$null = New-Item -ItemType Directory -Path $logRoot -Force

if ($GradleJavaHome) {
    $resolvedGradleJavaHome = (Resolve-Path -LiteralPath $GradleJavaHome).Path
    $javaExecutable = Join-Path $resolvedGradleJavaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
        throw "GradleJavaHome does not contain bin\java.exe: $resolvedGradleJavaHome"
    }
    $env:JAVA_HOME = $resolvedGradleJavaHome
}

$networkFailurePattern = '(?im)Could not (?:GET|HEAD)|UnknownHostException|ConnectException|SocketTimeoutException|SSLHandshakeException|PKIX path building failed|Connection (?:reset|refused)|50[234] (?:Bad Gateway|Service Unavailable|Gateway Timeout)'

for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $logPath = Join-Path $logRoot "gradle-$stamp-attempt-$attempt.log"
    # IntelliJ Platform Gradle Plugin 2.18.1 can run instrumentCode and
    # instrumentTestCode concurrently against one AntBuilder. Serial workers avoid
    # the known AntXMLContext corruption while keeping instrumentation enabled.
    $arguments = @('--console=plain', '--stacktrace', '--max-workers=1') + $Task

    & $gradleWrapper @arguments *> $logPath
    $exitCode = $LASTEXITCODE
    if ($exitCode -eq 0) {
        Write-Output "PASS | gradlew $($Task -join ' ') | attempt $attempt/$MaxAttempts | raw-log $logPath"
        exit 0
    }

    $logText = Get-Content -LiteralPath $logPath -Raw
    $isNetworkFailure = $logText -match $networkFailurePattern
    if (-not $isNetworkFailure -or $attempt -eq $MaxAttempts) {
        $cause = Get-Content -LiteralPath $logPath | Select-String -Pattern '^\* What went wrong:','^e: ','^Caused by:' | Select-Object -First 3
        Write-Output "FAIL | gradlew $($Task -join ' ') | attempt $attempt/$MaxAttempts | network=$isNetworkFailure | raw-log $logPath"
        $cause | ForEach-Object { Write-Output $_.Line }
        exit $exitCode
    }

    Write-Output "RETRY | gradlew $($Task -join ' ') | network-like failure | attempt $attempt/$MaxAttempts | raw-log $logPath"
}
