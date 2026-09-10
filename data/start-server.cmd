@echo off
rem miniDB server launcher (JDK 18+ recommended).
chcp 65001 >nul
setlocal
for %%I in ("%~dp0..") do set "ROOT=%%~fI"
cd /d "%ROOT%"
set "CP="
for /f "usebackq delims=" %%L in ("%~dp0cp.txt") do set "CP=%%L"
echo [miniDB] opening database at data/db ...
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "%ROOT%\target\classes;%CP%" top.zhongnan.minidb.engine.Launcher -open data/db
echo.
echo [miniDB] server exited.
pause
