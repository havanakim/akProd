$ErrorActionPreference = 'Stop'
$taskJava = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
if (Test-Path -LiteralPath (Join-Path $taskJava 'bin\java.exe')) { $env:JAVA_HOME = $taskJava }
& "$PSScriptRoot\gradlew.bat" -g "$PSScriptRoot\.tools\gradle-home" :shared:test :server:test :server:installDist :app:assembleDebug :app:lintDebug --console=plain
exit $LASTEXITCODE
