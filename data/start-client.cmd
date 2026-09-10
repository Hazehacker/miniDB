@echo off
rem ZingDB client launcher, connects to 127.0.0.1:9999 (JDK 18+ recommended).
chcp 65001 >nul
setlocal
set ROOT=%~dp0..
cd /d "%ROOT%"
set "CP="
for /f "usebackq delims=" %%L in ("%~dp0cp.txt") do set "CP=%%L"
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "target\classes;%CP%" top.tankenqi.zingdb.cli.Launcher
