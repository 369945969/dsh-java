/**
 * WebSocket E2E tests (TypeScript) - mirrors RPC test groupings, covers all WS scenarios.
 *
 * Run:   node testcase/ws-e2e.ts
 *   or:  testcase\ws-e2e.bat
 *
 * Deps:  Node 24+ (built-in WebSocket + TS strip-types), no extra npm packages.
 *        Backend running on localhost:8765 + DSH_TOKEN env var (launch token).
 *
 * Protocol (AgentWebSocketHandler, /ws/agent):
 *   C->S: {"action":"prompt","sessionId":"s1","message":"..."}
 *         {"action":"cancel","sessionId":"s1"}
 *   S->C: {"event":"session|delta|done|cancelled|error","sessionId":"s1","data":"..."}
 *
 * All chat/memory tests go through session.create (bound to a workspace named
 * with the current date YYYYMMDDHH) so that memory works and sessions are
 * visible in the web UI.
 */
import { join } from 'node:path'

const PORT = process.env.DSH_PORT || '8765'
const API = `http://localhost:${PORT}`
const WS_URL = `ws://localhost:${PORT}/ws/agent`

interface WsFrame {
  event: string
  sessionId: string
  data?: string
}

interface TestResult { name: string; pass: boolean; detail: string }
const results: TestResult[] = []

// ---- auth (token -> cookie exchange, same handshake as the web UI) ----

let COOKIE = ''

async function getAuthCookie(): Promise<string> {
  if (COOKIE) return COOKIE
  const token = process.env.DSH_TOKEN
  if (!token) throw new Error('DSH_TOKEN env var required (launch token from server log)')
  const res = await fetch(`${API}/?token=${token}`, { redirect: 'manual' })
  const sc = res.headers.get('set-cookie')
  if (!sc) throw new Error('no set-cookie in token exchange')
  COOKIE = sc.split(';')[0]
  return COOKIE
}

// ---- HTTP helpers (REST, 0.1.2 two-segment + legacy) ----

async function rpc(channel: string, endpoint: string, args: any = {}): Promise<any> {
  const res = await fetch(`${API}/api/${channel}/${endpoint}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', cookie: COOKIE },
    body: JSON.stringify({
      type: 'client-request',
      rpcId: 'ws-e2e-' + Math.random().toString(36).slice(2, 8),
      method: endpoint,
      payload: { args },
    }),
  })
  const json: any = await res.json()
  return json.result?.value
}

async function httpPost(method: string, payload: any = {}): Promise<any> {
  const res = await fetch(`${API}/api/${method}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', cookie: COOKIE },
    body: JSON.stringify({ rpcId: 'ws-e2e-' + Math.random().toString(36).slice(2, 8), payload }),
  })
  return await res.json()
}

// ---- session setup (no workspace, same as e2e.ts / RpcE2e) ----

async function createSession(): Promise<string> {
  const v = await rpc('session', 'create', {})
  const sid = v?.sessionId
  if (!sid) throw new Error(`session.create failed, response: ${JSON.stringify(v)}`)
  return sid
}

// ---- WebSocket helpers ----

function wsConnect(): WebSocket {
  return new WebSocket(WS_URL, { headers: { cookie: COOKIE } } as any)
}

async function wsPrompt(sid: string, message: string): Promise<WsFrame[]> {
  const ws = wsConnect()
  const frames: WsFrame[] = []
  const terminals = new Set(['done', 'cancelled', 'error'])
  return new Promise<WsFrame[]>((resolve) => {
    const timer = setTimeout(() => { ws.close(); resolve(frames) }, 120_000)
    ws.onopen = () => {
      ws.send(JSON.stringify({ action: 'prompt', sessionId: sid, message }))
    }
    ws.onmessage = (e: MessageEvent) => {
      const f: WsFrame = JSON.parse(typeof e.data === 'string' ? e.data : String(e.data))
      frames.push(f)
      if (f.sessionId === sid && terminals.has(f.event)) {
        clearTimeout(timer)
        ws.close()
        resolve(frames)
      }
    }
    ws.onerror = () => { clearTimeout(timer); resolve(frames) }
    ws.onclose = () => { clearTimeout(timer); resolve(frames) }
  })
}

function fullReply(frames: WsFrame[]): string {
  return frames.filter(f => f.event === 'delta').map(f => f.data || '').join('')
}

// ---- test recording ----

function record(name: string, pass: boolean, detail: string): void {
  results.push({ name, pass, detail })
  console.log(`  ${pass ? '[PASS]' : '[FAIL]'}  ${name} - ${detail}`)
}

function group(name: string): void {
  console.log(`\n  -- ${name} --`)
}

// ============================================================
// Basic chat mode (WebSocket)
// ============================================================

