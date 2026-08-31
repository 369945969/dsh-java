#!/usr/bin/env bash
# 编译后端：clean → install（跳过测试），删除旧 target/ 后全量重建。
# 用法： scripts/build-backend.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[build-backend] 清理旧构建产物..."
rm -rf "$ROOT"/dsh-*/target "$ROOT"/testcase/target
echo "[build-backend] 编译后端（mvn clean install -DskipTests）..."
cd "$ROOT"
mvn -q clean install -DskipTests -Dmaven.test.skip=true
echo "[build-backend] 生成运行时 classpath（rpc-cp.txt）..."
mvn -q -f "$ROOT/pom.xml" -pl dsh-app dependency:build-classpath -Dmdep.outputFile="$ROOT/dsh-app/target/rpc-cp.txt"
echo "[build-backend] 完成：dsh-app/target/classes + rpc-cp.txt 已生成"
