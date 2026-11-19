param([int]$Port = 8080, [string]$Bind = '127.0.0.1', [switch]$CheckDb, [string]$ServerJavaHome = $env:AKPDA_SERVER_JAVA_HOME)
$ErrorActionPreference = 'Stop'

# Reuse the current terminal's connection settings during the project rename.
# Credentials remain in process environment variables and are never printed or saved.
foreach ($key in @('JDBC_URL', 'DB_USER', 'DB_PASSWORD', 'WRITE_CONTRACTS', 'RECYCLE_CANCEL_PROCEDURE')) {
    $currentName = 'AKPDA_' + $key
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($currentName, 'Process'))) {
        $legacyValue = [Environment]::GetEnvironmentVariable('UNIPDA_' + $key, 'Process')
        if (-not [string]::IsNullOrWhiteSpace($legacyValue)) {
            [Environment]::SetEnvironmentVariable($currentName, $legacyValue, 'Process')
        }
    }
}
$missing = @('AKPDA_JDBC_URL', 'AKPDA_DB_USER') | Where-Object {
    [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_, 'Process'))
}
if ($missing) {
    throw ('Missing SQL settings in this terminal: ' + ($missing -join ', ') + '. Set these variables or use the terminal that started the previous SQL server.')
}
# Gradle 9 requires a newer build JVM; the SQL API uses the tested Java 17 runtime.
if ([string]::IsNullOrWhiteSpace($ServerJavaHome)) {
    $ServerJavaHome = Join-Path $env:USERPROFILE '.jdks\jbr-17.0.14'
}
$serverJava = Join-Path $ServerJavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $serverJava)) {
    throw 'SQL API Java runtime not found. Set AKPDA_SERVER_JAVA_HOME or pass -ServerJavaHome with the tested JDK home.'
}
$taskJava = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
if (Test-Path -LiteralPath (Join-Path $taskJava 'bin\java.exe')) { $env:JAVA_HOME = $taskJava }
& "$PSScriptRoot\gradlew.bat" -g "$PSScriptRoot\.tools\gradle-home" :server:installDist --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$env:AKPDA_MODE = 'sql'
$env:AKPDA_PORT = "$Port"
$env:AKPDA_BIND = $Bind
Write-Host ('SQL API runtime: ' + $serverJava)
$serverArgs = @('-cp', "$PSScriptRoot\server\build\install\server\lib\*", 'kr.co.akpda.server.MainKt')
if ($CheckDb) {
    Write-Host 'Checking SQL connection with SELECT 1. No API listener is started.'
    $serverArgs += '--check-db'
} else {
    Write-Host ('Starting AKPDA SQL API on ' + $Bind + ':' + $Port + '. Stop the previous API server first.')
}
& $serverJava @serverArgs
exit $LASTEXITCODE