async function testBasicGreeting(): Promise<void> {
  const sid = await createSession()
  const frames = await wsPrompt(sid, 'Hello, introduce yourself in one sentence.')
  const events = new Set(frames.map(f => f.event))
  const hasSession = events.has('session')
  const hasDone = events.has('done')
  const hasDelta = events.has('delta')
  record('basic greeting (session->delta*->done)',
    hasSession && hasDone && hasDelta,
    `events=${[...events].join(',')}`)
}

async function testFullResponse(): Promise<void> {
  const sid = await createSession()
  const frames = await wsPrompt(sid, 'Introduce Java in one sentence.')
  const reply = fullReply(frames)
  record('full response (delta concatenation)',
    reply.length > 5,
    `reply: ${reply.slice(0, 80)}...`)
}

async function testStreamingDelta(): Promise<void> {
  const sid = await createSession()
  const frames = await wsPrompt(sid, 'Describe Python in three sentences.')
  const deltas = frames.filter(f => f.event === 'delta')
  const totalText = fullReply(frames)
  record('streaming incremental (multiple delta frames)',
    deltas.length >= 1 && totalText.length > 10,
    `${deltas.length} delta frames, ${totalText.length} chars`)
}

// ============================================================
// Session & memory mode (WebSocket + HTTP auxiliary)
// ============================================================

async function testMultiTurnMemory(): Promise<void> {
  const sid = await createSession()
  await wsPrompt(sid, 'Please remember my name is Alice and my favorite color is blue.')
  const frames2 = await wsPrompt(sid, 'What is my name? What is my favorite color?')
  const reply = fullReply(frames2)
  const remembers = reply.toLowerCase().includes('alice') && reply.toLowerCase().includes('blue')
  record('multi-turn memory (same session)',
    remembers,
    remembers ? 'remembered Alice+blue' : `reply: ${reply.slice(0, 80)}`)
}

async function testDifferentSessionNoMemory(): Promise<void> {
  const sid1 = await createSession()
  const sid2 = await createSession()
  await wsPrompt(sid1, 'Please remember my password is xyz789')
  const frames2 = await wsPrompt(sid2, 'What is my password?')
  const reply = fullReply(frames2)
  const noLeak = !reply.includes('xyz789')
  record('different session no memory',
    noLeak,
    noLeak ? 'sid2 does not know sid1 password' : `leaked! reply: ${reply.slice(0, 60)}`)
}

async function testSessionListAfterChat(): Promise<void> {
  const sid = await createSession()
  await wsPrompt(sid, 'test')
  const v = await rpc('session', 'list')
  const items = v?.items || []
  const found = items.some((s: any) => s.sessionId === sid)
  record('session.list contains chatted session',
    found,
    found ? `${items.length} sessions, found ${sid.slice(0, 8)}...` : `${items.length} sessions, not found`)
}

// ============================================================
// Real-time communication mode (WebSocket)
// ============================================================

async function testConcurrentSessions(): Promise<void> {
  const sidA = await createSession()
  const sidB = await createSession()
  const ws = wsConnect()
  const allFrames: WsFrame[] = []
  const terms = new Set(['done', 'cancelled', 'error'])
  await new Promise<void>((resolve) => {
    ws.onopen = () => {
      ws.send(JSON.stringify({ action: 'prompt', sessionId: sidA, message: 'Hello, introduce yourself in one sentence.' }))
      ws.send(JSON.stringify({ action: 'prompt', sessionId: sidB, message: 'Introduce TypeScript in one sentence.' }))
    }
    const timer = setTimeout(() => { ws.close(); resolve() }, 120_000)
    ws.onmessage = (e: MessageEvent) => {
      const f: WsFrame = JSON.parse(typeof e.data === 'string' ? e.data : String(e.data))
      allFrames.push(f)
      const aDone = allFrames.some(x => x.sessionId === sidA && terms.has(x.event))
      const bDone = allFrames.some(x => x.sessionId === sidB && terms.has(x.event))
      if (aDone && bDone) { clearTimeout(timer); ws.close(); resolve() }
    }
    ws.onerror = () => { clearTimeout(timer); resolve() }
    ws.onclose = () => { clearTimeout(timer); resolve() }
  })
  const ea = new Set(allFrames.filter(f => f.sessionId === sidA).map(f => f.event))
  const eb = new Set(allFrames.filter(f => f.sessionId === sidB).map(f => f.event))
  const ok = ['session', 'delta', 'done'].every(e => ea.has(e)) &&
             ['session', 'delta', 'done'].every(e => eb.has(e))
  record('concurrent multi-session + streaming',
    ok,
    `a=${[...ea].join(',')} b=${[...eb].join(',')}`)
}

