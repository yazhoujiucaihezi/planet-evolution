@echo off
rem ============================================================
rem  星球演化模拟器 - 停止后端（由 start.bat 启动的服务）
rem  本文件编码为 GBK，修改时请保持该编码。
rem ============================================================
setlocal EnableExtensions
chcp 936 >nul

set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"
if not defined PORT set "PORT=8080"
set "PID_FILE=%PROJECT_DIR%\logs\planet-evolution.pid"
echo 正在停止「星球演化」后端...

if not exist "%PID_FILE%" (
  echo [提示] 未找到 PID 文件（后端可能不是由 start.bat 启动的）。
  goto :byport
)

set "KILLED="
for /f "usebackq delims=" %%p in ("%PID_FILE%") do (
  taskkill /PID %%p /T /F >nul 2>&1
  if not errorlevel 1 (
    echo       已结束进程 PID=%%p
    set "KILLED=1"
  )
)
if not defined KILLED echo [提示] PID 文件中的进程均已不存在。
del /q "%PID_FILE%" >nul 2>&1

:byport
netstat -ano | findstr /R /C:":%PORT% .*LISTENING" >nul 2>&1
if not errorlevel 1 (
  echo [警告] 端口 %PORT% 仍在监听，可能仍有残留进程，请手动检查：
  netstat -ano ^| findstr /R /C:":%PORT% .*LISTENING"
) else (
  echo       端口 %PORT% 已释放，服务已停止。
)

echo.
pause
endlocal
exit /b 0