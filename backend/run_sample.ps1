$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
chcp 65001 > $null

$workspaceRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = Join-Path $workspaceRoot "AI\.jdk17"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
$env:CONSOLE_LOG_CHARSET = "UTF-8"

Set-Location $PSScriptRoot

mvn -pl uavfire `
  "-Dproject.build.sourceEncoding=UTF-8" `
  "-Dmaven.compiler.encoding=UTF-8" `
  spring-boot:run `
  "-Dspring-boot.run.arguments=--mqtt.BASIC.host=192.168.50.10 --mqtt.BASIC.port=1883 --mqtt.DRC.host=192.168.50.10 --mqtt.DRC.port=8083"
