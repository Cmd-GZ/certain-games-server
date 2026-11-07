# 建筑猜猜乐地图生成脚本（Paper 26.2）
# 生成内容：超平坦世界的 大厅（出生点）+ 25x25 建造区 + 四色观战台 + 屏障围墙
# 运行方式：右键“使用 PowerShell 运行”，或 PowerShell 中执行 .\build_gtb_map.ps1
# 注意：脚本会启动一次服务端（要求已停止），完成后自动保存并关闭。

$ErrorActionPreference = 'Stop'
$srv = $PSScriptRoot
$java = Join-Path $srv 'jdk\bin\java.exe'
$logFile = Join-Path $srv 'logs\map_build.log'

if (-not (Test-Path $java)) { throw "未找到 JDK: $java" }
if (-not (Test-Path (Join-Path $srv 'server.jar'))) { throw '未找到 server.jar' }
if (Get-Process -Name java -ErrorAction SilentlyContinue) { throw '检测到 java 进程正在运行，请先停止服务端再运行本脚本' }

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
function Send-Cmd([string]$Cmd, [int]$WaitMs = 1200) {
    $p.StandardInput.WriteLine($Cmd)
    $p.StandardInput.Flush()
    Start-Sleep -Milliseconds $WaitMs
    for ($i = 0; $i -lt 80; $i++) {
        $line = Read-LineTimed 300
        if ($null -eq $line) { break }
        [void]$sb.AppendLine($line)
    }
}

try {
    if (-not (Wait-Started)) { throw '服务端启动超时' }
    [void]$sb.AppendLine('== 启动完成，开始生成地图 ==')

# 平坦世界地表：基岩-64 / 泥土-63..-62 / 草方块-61，地面顶面 = -60
# 因此所有场地都用 -64..-61 四层实心垫高，保证与周围地面平齐
$cmds = @(
    # --- 全局游戏规则（1.21.11 起规则名带 minecraft: 前缀）---
    'gamerule',
    'gamerule minecraft:advance_time false',
    'gamerule minecraft:weather_cycle false',
    'gamerule minecraft:spawn_mobs false',
    'gamerule minecraft:random_tick_speed 0',
    'time set 6000',
    'forceload add 30 -20 90 20',

    # --- 大厅（出生点 0,0，13x13 石英平台 + 金块描边 + 四角立柱灯笼）---
    'fill -6 -64 -6 6 -61 6 minecraft:quartz_block',
    'fill -7 -64 -7 -7 -61 7 minecraft:gold_block',
    'fill 7 -64 -7 7 -61 7 minecraft:gold_block',
    'fill -7 -64 -7 7 -61 -7 minecraft:gold_block',
    'fill -7 -64 7 7 -61 7 minecraft:gold_block',
    'setblock -7 -60 -7 minecraft:quartz_pillar',
    'setblock -7 -59 -7 minecraft:quartz_pillar',
    'setblock -7 -58 -7 minecraft:quartz_pillar',
    'setblock -7 -57 -7 minecraft:sea_lantern',
    'setblock 7 -60 -7 minecraft:quartz_pillar',
    'setblock 7 -59 -7 minecraft:quartz_pillar',
    'setblock 7 -58 -7 minecraft:quartz_pillar',
    'setblock 7 -57 -7 minecraft:sea_lantern',
    'setblock -7 -60 7 minecraft:quartz_pillar',
    'setblock -7 -59 7 minecraft:quartz_pillar',
    'setblock -7 -58 7 minecraft:quartz_pillar',
    'setblock -7 -57 7 minecraft:sea_lantern',
    'setblock 7 -60 7 minecraft:quartz_pillar',
    'setblock 7 -59 7 minecraft:quartz_pillar',
    'setblock 7 -58 7 minecraft:quartz_pillar',
    'setblock 7 -57 7 minecraft:sea_lantern',
    'setblock 0 -60 -6 minecraft:oak_sign',
    'data merge block 0 -60 -6 {front_text:{messages:[''{"text":"欢迎来到建筑猜猜乐","color":"gold"}'',''{"text":"/gtb join 加入 · /gtb start 开始","color":"white"}'',''{"text":"建造场地：正东方向 x48~72","color":"gray"}'','''']}}',

    # --- 建造场地：27x27 石底座（47~73, -13~13），25x25 场地（48~72, -12~12）---
    'fill 47 -64 -13 73 -61 13 minecraft:stone',
    'fill 48 -61 -12 72 -61 12 minecraft:lime_concrete',
    'fill 50 -61 -10 70 -61 10 minecraft:white_concrete',
    # 场地四周屏障围墙（y -60..-50，不可见，防止建造者跑出）
    'fill 47 -60 -13 47 -50 13 minecraft:barrier',
    'fill 73 -60 -13 73 -50 13 minecraft:barrier',
    'fill 48 -60 -13 72 -50 -13 minecraft:barrier',
    'fill 48 -60 13 72 -50 13 minecraft:barrier',
    # 场地四角海晶灯
    'setblock 48 -60 -12 minecraft:sea_lantern',
    'setblock 72 -60 -12 minecraft:sea_lantern',
    'setblock 48 -60 12 minecraft:sea_lantern',
    'setblock 72 -60 12 minecraft:sea_lantern',

    # --- 四色观战台（y=-53，与插件 view-height 8 对应）---
    'fill 34 -53 -2 38 -53 2 minecraft:red_wool',
    'fill 82 -53 -2 86 -53 2 minecraft:blue_wool',
    'fill 58 -53 20 62 -53 24 minecraft:green_wool',
    'fill 58 -53 -24 62 -53 -20 minecraft:yellow_wool',

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
