/**
 * 端到端验证 —— 验证 Java 后端 0.1.2 协议（token 认证 + combo + WS mux +
 * $events + session/follow + packed history）以及原有 apiproxy 能力。
 *
 * 运行: node testcase/e2e.ts  (Node 25+ 原生 TS，无需编译)
 * 或:   bash testcase/run-e2e.sh
 *
 * 依赖: 后端在 localhost:8765 运行 + 已配置可用模型（glm-5.2 等）。
 *       需要从启动日志获取 token URL（或通过 DSH_TOKEN 环境变量传入）。
 */
const API = 'http://localhost:8765'
const RPC = `${API}/api`

interface TestResult { name: string; pass: boolean; detail: string }
const results: TestResult[] = []

// ---- auth helpers ----

let cookieValue = ''

async function getAuthCookie(): Promise<string> {
  if (cookieValue) return cookieValue
  const token = process.env.DSH_TOKEN
  if (!token) throw new Error('DSH_TOKEN env var required (from start.sh log URL)')
  const res = await fetch(`${API}/?token=${token}`, { redirect: 'manual' })
  const sc = res.headers.get('set-cookie')
  if (!sc) throw new Error('no set-cookie in token exchange')
  cookieValue = sc.split(';')[0]
  return cookieValue
}

// ---- RPC helpers (0.1.2 two-segment path + args wrapper) ----

async function rpc(channel: string, endpoint: string, args: any = {}): Promise<any> {
  const ck = await getAuthCookie()
  const res = await fetch(`${RPC}/${channel}/${endpoint}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'cookie': ck },
    body: JSON.stringify({ type: 'client-request', rpcId: 'e2e-' + Math.random().toString(36).slice(2, 8), method: endpoint, payload: { args } }),
  })
  const json: any = await res.json()
  return json.result?.value
}

// Legacy dot-separated path (for old-style endpoints still in dispatch switch)
async function rpcLegacy(method: string, payload: any = {}): Promise<any> {
  const ck = await getAuthCookie()
  const res = await fetch(`${RPC}/${method}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'cookie': ck },
    body: JSON.stringify({ rpcId: 'e2e-' + Math.random().toString(36).slice(2, 8), payload }),
  })
  const json: any = await res.json()
  return json.result?.value
}

async function createSession(workspaceId?: string): Promise<string> {
  const v = await rpc('session', 'create', workspaceId ? { workspaceId } : {})
  return v.sessionId
}

async function prompt(sid: string, text: string): Promise<void> {
  await rpc('session', 'prompt', { sessionId: sid, message: text })
}

async function wait(ms: number): Promise<void> { await new Promise(r => setTimeout(r, ms)) }

async function getPage(sid: string): Promise<any[]> {
  const v = await rpc('session', 'page', { address: { sessionId: sid }, throughSeq: 999, maxMessages: 50 })
  return v.records || []
}

function assistantTextFromRecords(records: any[]): string {
  const msgs = records.filter(r => r.event?.type === 'assistant/message')
  const last = msgs[msgs.length - 1]
  return last?.event?.data?.message?.content?.[0]?.text || ''
}

function record(name: string, pass: boolean, detail: string): void {
  results.push({ name, pass, detail })
  console.log(`  ${pass ? '✓ PASS' : '✗ FAIL'}  ${name} — ${detail}`)
}

// ---- 0.1.2 protocol tests ----

// T1. Token 认证：无 token → 401
async function testT1_noTokenReturns401(): Promise<void> {
  const res = await fetch(`${API}/`)
  record('T1. 无token返回401', res.status === 401, `status=${res.status}`)
}

// T2. Token 换 cookie → 303 + Set-Cookie
async function testT2_tokenExchange(): Promise<void> {
  const ck = await getAuthCookie()
  record('T2. token换cookie', ck.startsWith('dsh-auth-'), `cookie=${ck.slice(0, 30)}…`)
}

// T3. Combo 路由 → 200 + JS content-type
async function testT3_comboRoute(): Promise<void> {
  const ck = await getAuthCookie()
  const res = await fetch(`${API}/plugins/??@deepseek-ai/dsh-client-connection/client.js&rev=test`,
    { headers: { cookie: ck } })
  const ct = res.headers.get('content-type') || ''
  const body = await res.text()
  record('T3. combo路由', res.status === 200 && ct.includes('javascript'), `status=${res.status}, bytes=${body.length}`)
}

