#!/usr/bin/env bash
# 编译 agentscope 工程：mvn clean package 生成 fat jar
# 用法：scripts/build.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
echo "[build] mvn clean package..."
mvn -q clean package -DskipTests
echo "[build] 完成：target/agentscope-1.0.0.jar"
