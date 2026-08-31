#!/usr/bin/env python3
"""WebSocket 端到端验证 —— 实时通信模式：并发多 session + 流式 + 会话取消。

协议（与后端 AgentWebSocketHandler 一致，ws://host/ws/agent）：
  C→S: {"action":"prompt","sessionId":"s1","message":"..."} / {"action":"cancel","sessionId":"s1"}
  S→C: {"event":"session|delta|done|cancelled|error","sessionId":"s1","data":"..."}

依赖：python3 + websockets（已随环境）。需先启动 Web 服务端（scripts/start.sh，默认 8765）。

认证：后端 /ws 需要浏览器会话 cookie。本脚本从 testcase/.auth/cookie.jar
（由 run-all.sh / web-e2e.sh 握手产生）读取 dsh-auth cookie 并在 WS 握手时带上。
可用 DSH_COOKIE 环境变量覆盖 cookie jar 路径。
"""
import asyncio
import json
import os
import sys

import websockets

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AUTH_DIR = os.path.join(ROOT, "testcase", ".auth")
COOKIE_JAR = os.environ.get("DSH_COOKIE", os.path.join(AUTH_DIR, "cookie.jar"))
WS_URL = os.environ.get("DSH_WS_URL", "ws://localhost:8765/ws/agent")
PASS = 0
FAIL = 0


def load_cookie_header(cookie_path):
    """解析 Netscape cookie jar（curl -c 产物），返回 "name=value; ..." 头或 None。"""
    if not os.path.isfile(cookie_path):
        return None
    pairs = []
    try:
        with open(cookie_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.rstrip("\n")
                if not line:
                    continue
                # 注释行跳过；#HttpOnly_ 前缀行是真 cookie，去掉前缀即可
                if line.startswith("#") and not line.startswith("#HttpOnly_"):
                    continue
                if line.startswith("#HttpOnly_"):
                    line = line[len("#HttpOnly_"):]
                fields = line.split("\t")
                if len(fields) < 7:
                    continue
                name, value = fields[5], fields[6]
                if name:
                    pairs.append(f"{name}={value}")
    except OSError as e:
        print(f"[ws-e2e] 读取 cookie jar 失败: {e}", file=sys.stderr)
        return None
    return "; ".join(pairs) if pairs else None


def pass_(name, extra=""):
    global PASS
    PASS += 1
    print(f"  [PASS] {name}{extra}")


def fail(name, why):
    global FAIL
    FAIL += 1
    print(f"  [FAIL] {name} — {why}")


COOKIE_HEADER = None


def ws_connect(url):
    """带浏览器会话 cookie 建立 WS 连接（/ws 需要认证）。"""
    headers = [("Cookie", COOKIE_HEADER)] if COOKIE_HEADER else None
    return websockets.connect(url, max_size=None, additional_headers=headers)


async def recv_until(ws, sid, terminals, timeout=120.0):
    """接收帧直到收到该 sid 的终结事件（done/cancelled/error）或超时。返回全部帧。"""
    frames = []
    try:
        while True:
            raw = await asyncio.wait_for(ws.recv(), timeout=timeout)
            f = json.loads(raw)
            frames.append(f)
            if f.get("sessionId") == sid and f.get("event") in terminals:
                return frames
    except (asyncio.TimeoutError, Exception) as e:
        fail(f"recv {sid}", f"超时/异常: {e}")
        return frames


async def test_concurrent_multi_session():
    """并发多 session + 流式：同连接并发起两个不同 sid 的对话，交错下发，各自走完 session→delta*→done。"""
    name = "并发多 session + 流式 (session→delta*→done)"
    try:
        async with ws_connect(WS_URL) as ws:
            await ws.send(json.dumps({"action": "prompt", "sessionId": "ws-a",
                                      "message": "你好，用一句话自我介绍"}))
            await ws.send(json.dumps({"action": "prompt", "sessionId": "ws-b",
                                      "message": "用一句话介绍 Python"}))
            # 交错帧会混在一起：累积全部帧直到两者都终结，再按 sid 分桶
            all_frames = []
            terms = {"done", "cancelled", "error"}
            deadline = asyncio.get_event_loop().time() + 120.0
            while asyncio.get_event_loop().time() < deadline:
                try:
                    raw = await asyncio.wait_for(ws.recv(), timeout=deadline - asyncio.get_event_loop().time())
                except (asyncio.TimeoutError, Exception):
                    break
                f = json.loads(raw)
                all_frames.append(f)
                a_done = any(f.get("sessionId") == "ws-a" and f.get("event") in terms for f in all_frames)
                b_done = any(f.get("sessionId") == "ws-b" and f.get("event") in terms for f in all_frames)
                if a_done and b_done:
                    break
            ea = {f["event"] for f in all_frames if f.get("sessionId") == "ws-a"}
            eb = {f["event"] for f in all_frames if f.get("sessionId") == "ws-b"}
            if "session" in ea and "done" in ea and "delta" in ea and "session" in eb and "done" in eb and "delta" in eb:
                pass_(name, f"  (a帧={sum(1 for f in all_frames if f.get('sessionId')=='ws-a')}, b帧={sum(1 for f in all_frames if f.get('sessionId')=='ws-b')})")
            else:
                fail(name, f"帧不完整 a={ea} b={eb}")
    except Exception as e:
        fail(name, str(e))


async def test_cancel():
    """会话取消：发起一个长任务后立即取消，应收到 cancelled 终结帧。"""
    name = "会话取消 (cancel)"
    try:
        async with ws_connect(WS_URL) as ws:
            await ws.send(json.dumps({"action": "prompt", "sessionId": "ws-c",
                                      "message": "请写一篇 1200 字的散文"}))
            await asyncio.sleep(0.2)
            await ws.send(json.dumps({"action": "cancel", "sessionId": "ws-c"}))
            fc = await recv_until(ws, "ws-c", {"cancelled", "done", "error"}, timeout=60.0)
            ec = {f["event"] for f in fc if f.get("sessionId") == "ws-c"}
            if "cancelled" in ec:
                pass_(name)
            else:
                fail(name, f"未收到 cancelled，事件={ec}")
    except Exception as e:
        fail(name, str(e))


async def main():
    global COOKIE_HEADER
    COOKIE_HEADER = load_cookie_header(COOKIE_JAR)
    if not COOKIE_HEADER:
        print(f"[ws-e2e] [FAIL] 未找到认证 cookie：{COOKIE_JAR} 不存在或为空。\n"
              f"        请先运行 testcase/web-e2e.sh（或 run-all.sh）启动服务端并完成 token→cookie 握手。",
              file=sys.stderr)
        sys.exit(1)
    print("[ws-e2e] 目标:", WS_URL)
    await test_concurrent_multi_session()
    await test_cancel()
    print(f"\n[ws-e2e] 结果: {PASS} 通过, {FAIL} 失败")
    sys.exit(0 if FAIL == 0 else 1)


if __name__ == "__main__":
    asyncio.run(main())
