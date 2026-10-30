#!/usr/bin/env bash
# 把 Maven 装到 .server/tools/ 下（只用于本仓库构建，不污染系统）。
# 下载源优先 Maven Central（archive.apache.org 在国内经常超时）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/.server/tools"
VER="${1:-3.9.9}"
URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$VER/apache-maven-$VER-bin.tar.gz"
mkdir -p "$DEST"
if [ -x "$DEST/apache-maven-$VER/bin/mvn" ]; then echo "已存在：$DEST/apache-maven-$VER"; exit 0; fi
echo "下载 $URL"
curl -fsSL -o "$DEST/maven.tar.gz" "$URL"
tar xzf "$DEST/maven.tar.gz" -C "$DEST"
rm -f "$DEST/maven.tar.gz"
echo "OK: $DEST/apache-maven-$VER/bin/mvn"
