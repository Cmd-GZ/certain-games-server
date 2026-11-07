# 建筑猜猜乐地图验证脚本：启动服务端 → 读取关键坐标的方块 → 保存关闭
$ErrorActionPreference = 'Stop'
$srv = $PSScriptRoot
$java = Join-Path $srv 'jdk\bin\java.exe'
$logFile = Join-Path $srv 'logs\map_verify.log'

if (-not (Test-Path $java)) { throw "未找到 JDK: $java" }
if (Get-Process -Name java -ErrorAction SilentlyContinue) { throw '检测到 java 进程正在运行，请先停止服务端' }

New-Item -ItemType Directory -Force (Join-Path $srv 'logs') | Out-Null

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $java
$psi.Arguments = '-Xms1G -Xmx2G -jar server.jar nogui'
$psi.WorkingDirectory = $srv
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $false
$p = [System.Diagnostics.Process]::Start($psi)
$reader = $p.StandardOutput
$sb = New-Object System.Text.StringBuilder
$script:readTask = $null

function Read-LineTimed([int]$TimeoutMs) {
    try {
        if ($null -eq $script:readTask) { $script:readTask = $reader.ReadLineAsync() }
        if ($script:readTask.Wait($TimeoutMs)) {
            $result = $script:readTask.Result
            $script:readTask = $null
            return $result
        }
        return $null
    } catch {
        $script:readTask = $null
        return $null
    }
}
function Wait-Started {
    $deadline = [DateTime]::Now.AddSeconds(240)
    while ([DateTime]::Now -lt $deadline) {
        $line = Read-LineTimed 2000
        if ($null -ne $line) {
            [void]$sb.AppendLine($line)
            if ($line -match 'Done \(') { return $true }
        }
    }
    return $false
}
function Send-Cmd([string]$Cmd, [int]$WaitMs = 1500) {
    $p.StandardInput.WriteLine($Cmd)
    $p.StandardInput.Flush()
    Start-Sleep -Milliseconds $WaitMs
    for ($i = 0; $i -lt 40; $i++) {
        $line = Read-LineTimed 300
        if ($null -eq $line) { break }
        [void]$sb.AppendLine($line)
    }
}

try {
    if (-not (Wait-Started)) { throw '服务端启动超时' }
    $cmds = @(
        'gtb',
        'gtb help',
        'gb info',
        'help',
        'save-all',
        'stop'
    )
    foreach ($c in $cmds) { Send-Cmd $c }
    $p.WaitForExit(60000) | Out-Null
} finally {
    if (-not $p.HasExited) { try { $p.Kill() } catch {} }
    while ($true) {
        $line = Read-LineTimed 1500
        if ($null -eq $line) { break }
        [void]$sb.AppendLine($line)
    }
    [IO.File]::WriteAllText($logFile, $sb.ToString(), (New-Object System.Text.UTF8Encoding $false))
    Write-Output "exit_code=$($p.ExitCode)"
    Write-Output "log=$logFile"
}
