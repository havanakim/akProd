param([string]$OutputPath)

$ErrorActionPreference='Stop'
$adbPath=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
if(!(Test-Path -LiteralPath $adbPath)) {
    $adbCommand=Get-Command adb -ErrorAction SilentlyContinue
    if(!$adbCommand) { throw 'Android SDK platform-tools의 adb를 찾을 수 없습니다.' }
    $adbPath=$adbCommand.Source
}
if(!$OutputPath) {
    $projectPath=Split-Path $PSScriptRoot -Parent
    $OutputPath=Join-Path $projectPath ('exports/akprod-insert-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.log')
}
$absolutePath=[IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Path (Split-Path $absolutePath -Parent) -Force | Out-Null
$lines=& $adbPath logcat -d -v time -s akProdDB
if($LASTEXITCODE -ne 0) { throw '단말 USB 연결 및 USB 디버깅 허용 상태를 확인하세요.' }
$lines | Set-Content -LiteralPath $absolutePath -Encoding utf8
Write-Output "저장 로그: $absolutePath"
if(!($lines -match 'INSERT|COMMIT|failure=')) {
    Write-Output '아직 INSERT 로그가 없습니다. v1.1.1 이상에서 저장을 누른 뒤 다시 실행하세요.'
}
