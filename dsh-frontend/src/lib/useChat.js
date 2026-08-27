import { useCallback, useEffect, useRef, useState } from 'react';
import { streamMessage, fetchSessions, fetchHistory } from './api.js';

let seq = 0;

/**
 * 对话状态钩子 —— 管理 messages、流式发送、会话切换、token 统计。
 * 数据源：POST /api/agent/stream（SSE：session → delta* → done）。
 */
export function useChat() {
  const [sessionId, setSessionId] = useState(null);
  const [messages, setMessages] = useState([]);
  const [running, setRunning] = useState(false);
  const [totalTokens, setTotalTokens] = useState(0);
  const [sessions, setSessions] = useState([]);
  const abortRef = useRef(null);

  const refreshSessions = useCallback(async () => {
    try {
      const data = await fetchSessions();
      setSessions(data.sessions || []);
      if (typeof data.totalTokens === 'number') setTotalTokens(data.totalTokens);
    } catch {
      /* 静默：端点可能未就绪 */
    }
  }, []);

  useEffect(() => { refreshSessions(); }, [refreshSessions]);

  const selectSession = useCallback(async (id) => {
    if (abortRef.current) abortRef.current.abort();
    setSessionId(id);
    setRunning(false);
    try {
      const data = await fetchHistory(id);
      setMessages((data.messages || []).map((m) => ({
        id: ++seq, role: m.role?.toLowerCase() || m.role, content: m.content || '',
      })));
      if (typeof data.totalTokens === 'number') setTotalTokens(data.totalTokens);
    } catch {
      setMessages([]);
    }
  }, []);

  const newSession = useCallback(() => {
    if (abortRef.current) abortRef.current.abort();
    setSessionId(null);
    setMessages([]);
    setRunning(false);
  }, []);

  const send = useCallback((text) => {
    if (!text.trim() || running) return;
    const ac = new AbortController();
    abortRef.current = ac;

    setMessages((prev) => [...prev, { id: ++seq, role: 'user', content: text }]);
    const assistantId = ++seq;
    setMessages((prev) => [...prev, { id: assistantId, role: 'assistant', content: '', streaming: true }]);
    setRunning(true);

    streamMessage({
      sessionId, message: text, signal: ac.signal,
      onSession: (id) => { if (!sessionId) setSessionId(id); },
      onDelta: (chunk) => {
        setMessages((prev) => prev.map((m) =>
          m.id === assistantId ? { ...m, content: m.content + chunk } : m));
      },
      onDone: () => {
        setMessages((prev) => prev.map((m) =>
          m.id === assistantId ? { ...m, streaming: false } : m));
        setRunning(false);
        refreshSessions();
      },
      onError: (msg) => {
        setMessages((prev) => prev.map((m) =>
          m.id === assistantId
            ? { ...m, content: m.content || ('（请求失败：' + msg + '）'), streaming: false, error: true }
            : m));
        setRunning(false);
      },
    });
  }, [sessionId, running, refreshSessions]);

  const stop = useCallback(() => {
    if (abortRef.current) abortRef.current.abort();
    setRunning(false);
    setMessages((prev) => prev.map((m) => m.streaming ? { ...m, streaming: false, stopped: true } : m));
  }, []);

  return { sessionId, messages, running, totalTokens, sessions, send, stop, selectSession, newSession, refreshSessions };
}