async function testCancel(): Promise<void> {
  const sid = await createSession()
  const ws = wsConnect()
  const frames: WsFrame[] = []
  await new Promise<void>((resolve) => {
    ws.onopen = () => {
      ws.send(JSON.stringify({ action: 'prompt', sessionId: sid, message: 'Please write a 1200-word essay.' }))
      setTimeout(() => ws.send(JSON.stringify({ action: 'cancel', sessionId: sid })), 300)
    }
    const timer = setTimeout(() => { ws.close(); resolve() }, 60_000)
    ws.onmessage = (e: MessageEvent) => {
      const f: WsFrame = JSON.parse(typeof e.data === 'string' ? e.data : String(e.data))
      frames.push(f)
      if (f.sessionId === sid && (f.event === 'cancelled' || f.event === 'done' || f.event === 'error')) {
        clearTimeout(timer); ws.close(); resolve()
      }
    }
    ws.onerror = () => { clearTimeout(timer); resolve() }
    ws.onclose = () => { clearTimeout(timer); resolve() }
  })
  const events = new Set(frames.filter(f => f.sessionId === sid).map(f => f.event))
  record('session cancel',
    events.has('cancelled'),
    events.has('cancelled') ? 'received cancelled' : `events=${[...events].join(',')}`)
}

async function testEmptySessionId(): Promise<void> {
  const ws = wsConnect()
  const frames: WsFrame[] = []
  await new Promise<void>((resolve) => {
    ws.onopen = () => {
      ws.send(JSON.stringify({ action: 'prompt', sessionId: '', message: 'hi' }))
    }
    const timer = setTimeout(() => { ws.close(); resolve() }, 10_000)
    ws.onmessage = (e: MessageEvent) => {
      const f: WsFrame = JSON.parse(typeof e.data === 'string' ? e.data : String(e.data))
      frames.push(f)
      if (f.event === 'error') { clearTimeout(timer); ws.close(); resolve() }
    }
    ws.onerror = () => { clearTimeout(timer); resolve() }
    ws.onclose = () => { clearTimeout(timer); resolve() }
  })
  const hasError = frames.some(f => f.event === 'error')
  record('empty sessionId -> error',
    hasError,
    hasError ? `error: ${frames.find(f => f.event === 'error')?.data}` : 'no error received')
}

async function testUnknownAction(): Promise<void> {
  const sid = await createSession()
  const ws = wsConnect()
  const frames: WsFrame[] = []
  await new Promise<void>((resolve) => {
    ws.onopen = () => {
      ws.send(JSON.stringify({ action: 'history', sessionId: sid }))
    }
    const timer = setTimeout(() => { ws.close(); resolve() }, 10_000)
    ws.onmessage = (e: MessageEvent) => {
      const f: WsFrame = JSON.parse(typeof e.data === 'string' ? e.data : String(e.data))
      frames.push(f)
      if (f.event === 'error') { clearTimeout(timer); ws.close(); resolve() }
    }
    ws.onerror = () => { clearTimeout(timer); resolve() }
    ws.onclose = () => { clearTimeout(timer); resolve() }
  })
  const errFrame = frames.find(f => f.event === 'error')
  const hasUnknown = !!errFrame?.data
  record('unknown action -> error',
    hasUnknown,
    hasUnknown ? `error: ${errFrame?.data}` : `frames=${JSON.stringify(frames)}`)
}

async function testSkillList(): Promise<void> {
  const json = await httpPost('skill.list')
  const skills = json.result?.value?.skills
  const ok = Array.isArray(skills)
  record('skill.list returns skill list',
    ok,
    ok ? `${skills.length} skills` : 'skills field abnormal')
}

// ============================================================
// main
// ============================================================

async function runTest(name: string, fn: () => Promise<void>): Promise<void> {
  try { await fn() } catch (e: any) {
    record(name, false, `EXCEPTION: ${e.message}`)
  }
}

async function main(): Promise<void> {
  console.log(`\n[ws-e2e] target: ${WS_URL}`)
  await getAuthCookie()
  console.log(`[ws-e2e] cookie: ${COOKIE.slice(0, 40)}...`)
  console.log('\n=== WebSocket E2E tests ===')

  group('basic chat mode')
  await runTest('basic greeting', testBasicGreeting)
  await runTest('full response', testFullResponse)
  await runTest('streaming incremental', testStreamingDelta)

  group('session & memory mode')
  await runTest('multi-turn memory', testMultiTurnMemory)
  await runTest('different session no memory', testDifferentSessionNoMemory)
  await runTest('session.list after chat', testSessionListAfterChat)

  group('real-time communication mode')
  await runTest('concurrent multi-session', testConcurrentSessions)
  await runTest('session cancel', testCancel)
  await runTest('empty sessionId -> error', testEmptySessionId)
  await runTest('unknown action -> error', testUnknownAction)

  group('HTTP auxiliary')
  await runTest('skill.list', testSkillList)

  const passed = results.filter(r => r.pass).length
  const total = results.length
  console.log(`\n=== result: ${passed}/${total} passed ===\n`)
  process.exit(passed === total ? 0 : 1)
}

main()
