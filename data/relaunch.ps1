$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

# 只重启当前项目的 miniDB 入口；不按端口结束其他服务。
$classesPattern = [regex]::Escape((Join-Path $projectRoot 'target\classes'))
Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
    Where-Object {
        $_.CommandLine -match $classesPattern -and
        $_.CommandLine -match 'top\.zhongnan\.minidb\.(engine|cli)\.Launcher(?:\s|$)'
    } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

Start-Process -FilePath 'cmd.exe' -ArgumentList '/k', 'data\start-server.cmd' -WorkingDirectory $projectRoot
Start-Sleep -Milliseconds 500
Start-Process -FilePath 'cmd.exe' -ArgumentList '/k', 'data\start-client.cmd' -WorkingDirectory $projectRoot
