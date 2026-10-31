<#
    BuildBattle - build all plugins at once.
    (This file is intentionally ASCII-only: Windows PowerShell 5.1 reads .ps1 as
     ANSI unless the file has a UTF-8 BOM, which would corrupt non-ASCII text.)

    Usage (from the repository root):
        .\build.ps1                 build only, jars go to .build\out\
        .\build.ps1 -Deploy         build and copy jars into server\plugins\
        build.bat                   same as above (also bypasses the execution policy)

    Requirements:
        - JDK 25: server\jdk\bin\javac.exe when present, else JAVA_HOME or javac on PATH
        - Paper API and its transitive dependencies already present in %USERPROFILE%\.m2
#>
param(
    [switch]$Deploy,
    # Build one plugin only (MiniGameLib / HubCore are added automatically when needed).
    [string]$Only = '',
    # Output folder, default .build\out. Use a distinct folder when checking in parallel.
    [string]$OutDir = ''
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$jdk = Join-Path $root 'server\jdk\bin'
if (-not (Test-Path (Join-Path $jdk 'javac.exe'))) {
    # server\ is not versioned, so a fresh clone has no bundled JDK:
    # fall back to JAVA_HOME, then to javac on PATH.
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        $jdk = Join-Path $env:JAVA_HOME 'bin'
    } else {
        $javacCmd = Get-Command javac.exe -ErrorAction SilentlyContinue
        if ($javacCmd) { $jdk = Split-Path $javacCmd.Source -Parent }
    }
}
$out = if ($OutDir) { Join-Path $root $OutDir } else { Join-Path $root '.build\out' }

foreach ($exe in 'javac.exe', 'jar.exe') {
    if (-not (Test-Path (Join-Path $jdk $exe))) {
        throw "Missing $jdk\$exe - install JDK 25 and set JAVA_HOME, or restore server\jdk."
    }
}

# Paper API + transitive dependencies. Scope is compile-only: they are never
# bundled into the plugin jars (the server provides them at runtime).
# The list lives in paper-classpath.ps1 (shared with check-stats.ps1).
. (Join-Path $root 'paper-classpath.ps1')

# Compile order == dependency order:
#   MiniGameLib provides com.hub.api/com.hub.util, HubCore and every game plugin use it.
$allPlugins = @('MiniGameLib', 'HubCore', 'GameWorlds', 'GuessTheBuild', 'DuelArena', 'HotPotato', 'TntRun', 'MurderMystery', 'SkyPit', 'FootballArena', 'ColorParty', 'BuildRun', 'LuckyPillar', 'JumpFill', 'LandClaim', 'TNTBattle', 'Quark', 'KnockbackBattle', 'GroupRace', 'DigToBottom', 'AnvilBattle', 'AnimalSlaughter', 'SnakeParty', 'ArcadeParty', 'MemoryFlip', 'HillKing', 'OneShot')
if ($Only) {
    if ($allPlugins -notcontains $Only) {
        throw "-Only $Only is not a known plugin. Known: $($allPlugins -join ', ')"
    }
    # Single-plugin mode: build the target plus the MiniGameLib/HubCore it needs, in order.
    $plugins = switch ($Only) {
        'MiniGameLib' { @('MiniGameLib') }
        'HubCore'     { @('MiniGameLib', 'HubCore') }
        default       { @('MiniGameLib', 'HubCore', $Only) }
    }
} else {
    $plugins = $allPlugins
}

if (-not (Test-Path $out)) {
    New-Item -ItemType Directory -Path $out -Force | Out-Null
}
$classpath = ($paperCp -join ';')

foreach ($name in $plugins) {
    $dir = Join-Path $root $name
    $src = Join-Path $dir 'src\main\java'
    $res = Join-Path $dir 'src\main\resources'
    $classes = Join-Path $out "$name.classes"
    if (Test-Path $classes) {
        Remove-Item $classes -Recurse -Force
    }
    New-Item -ItemType Directory -Path $classes -Force | Out-Null

    $sources = Get-ChildItem -LiteralPath $src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    if (-not $sources) {
        throw "$name has no .java sources under $src"
    }

    # javac prints "deprecated API" notes to stderr even with -nowarn, and
    # Windows PowerShell turns native stderr into errors that $ErrorActionPreference
    # = 'Stop' would treat as fatal. Relax it for the compiler call only.
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & (Join-Path $jdk 'javac.exe') --release 21 -encoding UTF-8 -nowarn `
        '-Xlint:-removal' '-Xlint:-deprecation' -cp $classpath -d $classes $sources
    $javacExit = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    if ($javacExit -ne 0) {
        throw "$name failed to compile."
    }

    # Ship plugin.yml / config.yml next to the classes.
    if (Test-Path $res) {
        Copy-Item (Join-Path $res '*') -Destination $classes -Force
    }
    $jarPath = Join-Path $out "$name.jar"
    & (Join-Path $jdk 'jar.exe') --create --file $jarPath -C $classes .
    if ($LASTEXITCODE -ne 0) {
        throw "$name failed to package."
    }

    # Later plugins can compile against this one's output.
    $classpath = "$classpath;$classes"
    Write-Host ("[built]  {0,-16} -> {1}" -f $name, $jarPath)

    if ($Deploy) {
        Copy-Item $jarPath (Join-Path $root "server\plugins\$name.jar") -Force
        Write-Host ("[deploy] {0}" -f "server\plugins\$name.jar")
    }
}

Write-Host ""
Write-Host "Done: $($plugins.Count) plugins."
if (-not $Deploy) {
    Write-Host "Tip: add -Deploy to also copy the jars into server\plugins\."
}
