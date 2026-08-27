/**
 * 端到端验证 —— 验证 Java 后端 apiproxy + SSE + WebSocket 的 11 项核心能力。
 *
 * 运行: node testcase/e2e.ts  (Node 25+ 原生 TS，无需编译)
 * 或:   bash testcase/run-e2e.sh
 *
 * 依赖: 后端在 localhost:8765 运行 + 已配置可用模型（glm-5.2 等）。
 */
const API = 'http://localhost:8765'
const RPC = `${API}/api`

interface TestResult { name: string; pass: boolean; detail: string }
const results: TestResult[] = []

// ---- helpers ----

async function rpc(method: string, payload: any = {}): Promise<any> {
  const res = await fetch(`${RPC}/${method}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ rpcId: 'e2e-' + Math.random().toString(36).slice(2, 8), payload }),
  })
  const json: any = await res.json()
  return json.result?.value
}

async function createSession(workspaceId?: string): Promise<string> {
  const v = await rpc('session.create', workspaceId ? { workspaceId } : {})
  return v.sessionId
}

async function prompt(sid: string, text: string): Promise<void> {
  await rpc('session.prompt', { sessionId: sid, mode: 'queue', content: [{ type: 'text', text }] })
}

async function wait(ms: number): Promise<void> { await new Promise(r => setTimeout(r, ms)) }

async function getHistory(sid: string): Promise<any[]> {
  const v = await rpc('session.history', { sessionId: sid })
  return v.events || []
}

function assistantText(evs: any[]): string {
  const msgs = evs.filter(e => e.event?.type === 'assistant/message')
  const last = msgs[msgs.length - 1]
  return last?.event?.data?.message?.content?.[0]?.text || ''
}

function record(name: string, pass: boolean, detail: string): void {
  results.push({ name, pass, detail })
  console.log(`  ${pass ? '✓ PASS' : '✗ FAIL'}  ${name} — ${detail}`)
}

// ---- test cases ----

// 1. 基础 agent 会话返回
async function test1_basicResponse(): Promise<void> {
  const sid = await createSession()
  await prompt(sid, '你好，请用一句话自我介绍')
  await wait(12000)
  const evs = await getHistory(sid)
  const text = assistantText(evs)
  record('1. 基础agent会话返回', text.length > 0, `回复: ${text.slice(0, 50)}`)
}

// 2. 流读取返回（SSE）
async function test2_streamRead(): Promise<void> {
  try {
    const res = await fetch(`${API}/api/agent/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: '用一句话介绍 Python' }),
    })
    const text = await res.text()
    const chunks = text.split('\n').filter(l => l.startsWith('data:'))
    record('2. 流读取返回(SSE)', chunks.length > 0, `${chunks.length} SSE chunks`)
  } catch (e: any) {
    record('2. 流读取返回(SSE)', false, e.message)
  }
}

// 3. 完整性推理完返回
async function test3_reasoningComplete(): Promise<void> {
  const sid = await createSession()
  await prompt(sid, '123 乘以 456 等于多少？请仔细推理')
  await wait(15000)
  const evs = await getHistory(sid)
  const text = assistantText(evs)
  record('3. 完整性推理完返回', text.length > 0, `回复: ${text.slice(0, 60)}`)
}

// 4. 多 agent 协同（subagent/task + team/run）
async function test4_multiAgent(): Promise<void> {
  const sid = await createSession()
  const taskV = await rpc('subagent.task', { sessionId: sid, task: '测试任务' })
  const teamV = await rpc('team.run', { sessionId: sid, task: '团队测试' })
  const ok = taskV !== undefined && teamV !== undefined
  record('4. 多agent协同', ok, `subagent=${taskV ? 'ok' : 'fail'}, team=${teamV ? 'ok' : 'fail'}`)
}

