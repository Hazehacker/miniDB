$ErrorActionPreference = 'Continue'
$root = 'C:\Users\night\Desktop\ex_projects\miniDB'

# 1. Kill ZingDB java processes touching port 9999 (server + any clients).
$pids = @()
$pids += Get-NetTCPConnection -LocalPort 9999 -ErrorAction SilentlyContinue | ForEach-Object { $_.OwningProcess }
$pids += Get-NetTCPConnection -ErrorAction SilentlyContinue |
    Where-Object { $_.RemotePort -eq 9999 } | ForEach-Object { $_.OwningProcess }
$pids = $pids | Where-Object { $_ -gt 0 } | Select-Object -Unique
foreach ($p in $pids) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }

# 2. Close the cmd console windows that ran the launcher scripts.
Get-CimInstance Win32_Process -Filter "Name = 'cmd.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'start-(server|client)\.cmd' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

Start-Sleep -Milliseconds 1200

# 3. Reopen both ends with the UTF-8 (chcp 65001) launchers.
Start-Process -FilePath 'cmd.exe' -ArgumentList '/k','data\start-server.cmd' -WorkingDirectory $root
Start-Sleep -Milliseconds 500
Start-Process -FilePath 'cmd.exe' -ArgumentList '/k','data\start-client.cmd' -WorkingDirectory $root
