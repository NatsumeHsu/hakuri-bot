$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    $taskJdk = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '.tools') -Directory -Filter 'jdk-25*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($taskJdk) { $env:JAVA_HOME = $taskJdk.FullName }
    else { throw '請安裝 JDK 25 並設定 JAVA_HOME。' }
}
& (Join-Path $PSScriptRoot 'mvnw.cmd') 'spring-boot:run'
exit $LASTEXITCODE
