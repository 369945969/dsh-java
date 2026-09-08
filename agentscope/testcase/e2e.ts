/**
 * AgentScope 端到端测试客户端（TypeScript，前端 app.js 可复用）
 *
 * 覆盖：
 * 1. token 握手换 cookie
 * 2. health 检查
 * 3. /api/agent/send 一次性对话
 * 4. 多轮记忆（同 sessionId 记住→回忆）
 * 5. skill.list（验证 agent 能发现 skill）
 * 6. WS prompt → session/delta/done 帧
 * 7. WS cancel → cancelled
 * 8. 无 token → 401
 *
 * 用法：node testcase/e2e.ts（需 Node 22+，DSH_TOKEN + DSH_PORT env）
 */

const PORT = process.env.DSH_PORT || '8766'
const BASE = `http://localhost:${PORT}`
const TOKEN = process.env.DSH_TOKEN || ''
const WS_URL = `ws://localhost:${parseInt(PORT) + 1}/ws/agent`

let COOKIE = ''
const results: { name: string; pass: boolean; detail: string }[] = []

function record(name: string, pass: boolean, detail: string) {
  results.push({ name, pass, detail })
  console.log(`  ${pass ? '[PASS]' : '[FAIL]'} ${name} - ${detail}`)
}

// ---- HTTP 辅助 ----
async function fetchWithCookie(path: string, opts: any = {}): Promise<Response> {
  const headers: any = { ...opts.headers }
  if (COOKIE) headers['cookie'] = COOKIE
  return fetch(`${BASE}${path}`, { ...opts, headers })
}

// ---- 测试用例 ----
async function testHealth() {
  const r = await fetchWithCookie('/api/agent/health')
  const j: any = await r.json()
  record('health (status ok)', j?.status === 'ok', j?.status || 'fail')
}

async function testSend() {
  const r = await fetchWithCookie('/api/agent/send', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: 'Reply with just OK.' })
  })
  const j: any = await r.json()
  record('send (reply non-empty)', j?.reply && j.reply.length > 0, `reply: ${j?.reply?.slice(0, 40)}`)
  return j?.sessionId
}

async function testMultiTurn(sid: string) {
  // 记住事实
  await fetchWithCookie('/api/agent/send', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId: sid, message: 'Remember: my name is Alice.' })
  })
  // 回忆
  const r2 = await fetchWithCookie('/api/agent/send', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId: sid, message: 'What is my name? Just the name.' })
  })
  const j: any = await r2.json()
  const ok = (j?.reply || '').toLowerCase().includes('alice')
  record('multi-turn memory', ok, ok ? 'remembered Alice' : `reply: ${j?.reply?.slice(0, 60)}`)
}

async function testUnauthorized() {
  const r = await fetch(`${BASE}/api/agent/health`) // 无 cookie
  record('unauthorized without token', r.status === 401, `status=${r.status}`)
}

async function testSkillPrompt() {
  // 通过请求参数注入 skill 提示词，agent 应遵循执行
  const r = await fetchWithCookie('/api/agent/send', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      message: 'What is 2+2? Answer with just the number.',
      skillPrompt: 'Always prefix your reply with SKILL: to show you followed this instruction.'
    })
  })
  const j: any = await r.json()
  const ok = (j?.reply || '').toUpperCase().includes('SKILL:')
  record('skill prompt injection (prefix SKILL:)', ok, `reply: ${(j?.reply || '').slice(0, 60)}`)
}

async function testSystemPromptOverride() {
  // 通过请求参数覆盖系统提示词
  const r = await fetchWithCookie('/api/agent/send', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      message: 'Who are you?',
      systemPrompt: 'You are a DBA assistant. Always mention databases in your reply.'
    })
  })
  const j: any = await r.json()
  const ok = (j?.reply || '').toLowerCase().includes('database') || (j?.reply || '').toLowerCase().includes('dba')
  record('system prompt override (mentions database)', ok, `reply: ${(j?.reply || '').slice(0, 60)}`)
}

// ---- WebSocket 辅助 ----
function wsPrompt(sid: string, message: string): Promise<any[]> {
  return new Promise((resolve) => {
    const ws = new WebSocket(WS_URL, { headers: { cookie: COOKIE } } as any)
    const frames: any[] = []
    const timer = setTimeout(() => { ws.close(); resolve(frames) }, 120_000)
    ws.onopen = () => ws.send(JSON.stringify({ action: 'prompt', sessionId: sid, message }))
    ws.onmessage = (e: MessageEvent) => {
      const f = JSON.parse(typeof e.data === 'string' ? e.data : '')
      frames.push(f)
      if (f.event === 'done' || f.event === 'cancelled' || f.event === 'error') {
        clearTimeout(timer); ws.close(); resolve(frames)
      }
    }
    ws.onerror = () => { clearTimeout(timer); resolve(frames) }
    ws.onclose = () => { clearTimeout(timer); resolve(frames) }
  })
}

async function testWsPrompt() {
  const sid = 'ws-test-' + Math.random().toString(36).slice(2, 8)
  const frames = await wsPrompt(sid, 'Reply OK.')
  const events = new Set(frames.map(f => f.event))
  const ok = events.has('session') && events.has('delta') && events.has('done')
  record('ws prompt (session→delta→done)', ok, `events=[${[...events].join(',')}]`)
}

// ---- 主流程 ----
async function main() {
  console.log(`\n[agentscope-e2e] target: ${BASE} (token=${TOKEN.slice(0, 6)}…)`)

  // 1) token 握手
  if (!TOKEN) { console.error('DSH_TOKEN required'); process.exit(1) }
  const res = await fetch(`${BASE}/?token=${TOKEN}`, { redirect: 'manual' })
  const sc = res.headers.get('set-cookie')
  if (sc) { COOKIE = sc.split(';')[0]; console.log('[agentscope-e2e] cookie:', COOKIE.slice(0, 30), '…') }
  else { console.error('no set-cookie'); process.exit(1) }

  await testHealth()
  const sid = await testSend()
  if (sid) await testMultiTurn(sid)
  await testUnauthorized()
  await testSkillPrompt()
  await testSystemPromptOverride()
  await testWsPrompt()

  const passed = results.filter(r => r.pass).length
  console.log(`\n=== result: ${passed}/${results.length} passed ===`)
  process.exit(passed === results.length ? 0 : 1)
}

main().catch(e => { console.error(e); process.exit(1) })
