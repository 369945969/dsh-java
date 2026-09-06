# WebSocket 对接说明

dsh 的 WebSocket 是 **Spring Boot Web 服务端的一部分**，不是独立进程。由 `scripts\start.bat`（Windows）或 `scripts/start.sh`（Linux）启动（入口 `com.deepseek.dsh.app.boot.DshApplication`，默认端口 `8765`），与 REST / SSE 共用同一实例。

端点注册见 `dsh-web/.../server/WebSocketConfig.java:35`，处理器见 `dsh-web/.../server/AgentWebSocketHandler.java`。

---

## 1. 端点

| 项 | 值 |
|---|---|
| URL | `ws://<host>:<port>/ws/agent`（默认 `ws://localhost:8765/ws/agent`） |
| 协议 | 原生 WebSocket（非 STOMP），JSON 文本帧 |
| 跨域 | `setAllowedOrigins("*")` |
| 认证 | 需浏览器会话 cookie `dsh-auth`（见下节） |

可用环境变量 `DSH_WS_URL` 覆盖目标地址（`testcase/ws-e2e.py:28`）。

---

## 2. 认证：token → cookie 握手

`/ws` 端点要求请求带 `dsh-auth` 会话 cookie，裸连会被拒。获取流程（参考 `testcase/run-all.sh:48-64`）：

1. 启动服务端，从日志中解析启动令牌（形如 `token=xxxx`，日志行含 `authentication URL:`）。
2. 用该 token 换 cookie：
   ```bash
   curl -s -o /dev/null "http://localhost:8765/?token=$TOKEN" -c cookie.jar
   ```
   `cookie.jar` 是 Netscape 格式（`curl -c` 产物），其中含 `dsh-auth=<值>`。
3. 建立 WS 连接时在握手头带：
   ```
   Cookie: dsh-auth=<值>
   ```

> 简便做法：先跑一次 `testcase/web-e2e.sh` 或 `testcase/run-all.sh`，它们会生成 `testcase/.auth/cookie.jar`，之后 WS 客户端直接复用。`ws-e2e.py` 即从该 jar 读 cookie（可用 `DSH_COOKIE` 环境变量覆盖路径）。

---

## 3. 线协议（JSON 文本帧）

### 3.1 客户端 → 服务端

**发起对话**
```json
{ "action": "prompt", "sessionId": "s1", "message": "你好" }
```

**取消对话**
```json
{ "action": "cancel", "sessionId": "s1" }
```

字段说明：
- `action`：`prompt` / `cancel`（必填）
- `sessionId`：客户端自选的会话标识，任意非空字符串（必填）；服务端用它分桶回复
- `message`：用户输入（`prompt` 时必填）

### 3.2 服务端 → 客户端

```json
{ "event": "session", "sessionId": "s1" }
{ "event": "delta",   "sessionId": "s1", "data": "片段…" }
{ "event": "done",    "sessionId": "s1" }
```

| event | 含义 | data |
|---|---|---|
| `session` | 回合已开始 | 无 |
| `delta` | 流式增量片段 | 本片文本 |
| `done` | 正常结束 | 无 |
| `cancelled` | 被 `cancel` 中断 / 线程被中断 | 无 |
| `error` | 出错 | 错误信息 |

> 注：`session` / `done` / `cancelled` 帧不带 `data` 字段（服务端仅当 `data != null` 时才写入，见 `AgentWebSocketHandler.java:135-141`）。客户端解析时应将缺失的 `data` 视为空。

### 3.3 事件序列

- **正常流程**：`prompt` → `session` → `delta` ×N → `done`
- **取消流程**：`prompt` →（可能已有若干 `delta`）→ `cancel` → `cancelled`
- **出错**：`error`（终结帧）

**终结帧** = `done` / `cancelled` / `error`，收到任一即表示该 `sessionId` 本轮结束。

兜底行为：若流式未产出任何片段，服务端会把整段回复作为单个 `delta` 下发后再发 `done`（`AgentWebSocketHandler.java:97-99`）。

---

## 4. 并发与取消

- **同一连接可并发多 session**：在同一条 WS 连接上可交错下发多个不同 `sessionId` 的 `prompt`，每个回合在独立虚拟线程运行，回复按 `sessionId` 标记交错下发。客户端应按 `sessionId` 归并帧。
- **取消**：发送 `{"action":"cancel","sessionId":"..."}`，服务端中断该回合的 `Future`，下发 `cancelled` 终结帧。已发出但未收到的 `delta` 不保证到达。
- **连接关闭**：连接断开时，服务端取消该连接上所有运行中的回合（`afterConnectionClosed`）。

---

## 5. 最简客户端示例（Python）

依赖：`pip install websockets`

```python
import asyncio, json, websockets

URL = "ws://localhost:8765/ws/agent"
COOKIE = "dsh-auth=xxxxxxxxxxxxxxxx"   # 从 cookie.jar 取

async def main():
    headers = [("Cookie", COOKIE)]
    async with websockets.connect(URL, max_size=None, additional_headers=headers) as ws:
        await ws.send(json.dumps({
            "action": "prompt", "sessionId": "s1", "message": "你好，用一句话自我介绍"
        }))
        # 接收直到收到该 sid 的终结帧
        terms = {"done", "cancelled", "error"}
        while True:
            f = json.loads(await ws.recv())
            print(f)
            if f.get("sessionId") == "s1" and f.get("event") in terms:
                break

asyncio.run(main())
```

---

## 6. 参考实现

- **服务端**：`dsh-web/src/main/java/com/deepseek/dsh/web/server/`
  - `WebSocketConfig.java` —— 端点注册
  - `AgentWebSocketHandler.java` —— 帧处理 / 流式 / 取消
- **客户端 E2E**：`testcase/ws-e2e.py` —— 并发多 session + 流式 + 取消的完整示例
- **一键验证**：`testcase/run-all.sh` —— 自动启动服务端、握手、跑 WS E2E
- **启动脚本**：`scripts/start.bat` / `scripts/start.sh`