// 5. WebSocket 连接
async function test5_websocket(): Promise<void> {
  const sid = await createSession()
  const frames: any[] = await new Promise((resolve) => {
    const ws = new WebSocket(`${API.replace('http', 'ws')}/ws/agent`)
    const collected: any[] = []
    let settled = false
    ws.onopen = () => ws.send(JSON.stringify({ action: 'prompt', sessionId: sid, message: '你好' }))
    ws.onmessage = (e: any) => {
      try { collected.push(JSON.parse(e.data.toString())) } catch {}
      if (collected.some(f => f.event === 'done' || f.event === 'error')) { settled = true; ws.close() }
    }
    ws.onerror = () => { settled = true; resolve(collected) }
    ws.onclose = () => resolve(collected)
    setTimeout(() => { if (!settled) ws.close() }, 30000)
  })
  const hasReply = frames.some(f => f.event === 'done')
  record('5. WebSocket连接', hasReply, `${frames.length} frames, done=${hasReply}`)
}

// 6. session 多轮对话（记忆）
async function test6_multiTurnMemory(): Promise<void> {
  const sid = await createSession()
  await prompt(sid, '请记住我的名字叫张三')
  await wait(12000)
  await prompt(sid, '我叫什么名字？')
  await wait(12000)
  const evs = await getHistory(sid)
  const text = assistantText(evs)
  const remembers = text.includes('张三')
  record('6. session多轮对话(记忆)', remembers, remembers ? '记住了张三' : `回复: ${text.slice(0, 40)}`)
}

// 7. session 对话后 fork 新 session 保留记忆
async function test7_forkWithMemory(): Promise<void> {
  const sid = await createSession()
  await prompt(sid, '记住密码是 1234')
  await wait(12000)
  const forkV = await rpc('session.fork', { sessionId: sid })
  const childSid = forkV?.sessionId
  if (!childSid) { record('7. fork保留记忆', false, 'fork 未返回 sessionId'); return }
  await prompt(childSid, '密码是多少？')
  await wait(12000)
  const evs = await getHistory(childSid)
  const text = assistantText(evs)
  const remembers = text.includes('1234')
  record('7. fork保留记忆', remembers, remembers ? '记住了 1234' : `回复: ${text.slice(0, 40)}`)
}

// 8. session 对话后 fork 新 session 不保留记忆（新建 session）
async function test8_forkWithoutMemory(): Promise<void> {
  const sid = await createSession()
  await prompt(sid, '记住密码是 5678')
  await wait(12000)
  const newSid = await createSession()
  await prompt(newSid, '密码是多少？')
  await wait(12000)
  const evs = await getHistory(newSid)
  const text = assistantText(evs)
  const noMemory = !text.includes('5678')
  record('8. fork不保留记忆(新session)', noMemory, noMemory ? '无记忆' : `回复含5678: ${text.slice(0, 40)}`)
}

// 9. 取消会话和管理会话
async function test9_cancelAndManage(): Promise<void> {
  const sid = await createSession()
  await prompt(sid, '请写一篇 500 字的文章')
  await wait(2000)
  const cancelV = await rpc('session.cancel', { sessionId: sid })
  const cancelOk = cancelV?.accepted === true
  record('9. 取消会话和管理会话', cancelOk, `cancel accepted=${cancelOk}`)
}

// 10. 查询当前所有会话
async function test10_listAllSessions(): Promise<void> {
  const v = await rpc('session.list')
  const ok = Array.isArray(v?.items)
  record('10. 查询所有会话', ok, `${v?.items?.length} sessions`)
}

// 11. 查询某个会话的问答记录（分页）
async function test11_historyPagination(): Promise<void> {
  const sid = await createSession()
  await prompt(sid, '你好')
  await wait(12000)
  const v = await rpc('session.history', { sessionId: sid })
  const ok = Array.isArray(v?.events) && v.events.length > 0
  record('11. 查询问答记录(分页)', ok, `${v?.events?.length} events`)
}

// 12. 创建 session 返回 sessionId
async function test12_createSession(): Promise<void> {
  const sid = await createSession()
  const ok = typeof sid === 'string' && sid.length > 0
  const list = await rpc('session.list')
  const found = list?.items?.some((s: any) => s.sessionId === sid)
  record('12. 创建session返回sessionId', ok && found, `sid=${sid?.slice(0, 8)}, in list=${found}`)
}

