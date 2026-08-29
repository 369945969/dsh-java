#!/usr/bin/env python3
"""WebSocket 端到端验证 —— 实时通信模式：并发多 session + 流式 + 会话取消。

协议（与后端 AgentWebSocketHandler 一致，ws://host/ws/agent）：
  C→S: {"action":"prompt","sessionId":"s1","message":"..."} / {"action":"cancel","sessionId":"s1"}
  S→C: {"event":"session|delta|done|cancelled|error","sessionId":"s1","data":"..."}

依赖：python3 + websockets（已随环境）。需先启动 Web 服务端（scripts/start.sh，默认 8765）。
"""
import asyncio
import json
import os
import sys

import websockets

WS_URL = os.environ.get("DSH_WS_URL", "ws://localhost:8765/ws/agent")
PASS = 0
FAIL = 0


def pass_(name, extra=""):
    global PASS
    PASS += 1
    print(f"  [PASS] {name}{extra}")


def fail(name, why):
    global FAIL
    FAIL += 1
    print(f"  [FAIL] {name} — {why}")


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
        async with websockets.connect(WS_URL, max_size=None) as ws:
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
        async with websockets.connect(WS_URL, max_size=None) as ws:
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
    print("[ws-e2e] 目标:", WS_URL)
    await test_concurrent_multi_session()
    await test_cancel()
    print(f"\n[ws-e2e] 结果: {PASS} 通过, {FAIL} 失败")
    sys.exit(0 if FAIL == 0 else 1)


if __name__ == "__main__":
    asyncio.run(main())
