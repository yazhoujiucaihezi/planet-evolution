@echo off
rem ============================================================
rem  星球演化模拟器 - 一键启动（前端 + 后端）
rem  用法：双击本文件，或在命令行执行 start.bat
rem  可选环境变量：
rem    PORT=8080          服务端口
rem    SKIP_BUILD=1       跳过 Maven 构建，直接启动
rem    NO_BROWSER=1       不自动打开浏览器
rem    STARTUP_TIMEOUT=90 等待启动的秒数
rem  本文件编码为 GBK（中文 Windows 命令行默认代码页），修改时请保持该编码。
rem ============================================================
setlocal EnableExtensions
chcp 936 >nul

set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"
set "MVN=D:\JAVA\IDEA\IntelliJ IDEA 2026.1\plugins\maven\lib\maven3\bin\mvn.cmd"
set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"

if not defined PORT set "PORT=8080"
if not defined STARTUP_TIMEOUT set "STARTUP_TIMEOUT=90"

echo ============================================================
echo   星球演化模拟器 - 一键启动
echo   项目目录: %PROJECT_DIR%
echo   服务地址: http://127.0.0.1:%PORT%/
echo ============================================================
echo.

if not exist "%PROJECT_DIR%\run.ps1" (
  echo [错误] 找不到 run.ps1，请确认本文件位于项目根目录。
  goto :fail
)
if not exist "%PROJECT_DIR%\pom.xml" (
  echo [错误] 找不到 pom.xml，请确认本文件位于项目根目录。
  goto :fail
)

echo [1/4] 清理端口 %PORT% 上的旧进程...
set "CLEANED=0"
for /f "tokens=5" %%a in ('netstat -ano ^| findstr /R /C:":%PORT% .*LISTENING"') do (
  echo       结束 PID=%%a
  taskkill /F /PID %%a >nul 2>&1
  if not errorlevel 1 set "CLEANED=1"
)
if "%CLEANED%"=="0" echo       无占用进程。

if "%SKIP_BUILD%"=="1" (
  echo [2/4] 已指定 SKIP_BUILD=1，跳过构建。
) else (
  echo [2/4] 正在离线构建（mvn -o clean package），首次可能较慢...
  if not exist "%MVN%" (
    echo [错误] 找不到 IDEA 自带的 Maven：
    echo        %MVN%
    goto :fail
  )
  pushd "%PROJECT_DIR%"
  call "%MVN%" -o -B clean package
  popd
  if errorlevel 1 (
    echo.
    echo [错误] 构建失败，不启动服务。
    goto :fail
  )
  echo       构建成功。
)
echo.

echo [3/4] 正在启动后端并等待就绪...
if "%NO_BROWSER%"=="1" (
  "%PS%" -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_DIR%\start-backend.ps1" -ProjectDir "%PROJECT_DIR%" -Port %PORT% -TimeoutSec %STARTUP_TIMEOUT% -SkipBrowser
) else (
  "%PS%" -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_DIR%\start-backend.ps1" -ProjectDir "%PROJECT_DIR%" -Port %PORT% -TimeoutSec %STARTUP_TIMEOUT%
)
if errorlevel 1 (
  echo.
  echo [错误] 后端未能启动就绪，详见上方输出与 target 目录下的日志。
  goto :fail
)
echo.

echo [4/4] 完成。
echo.
echo ============================================================
echo   页面地址: http://127.0.0.1:%PORT%/
echo   后端日志: %PROJECT_DIR%\target\planet-evolution.log
echo   停止服务: 运行 stop.bat
echo ============================================================
echo.
echo 本窗口可以关闭，后端会继续运行。
pause
endlocal
exit /b 0

:fail
echo.
echo 启动未完成。
pause
endlocal
exit /b 1