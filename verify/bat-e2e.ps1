# 端到端验证 start.bat / stop.bat（自动化用，避开 pause 阻塞）
$ErrorActionPreference = 'Continue'
$dir = 'D:\vibe-coding\planet-evolution'
$port = 8099
$outLog = Join-Path $env:TEMP 'pe-bat-test.log'

$env:PORT = "$port"
$env:NO_BROWSER = '1'
$env:SKIP_BUILD = '1'
$env:STARTUP_TIMEOUT = '90'

Write-Host '=== 1) 运行 start.bat（SKIP_BUILD=1, NO_BROWSER=1, PORT=8099）==='
$cmd = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'start.bat' -WorkingDirectory $dir `
    -RedirectStandardOutput $outLog -RedirectStandardError (Join-Path $env:TEMP 'pe-bat-test.err.log') -PassThru

$ready = $false
foreach ($i in 1..60) {
    Start-Sleep -Seconds 1
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { $ready = $true; break }
}
Write-Host ("端口 $port 监听 = " + $ready)

Write-Host '=== 2) PID 文件内容 ==='
$pidFile = Join-Path $dir 'target\planet-evolution.pid'
if (Test-Path $pidFile) { Get-Content $pidFile | ForEach-Object { Write-Host ("  PID=" + $_) } } else { Write-Host '  未生成' }

Write-Host '=== 3) 接口验证 ==='
if ($ready) {
    $c = [System.Net.WebClient]::new()
    foreach ($path in @('/', '/api/world')) {
        try {
            $bytes = $c.DownloadData("http://127.0.0.1:$port$path")
            $text = [System.Text.Encoding]::UTF8.GetString($bytes)
            Write-Host ("  {0} -> 200, {1} 字节, {2}" -f $path, $bytes.Length, $text.Substring(0, [Math]::Min(150, $text.Length)))
        } catch { Write-Host ("  {0} -> 失败 {1}" -f $path, $_.Exception.Message) }
    }
    $c.Dispose()
}

Write-Host '=== 4) 运行 stop.bat ==='
$stop = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'stop.bat' -WorkingDirectory $dir `
    -RedirectStandardOutput (Join-Path $env:TEMP 'pe-stop-test.log') -RedirectStandardError (Join-Path $env:TEMP 'pe-stop-test.err.log') -PassThru
Start-Sleep -Seconds 6
if (-not $stop.HasExited) { Stop-Process -Id $stop.Id -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2

$stillListening = [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
Write-Host ("停止后端口 $port 仍在监听 = " + $stillListening)

Write-Host '=== 5) start.bat 输出（GBK 解码，末尾 12 行）==='
if (Test-Path $outLog) {
    $gbk = [System.Text.Encoding]::GetEncoding(936)
    $text = $gbk.GetString([System.IO.File]::ReadAllBytes($outLog))
    ($text -split "`r?`n" | Where-Object { $_ -ne '' } | Select-Object -Last 12) | ForEach-Object { Write-Host ("  " + $_) }
}

if (-not $cmd.HasExited) { Stop-Process -Id $cmd.Id -Force -ErrorAction SilentlyContinue }
Remove-Item Env:PORT, Env:NO_BROWSER, Env:SKIP_BUILD, Env:STARTUP_TIMEOUT -ErrorAction SilentlyContinue
Write-Host '=== 测试结束 ==='