// 13. 根据 sessionId 查看 session 历史（验证事件结构）
async function test13_historyBySessionId(): Promise<void> {
  const sid = await createSession()
  await prompt(sid, '你好，请简短回复')
  await wait(12000)
  const v = await rpc('session.history', { sessionId: sid })
  const evs = v?.events || []
  const hasUser = evs.some(e => e.event?.type === 'user/message')
  const hasAssistant = evs.some(e => e.event?.type === 'assistant/message')
  const hasSurfaceOp = evs.some(e => e.event?.surfaceOp === 'append')
  const hasProjections = v?.projections?.values?.title !== undefined
  const ok = hasUser && hasAssistant && hasSurfaceOp && hasProjections
  record('13. 根据sessionId查看历史', ok, `user=${hasUser}, asst=${hasAssistant}, surfaceOp=${hasSurfaceOp}, proj=${hasProjections}, ${evs.length} events`)
}

// 14. 继续发送消息到已存在的 sessionId 继续对话
async function test14_continueConversation(): Promise<void> {
  const sid = await createSession()
  await prompt(sid, '请记住我的喜好是打篮球')
  await wait(12000)
  // 第二次发送到同一个 sessionId
  await prompt(sid, '我的喜好是什么？')
  await wait(12000)
  const evs = await getHistory(sid)
  const text = assistantText(evs)
  const remembers = text.includes('篮球')
  record('14. 继续发送消息到同一sessionId', remembers, remembers ? '记住了篮球' : `回复: ${text.slice(0, 40)}`)
}

// 15. 查看 session 列表（验证字段完整性）
async function test15_sessionListFields(): Promise<void> {
  const sid = await createSession()
  await prompt(sid, '测试列表')
  await wait(10000)
  const list = await rpc('session.list')
  const me = list?.items?.find((s: any) => s.sessionId === sid)
  const hasFields = me
    && typeof me.title === 'string' && me.title.length > 0
    && typeof me.updatedAt === 'number'
    && typeof me.running === 'boolean'
    && typeof me.blank === 'boolean'
    && typeof me.sessionId === 'string'
  record('15. 查看session列表(字段完整性)', !!hasFields, hasFields ? `title="${me.title}", blank=${me.blank}, running=${me.running}` : 'missing fields')
}

// 16. 工具调用验证（agent 使用 read 等工具）
async function test16_toolCall(): Promise<void> {
  const sid = await createSession()
  await prompt(sid, '请用 read 工具读取当前目录的 README.md 文件')
  await wait(15000)
  const evs = await getHistory(sid)
  const hasToolCall = evs.some(e => e.event?.type === 'tool/call')
  const hasToolResult = evs.some(e => e.event?.type === 'tool/result')
  // 验证 tool/result 的 content block 类型是 'tool-result'
  const toolResultEvs = evs.filter(e => e.event?.type === 'tool/result')
  const hasCorrectBlockType = toolResultEvs.some(e => {
    const blocks = e.event?.data?.message?.content || []
    return blocks.some((b: any) => b.type === 'tool-result')
  })
  const ok = hasToolCall && hasToolResult && hasCorrectBlockType
  record('16. 工具调用验证', ok, `tool/call=${hasToolCall}, tool/result=${hasToolResult}, correctBlockType=${hasCorrectBlockType}`)
}

// ---- main ----

async function main(): Promise<void> {
  console.log('\n=== E2E 端到端测试 ===\n')
  const tests = [
    test1_basicResponse, test2_streamRead, test3_reasoningComplete,
    test4_multiAgent, test5_websocket, test6_multiTurnMemory,
    test7_forkWithMemory, test8_forkWithoutMemory, test9_cancelAndManage,
    test10_listAllSessions, test11_historyPagination,
    test12_createSession, test13_historyBySessionId,
    test14_continueConversation, test15_sessionListFields,
    test16_toolCall,
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
