param([int]$Port = 8080, [string]$Bind = '127.0.0.1')
$ErrorActionPreference = 'Stop'
$taskJava = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
if (Test-Path -LiteralPath (Join-Path $taskJava 'bin\java.exe')) { $env:JAVA_HOME = $taskJava }
$env:AKPDA_MODE = 'demo'
$env:AKPDA_PORT = "$Port"
$env:AKPDA_BIND = $Bind
& "$PSScriptRoot\gradlew.bat" -g "$PSScriptRoot\.tools\gradle-home" :server:run --console=plain
exit $LASTEXITCODE
