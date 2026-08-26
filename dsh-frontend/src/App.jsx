import { useState, useRef, useEffect } from 'react';

/**
 * DeepSeek Harness Web UI —— 对接 Java 后端 REST API。
 * 保留原 Harness 的对话式布局与 --dsw-* 设计令牌风格。
 */
export default function App() {
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [sessionId, setSessionId] = useState(null);
  const [loading, setLoading] = useState(false);
  const [tokenCount, setTokenCount] = useState(0);
  const [tools] = useState([
    'bash', 'read', 'write', 'edit', 'glob', 'grep',
  ]);
  const conversationRef = useRef(null);

  useEffect(() => {
    if (conversationRef.current) {
      conversationRef.current.scrollTop = conversationRef.current.scrollHeight;
    }
  }, [messages, loading]);

  async function send() {
    if (!input.trim() || loading) return;
    const text = input.trim();
    setInput('');
    setMessages((prev) => [...prev, { role: 'user', content: text }]);
    setLoading(true);
    try {
      const resp = await fetch('/api/agent/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId, message: text }),
      });
      const data = await resp.json();
      if (!sessionId) setSessionId(data.sessionId);
      setTokenCount(data.totalTokens);
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', content: data.reply },
      ]);
    } catch (e) {
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', content: '（请求失败：' + e.message + '）' },
      ]);
    } finally {
      setLoading(false);
    }
  }

  function onKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      send();
    }
  }

  return (
    <div className="app">
      <header className="app__header">
        <span className="app__logo">DeepSeek Harness</span>
        <span className="app__status">Java 后端 · React 前端</span>
      </header>
      <div className="main">
        <aside className="sidebar">
          <div className="sidebar__section">
            <div className="sidebar__title">可用工具</div>
            {tools.map((t) => (
              <div key={t} className="sidebar__item">⚡ {t}</div>
            ))}
          </div>
          <div className="sidebar__section">
            <div className="sidebar__title">Token 用量</div>
            <div className="sidebar__item">{tokenCount.toLocaleString()} tokens</div>
          </div>
          <div className="sidebar__section">
            <div className="sidebar__title">会话</div>
            <div className="sidebar__item">
              {sessionId ? sessionId.slice(0, 8) + '…' : '未开始'}
            </div>
          </div>
        </aside>
        <div className="conversation" ref={conversationRef}>
          {messages.length === 0 && (
            <div className="message message--assistant">
              <div className="message__role">assistant</div>
              你好！我是 DeepSeek Harness，一个软件工程助手。
              可以使用 bash、文件读写等工具完成编程任务，请输入你的需求。
            </div>
          )}
          {messages.map((m, i) => (
            <div
              key={i}
              className={`message message--${m.role}`}
            >
              <div className="message__role">{m.role}</div>
              {m.content}
            </div>
          ))}
          {loading && (
            <div className="message message--assistant">
              <div className="message__role">assistant</div>
              <em>思考中…</em>
            </div>
          )}
        </div>
      </div>
      <div className="input-bar">
        <input
          className="input-bar__field"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={onKeyDown}
          placeholder="输入消息，Enter 发送，Shift+Enter 换行"
          disabled={loading}
        />
        <button
          className="input-bar__send"
          onClick={send}
          disabled={loading || !input.trim()}
        >
          发送
        </button>
      </div>
    </div>
  );
}
