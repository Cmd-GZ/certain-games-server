# BuildBattle: the compile classpath (Paper API 26.2 + transitive deps), ASCII only.
#
# Single source of truth: dot-source this file and use $paperCp.
#     . (Join-Path $PSScriptRoot 'paper-classpath.ps1')
# Used by build.ps1 and check-stats.ps1. Jars are compile-only, never bundled:
# the server provides them at runtime.
#
# Two roots are searched, per jar, in this order:
#   1. %USERPROFILE%\.m2\repository  - a normal dev machine (Maven / IDE filled it)
#   2. toolchain\.m2\repository      - a repo-local copy shipped in the migration
#                                      package, so a fresh PC can build with no .m2
# $paperCpRel holds the paths relative to either repository root.

$paperCpRel = @(
    'io\papermc\paper\paper-api\26.2.build.126-stable\paper-api-26.2.build.126-stable.jar'
    'com\google\guava\guava\33.6.0-jre\guava-33.6.0-jre.jar'
    'com\google\code\gson\gson\2.14.0\gson-2.14.0.jar'
    'org\yaml\snakeyaml\2.2\snakeyaml-2.2.jar'
    'org\joml\joml\1.10.8\joml-1.10.8.jar'
    'it\unimi\dsi\fastutil\8.5.18\fastutil-8.5.18.jar'
    'org\apache\logging\log4j\log4j-api\2.26.0\log4j-api-2.26.0.jar'
    'org\slf4j\slf4j-api\2.0.17\slf4j-api-2.0.17.jar'
    'com\mojang\brigadier\1.3.10\brigadier-1.3.10.jar'
    'net\md-5\bungeecord-chat\1.21-R0.2-deprecated+build.21\bungeecord-chat-1.21-R0.2-deprecated+build.21.jar'
    'net\kyori\adventure-api\5.2.0\adventure-api-5.2.0.jar'
    'net\kyori\adventure-key\5.2.0\adventure-key-5.2.0.jar'
    'net\kyori\adventure-text-logger-slf4j\5.2.0\adventure-text-logger-slf4j-5.2.0.jar'
    'net\kyori\adventure-text-minimessage\5.2.0\adventure-text-minimessage-5.2.0.jar'
    'net\kyori\adventure-text-serializer-commons\5.2.0\adventure-text-serializer-commons-5.2.0.jar'
    'net\kyori\adventure-text-serializer-gson\5.2.0\adventure-text-serializer-gson-5.2.0.jar'
    'net\kyori\adventure-text-serializer-json\5.2.0\adventure-text-serializer-json-5.2.0.jar'
    'net\kyori\adventure-text-serializer-legacy\5.2.0\adventure-text-serializer-legacy-5.2.0.jar'
    'net\kyori\adventure-text-serializer-plain\5.2.0\adventure-text-serializer-plain-5.2.0.jar'
    'net\kyori\examination-api\1.3.0\examination-api-1.3.0.jar'
    'net\kyori\examination-string\1.3.0\examination-string-1.3.0.jar'
    'org\jspecify\jspecify\1.0.0\jspecify-1.0.0.jar'
    'org\checkerframework\checker-qual\3.49.2\checker-qual-3.49.2.jar'
    'org\jetbrains\annotations\26.1.0\annotations-26.1.0.jar'
)

$m2 = Join-Path $env:USERPROFILE '.m2\repository'
$localM2 = Join-Path $PSScriptRoot 'toolchain\.m2\repository'

$paperCp = foreach ($rel in $paperCpRel) {
    $jar = Join-Path $m2 $rel
    if (-not (Test-Path $jar)) {
        $localJar = Join-Path $localM2 $rel
        if (Test-Path $localJar) { $jar = $localJar }
    }
    $jar
}

foreach ($jar in $paperCp) {
    if (-not (Test-Path $jar)) {
        throw "Missing compile dependency: $jar`nRun a Maven build once to populate .m2, or copy the jar into toolchain\.m2\repository (same relative path)."
    }
}


# Optional third-party API jars shipped with the server (compile-only).
$serverSkins = Join-Path $PSScriptRoot 'server\plugins\SkinsRestorer.jar'
if (Test-Path $serverSkins) {
    $paperCp = @($paperCp) + $serverSkins
}