// T4. Remote 两段路由 session/list → 200 + items
async function testT4_remoteRouting(): Promise<void> {
  const v = await rpc('session', 'list')
  record('T4. Remote两段路由', Array.isArray(v?.items), `${v?.items?.length} sessions`)
}

// T5. WS mux + $events ready → connected
async function testT5_wsMuxReady(): Promise<void> {
  const ck = await getAuthCookie()
  const WebSocket = (await import('node:module')).createRequire(import.meta.url)('/Users/jack/java/deepseek-harness/node_modules/.pnpm/ws@8.21.0/node_modules/ws')
  const ws = new WebSocket(`ws://localhost:8765/api/remote.mux`, { headers: { cookie: ck } })
  const ready = await new Promise<boolean>((resolve) => {
    ws.on('open', () => {
      ws.send(JSON.stringify({ type: 'open', streamId: 's1', endpoint: '$events', payload: { args: {} } }))
    })
    ws.on('message', (d: any) => {
      const m = JSON.parse(d.toString())
      if (m.value?.type === 'ready') { ws.close(); resolve(true) }
    })
    ws.on('error', () => resolve(false))
    setTimeout(() => { ws.close(); resolve(false) }, 5000)
  })
  record('T5. WS mux ready', ready, ready ? 'connected' : 'timeout')
}

// T6. session/follow 流 → snapshot + live events
async function testT6_sessionFollow(): Promise<void> {
  const ck = await getAuthCookie()
  const WebSocket = (await import('node:module')).createRequire(import.meta.url)('/Users/jack/java/deepseek-harness/node_modules/.pnpm/ws@8.21.0/node_modules/ws')
  const sid = await createSession()
  const ws = new WebSocket(`ws://localhost:8765/api/remote.mux`, { headers: { cookie: ck } })
  let snapshotSeen = false
  let followEvents = 0
  await new Promise<void>((resolve) => {
    ws.on('open', () => {
      ws.send(JSON.stringify({ type: 'open', streamId: 's1', endpoint: '$events', payload: { args: {} } }))
      ws.send(JSON.stringify({ type: 'open', streamId: 'f1', endpoint: 'session/follow', payload: { args: { address: { kind: 'session', sessionId: sid }, maxMessages: 50 } } }))
      setTimeout(async () => {
        await prompt(sid, 'say hi')
        await wait(8000)
        ws.close()
        resolve()
      }, 500)
    })
    ws.on('message', (d: any) => {
      const m = JSON.parse(d.toString())
      if (m.streamId === 'f1') {
        if (m.value?.type === 'snapshot') snapshotSeen = true
        if (m.value?.type === 'event') followEvents++
      }
    })
    ws.on('error', () => resolve())
    setTimeout(() => { ws.close(); resolve() }, 15000)
  })
  record('T6. session/follow流', snapshotSeen && followEvents > 0, `snapshot=${snapshotSeen}, events=${followEvents}`)
}

// T7. session.page packed history → records + hasMore
async function testT7_packedHistory(): Promise<void> {
  const sid = await createSession()
  await prompt(sid, 'say hi in 3 words')
  await wait(8000)
  const records = await getPage(sid)
  const hasUser = records.some(r => r.event?.type === 'user/message')
  const hasAssistant = records.some(r => r.event?.type === 'assistant/message')
  record('T7. packed history', records.length > 0 && hasUser && hasAssistant, `${records.length} records, user=${hasUser}, asst=${hasAssistant}`)
}

// T8. host/describe 版本 → 0.1.2-alpha.1
async function testT8_hostDescribeVersion(): Promise<void> {
  const v = await rpc('host', 'describe')
  record('T8. host.describe版本', v?.version === '0.1.2-alpha.1', `version=${v?.version}`)
}

// T9. session.list projections 格式 → {asOfSeq, values:{title}}
async function testT9_sessionListProjections(): Promise<void> {
  const sid = await createSession()
  await prompt(sid, 'test')
  await wait(8000)
  const v = await rpc('session', 'list')
  const me = v?.items?.find((s: any) => s.sessionId === sid)
  const hasProj = me?.projections?.values?.title !== undefined
  record('T9. session.list projections', hasProj, hasProj ? `title="${me.projections.values.title}"` : 'no projections')
}

