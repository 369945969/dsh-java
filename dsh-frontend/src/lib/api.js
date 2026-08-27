// Java 后端 REST + SSE 客户端 —— 对接 dsh-web 的 AgentController / AgentStreamController / ConfigController。

/** 流式对话：POST /api/agent/stream，解析 SSE 事件流（session → delta* → done）。 */
export async function streamMessage({ sessionId, message, onSession, onDelta, onDone, onError, signal }) {
  let resp;
  try {
    resp = await fetch('/api/agent/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ sessionId, message }),
      signal,
    });
  } catch (e) {
    if (e.name === 'AbortError') return;
    onError?.(e.message);
    return;
  }
  if (!resp.ok || !resp.body) {
    const text = await resp.text().catch(() => '');
    onError?.(`HTTP ${resp.status} ${text}`);
    return;
  }

  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  let eventName = 'message';
  let dataLines = [];

  const dispatch = () => {
    if (dataLines.length === 0) return;
    const data = dataLines.join('\n');
    dataLines = [];
    const ev = eventName;
    eventName = 'message';
    if (ev === 'session') onSession?.(data);
    else if (ev === 'delta') onDelta?.(data);
    else if (ev === 'done') onDone?.(data);
    else if (ev === 'error') onError?.(data);
  };

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf('\n')) >= 0) {
        let line = buf.slice(0, idx);
        buf = buf.slice(idx + 1);
        // 去除行尾 \r
        if (line.endsWith('\r')) line = line.slice(0, -1);
        if (line === '') {
          dispatch();
          continue;
        }
        if (line.startsWith(':')) continue; // SSE 注释
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          dataLines.push(line.slice(5).replace(/^ /, ''));
        }
      }
    }
    dispatch(); // flush 末尾
    onDone?.('[DONE]');
  } catch (e) {
    if (e.name === 'AbortError') return;
    onError?.(e.message);
  }
}

/** 非流式发送（兜底，返回完整历史与 token 统计）。 */
export async function sendMessage({ sessionId, message }) {
  const resp = await fetch('/api/agent/send', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, message }),
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  return resp.json();
}

export async function fetchSessions() {
  const resp = await fetch('/api/agent/sessions');
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  return resp.json();
}

export async function fetchHistory(sessionId) {
  const resp = await fetch(`/api/agent/sessions/${encodeURIComponent(sessionId)}/messages`);
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  return resp.json();
}

export async function fetchModels() {
  const resp = await fetch('/api/config/models');
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  return resp.json();
}

export async function addModel(form) {
  const resp = await fetch('/api/config/models', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(form),
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  return resp.json();
}

export async function setActiveModel(id) {
  const resp = await fetch('/api/config/models/active', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id }),
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  return resp.json();
}

export async function deleteModel(id) {
  const resp = await fetch(`/api/config/models/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  return resp.json();
}

export async function updateModel(id, form) {
  const resp = await fetch(`/api/config/models/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(form),
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  return resp.json();
}
