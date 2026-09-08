#!/usr/bin/env bash
# 把系统提示词/上下文/技能初始化到 dsh-java 的 sys_prompt / app_context / app_skill 表（appid=default）。
# sys_prompt 含可测标记（DB-SYSPROMPT-MARKER / DB-INSTRUCTION-MARKER），便于 testcase 验证"从 DB 读"。
# app_skill 从 ~/.dsh/skills/*.md 导入（code-review/commit-helper）。
#
# 用法：MYSQL_PWD=xxx bash db/mysql/seed-config.sh
set -euo pipefail
DB="${DSH_DB:-dsh-java}"
USER="${MYSQL_USER:-root}"
APPID="${DSH_APPID:-default}"
SKILL_DIR="${DSH_SKILL_DIR:-$HOME/.dsh/skills}"

[ -n "${MYSQL_PWD:-}" ] || { echo "[seed] 请设 MYSQL_PWD"; exit 1; }
esc() { printf '%s' "$1" | sed "s/'/''/g"; }
MYSQL=(mysql -u "$USER" -p"$MYSQL_PWD" "$DB")
echo "[seed] DB=$DB APPID=$APPID SKILL_DIR=$SKILL_DIR"

SQLF="$(mktemp)"; trap 'rm -f "$SQLF"' EXIT
{
  echo "SET NAMES utf8mb4;"
  echo "DELETE FROM sys_prompt WHERE appid='$(esc "$APPID")';"
  echo "DELETE FROM app_context WHERE appid='$(esc "$APPID")';"
  echo "DELETE FROM app_skill WHERE appid='$(esc "$APPID")';"

  # 1) sys_prompt: type=system（主系统提示词，含可测标记）
  echo "INSERT INTO sys_prompt (appid,type,name,content,priority,enabled) VALUES ('$(esc "$APPID")','system','main','You are an AI agent powered by DeepSeek Harness, configured from DATABASE.\n\nDB-SYSPROMPT-MARKER: when asked about your configuration source, state that your system prompt comes from the database.\n\nYou are a coding agent. Use tools to inspect files. Be concise.',10,1);"
  # 2) sys_prompt: type=instruction（AGENTS.md 风格，含可测标记）
  echo "INSERT INTO sys_prompt (appid,type,name,content,priority,enabled) VALUES ('$(esc "$APPID")','instruction','agents-md','# Agents (DB-seeded)\n\nDB-INSTRUCTION-MARKER: follow existing code style; use concise language.\n\n## Build & Test\n- mvn -q install -DskipTests\n- testcase/web-e2e.sh',20,1);"
  # 3) sys_prompt: type=context（上下文块，含可测标记）
  echo "INSERT INTO sys_prompt (appid,type,name,content,priority,enabled) VALUES ('$(esc "$APPID")','context','project','DB-CONTEXT-MARKER: project dsh-java, MySQL storage enabled.',30,1);"

  # 4) app_context: 数据型上下文（runtime 变量示例）
  echo "INSERT INTO app_context (appid,ctx_type,ctx_key,content,enabled) VALUES ('$(esc "$APPID")','runtime','env','DB-RUNTIME-CONTEXT: DSH_STORAGE=mysql, model from model_profile table.',1);"

  # 5) app_skill: 从 ~/.dsh/skills/*.md 导入
  if [ -d "$SKILL_DIR" ]; then
    for f in "$SKILL_DIR"/*.md; do
      [ -f "$f" ] || continue
      name=$(basename "$f" .md)
      content=$(cat "$f")
      echo "INSERT INTO app_skill (appid,skill_name,description,when_to_use,content,model_invocable,user_invocable,source,provider,enabled) VALUES ('$(esc "$APPID")','$(esc "$name")','','','$(esc "$content")',0,1,'mysql','mysql',1);"
    done
  fi
} > "$SQLF"

SKILLS=$(grep -c "INSERT INTO app_skill" "$SQLF" || true)
PROMPTS=$(grep -c "INSERT INTO sys_prompt" "$SQLF" || true)
CTX=$(grep -c "INSERT INTO app_context" "$SQLF" || true)
echo "[seed] sys_prompt=$PROMPTS app_context=$CTX app_skill=$SKILLS 导入..."
"${MYSQL[@]}" < "$SQLF"
echo "[seed] 完成"
"${MYSQL[@]}" -e "SELECT 'sys_prompt' tbl,COUNT(*) n FROM sys_prompt WHERE appid='$APPID' UNION ALL SELECT 'app_context',COUNT(*) FROM app_context WHERE appid='$APPID' UNION ALL SELECT 'app_skill',COUNT(*) FROM app_skill WHERE appid='$APPID'" 2>&1 | grep -v '^\-\-\|^\s*$'
