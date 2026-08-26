#!/usr/bin/env python3
"""前端真实交互端到端验证 —— 用系统 chromium 打开 SPA → 输入 → 发送 → 等待回复渲染 → 断言。

证明 React 前端与 Java 后端联合运行功能正常（渲染→交互→API→渲染回复，全链路）。
依赖：python3 + playwright（pip install playwright）；系统 chromium（/usr/bin/chromium-browser）。
需先启动前后端（scripts/start.sh，默认 8765）。
"""
import os
import sys
from playwright.sync_api import sync_playwright

URL = os.environ.get("DSH_WEB_URL", "http://localhost:8765/")
CHROME = os.environ.get("CHROMIUM_PATH", "/usr/bin/chromium-browser")


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=CHROME,
                                   args=["--no-sandbox", "--disable-dev-shm-usage"])
        page = browser.new_page()
        page.goto(URL, wait_until="domcontentloaded")
        page.wait_for_selector(".input-bar__field", timeout=10000)
        conv = page.inner_text(".conversation")
        assert "DeepSeek Harness" in conv, "React 未渲染欢迎语"
        print("  [PASS] SPA 渲染（React 挂载，欢迎语出现）")

        page.fill(".input-bar__field", "你好")
        page.click(".input-bar__send")

        # 轮询等待回复渲染：用户消息出现 + 最后一条 assistant 非"思考中"且有实质内容
        got = False
        for _ in range(90):
            try:
                u = page.locator(".message--user").count()
                asst = page.locator(".message--assistant").all_inner_texts()
            except Exception:
                u, asst = 0, []
            last = asst[-1].strip() if asst else ""
            if u >= 1 and "思考中" not in last and len(last) > 5:
                got = True
                break
            page.wait_for_timeout(1000)

        if not got:
            try:
                print("  [DEBUG] DOM: " + page.inner_text(".conversation")[:300])
            except Exception:
                pass
            assert False, "未在 90s 内收到回复渲染"

        replies = page.locator(".message--assistant").all_inner_texts()
        users = page.locator(".message--user").all_inner_texts()
        last_reply = replies[-1].strip()
        assert len(last_reply) > 5, "回复为空"
        assert "你好" in users[-1], "用户消息未渲染"
        # 去掉 role 标签后取正文
        body = last_reply.split("\n", 1)[-1] if "\n" in last_reply else last_reply
        print(f"  [PASS] 交互发送→回复渲染  回复: {body[:80]}…")
        browser.close()
    print("\n[frontend] 结果: 前端与后端联合运行功能验证 PASS")
    sys.exit(0)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"\n[frontend] 结果: FAIL — {e}")
        sys.exit(1)
