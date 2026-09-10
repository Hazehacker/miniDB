@echo off
rem ZingDB server launcher (JDK 18+ recommended).
chcp 65001 >nul
setlocal
set ROOT=%~dp0..
cd /d "%ROOT%"
set "CP="
for /f "usebackq delims=" %%L in ("%~dp0cp.txt") do set "CP=%%L"
echo [ZingDB] opening database at data/db ...
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "target\classes;%CP%" top.tankenqi.zingdb.engine.Launcher -open data/db
echo.
echo [ZingDB] server exited.
pause