// T10. $events emit 实时事件转发
async function testT10_emitForwarding(): Promise<void> {
  const ck = await getAuthCookie()
  const WebSocket = (await import('node:module')).createRequire(import.meta.url)('/Users/jack/java/deepseek-harness/node_modules/.pnpm/ws@8.21.0/node_modules/ws')
  const sid = await createSession()
  const ws = new WebSocket(`ws://localhost:8765/api/remote.mux`, { headers: { cookie: ck } })
  let emitCount = 0
  await new Promise<void>((resolve) => {
    ws.on('open', () => {
      ws.send(JSON.stringify({ type: 'open', streamId: 's1', endpoint: '$events', payload: { args: {} } }))
      setTimeout(async () => {
        await prompt(sid, 'say hi')
        await wait(8000)
        ws.close()
        resolve()
      }, 500)
    })
    ws.on('message', (d: any) => {
      const m = JSON.parse(d.toString())
      if (m.value?.type === 'emit') emitCount++
    })
    ws.on('error', () => resolve())
    setTimeout(() => { ws.close(); resolve() }, 15000)
  })
  record('T10. $events emit转发', emitCount > 0, `${emitCount} emit frames`)
}

// T11. $events/result 端点 → 200
async function testT11_eventsResult(): Promise<void> {
  const v = await rpc('$events', 'result', { clientId: 'c1', eventId: 'e1', outcome: { kind: 'next' } })
  record('T11. $events/result', v !== undefined, `ok=${v !== undefined}`)
}

// ---- original apiproxy tests (compatibility) ----

// O1. 基础 agent 会话返回
async function testO1_basicResponse(): Promise<void> {
  const sid = await createSession()
  await prompt(sid, '你好，请用一句话自我介绍')
  await wait(12000)
  const records = await getPage(sid)
  const text = assistantTextFromRecords(records)
  record('O1. 基础agent会话返回', text.length > 0, `回复: ${text.slice(0, 50)}`)
}

// O2. session 多轮对话（记忆）
async function testO2_multiTurnMemory(): Promise<void> {
  const sid = await createSession()
  await prompt(sid, '请记住我的名字叫张三')
  await wait(12000)
  await prompt(sid, '我叫什么名字？')
  await wait(12000)
  const records = await getPage(sid)
  const text = assistantTextFromRecords(records)
  const remembers = text.includes('张三')
  record('O2. session多轮对话(记忆)', remembers, remembers ? '记住了张三' : `回复: ${text.slice(0, 40)}`)
}

// O3. 工具调用验证
async function testO3_toolCall(): Promise<void> {
  const sid = await createSession()
  await prompt(sid, '请用 read 工具读取当前目录的 README.md 文件')
  await wait(15000)
  const records = await getPage(sid)
  const hasToolCall = records.some(r => r.event?.type === 'tool/call')
  const hasToolResult = records.some(r => r.event?.type === 'tool/result')
  record('O3. 工具调用验证', hasToolCall && hasToolResult, `call=${hasToolCall}, result=${hasToolResult}`)
}

// ---- main ----

async function main(): Promise<void> {
  console.log('\n=== E2E 端到端测试 (0.1.2 协议 + 兼容性) ===\n')
  const tests = [
    testT1_noTokenReturns401, testT2_tokenExchange, testT3_comboRoute,
    testT4_remoteRouting, testT5_wsMuxReady, testT6_sessionFollow,
    testT7_packedHistory, testT8_hostDescribeVersion,
    testT9_sessionListProjections, testT10_emitForwarding,
    testT11_eventsResult,
    testO1_basicResponse, testO2_multiTurnMemory, testO3_toolCall,
  ]
  for (const t of tests) {
    try { await t() } catch (e: any) {
      record(t.name, false, `EXCEPTION: ${e.message}`)
    }
  }
  const passed = results.filter(r => r.pass).length
  const total = results.length
  console.log(`\n=== 结果: ${passed}/${total} 通过 ===\n`)
  process.exit(passed === total ? 0 : 1)
}

main()
