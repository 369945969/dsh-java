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
  const [showSettings, setShowSettings] = useState(false);
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
        <button
          className="app__settings-btn"
          onClick={() => setShowSettings(true)}
        >
          ⚙ 模型设置
        </button>
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
      {showSettings && (
        <SettingsModal onClose={() => setShowSettings(false)} />
      )}
    </div>
  );
}

/**
 * 模型设置弹窗 —— 添加/选择/删除自定义模型档案。
 * 对接 /api/config/models REST API；API Key 脱敏展示，绝不回传明文。
 */
function SettingsModal({ onClose }) {
  const [profiles, setProfiles] = useState([]);
  const [activeId, setActiveId] = useState('');
  const [form, setForm] = useState({ displayName: '', apiKey: '', baseUrl: '', model: '' });
  const [msg, setMsg] = useState('');

  async function load() {
    try {
      const resp = await fetch('/api/config/models');
      const data = await resp.json();
      setProfiles(data.profiles || []);
      setActiveId(data.activeId || '');
    } catch (e) {
      setMsg('加载失败：' + e.message);
    }
  }

  useEffect(() => { load(); }, []);

  async function addModel(e) {
    e.preventDefault();
    if (!form.model.trim()) { setMsg('模型名不能为空'); return; }
    try {
      const resp = await fetch('/api/config/models', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(form),
      });
      if (!resp.ok) throw new Error('HTTP ' + resp.status);
      setForm({ displayName: '', apiKey: '', baseUrl: '', model: '' });
      setMsg('已添加');
      await load();
    } catch (e) {
      setMsg('添加失败：' + e.message);
    }
  }

  async function selectActive(id) {
    try {
      await fetch('/api/config/models/active', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id }),
      });
      await load();
      setMsg('已切换为当前模型');
    } catch (e) {
      setMsg('切换失败：' + e.message);
    }
  }

  async function removeModel(id) {
    if (!confirm('删除该模型档案？')) return;
    try {
      await fetch('/api/config/models/' + id, { method: 'DELETE' });
      await load();
      setMsg('已删除');
    } catch (e) {
      setMsg('删除失败：' + e.message);
    }
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal__header">
          <span className="modal__title">模型设置</span>
          <button className="modal__close" onClick={onClose}>✕</button>
        </div>
        <div className="modal__body">
          <div className="modal__section-title">已保存模型</div>
          {profiles.length === 0 && (
            <div className="modal__empty">暂无模型，请在下方添加（也可用 export 环境变量配置）</div>
          )}
          {profiles.map((p) => (
            <div key={p.id} className={`model-card ${p.id === activeId ? 'model-card--active' : ''}`}>
              <div className="model-card__info">
                <div className="model-card__name">
                  {p.id === activeId && <span className="model-card__badge">当前</span>}
                  {p.displayName}
                </div>
                <div className="model-card__meta">
                  {p.model} · {p.baseUrl || '默认端点'} · {p.hasKey ? 'Key: ' + p.apiKeyMasked : '无 Key'}
                </div>
              </div>
              <div className="model-card__actions">
                {p.id !== activeId && (
                  <button className="btn btn--small" onClick={() => selectActive(p.id)}>设为当前</button>
                )}
                <button className="btn btn--small btn--danger" onClick={() => removeModel(p.id)}>删除</button>
              </div>
            </div>
          ))}

          <div className="modal__section-title">添加自定义模型</div>
          <form className="model-form" onSubmit={addModel}>
            <label className="model-form__label">
              显示名（可选）
              <input
                className="model-form__input"
                value={form.displayName}
                onChange={(e) => setForm({ ...form, displayName: e.target.value })}
                placeholder="如：阿里云 glm-5.2"
              />
            </label>
            <label className="model-form__label">
              模型名 *
              <input
                className="model-form__input"
                value={form.model}
                onChange={(e) => setForm({ ...form, model: e.target.value })}
                placeholder="如：glm-5.2 / deepseek-chat / qwen-plus"
              />
            </label>
            <label className="model-form__label">
              API Key
              <input
                className="model-form__input"
                type="password"
                value={form.apiKey}
                onChange={(e) => setForm({ ...form, apiKey: e.target.value })}
                placeholder="sk-..."
              />
            </label>
            <label className="model-form__label">
              端点（OpenAI 兼容，可选）
              <input
                className="model-form__input"
                value={form.baseUrl}
                onChange={(e) => setForm({ ...form, baseUrl: e.target.value })}
                placeholder="如 https://dashscope.aliyuncs.com/compatible-mode/v1"
              />
            </label>
            <button className="btn btn--primary" type="submit">添加模型</button>
          </form>
          {msg && <div className="modal__msg">{msg}</div>}
          <div className="modal__hint">
            提示：也可在 .env 用 export 配置（DEEPSEEK_API_KEY / DSH_BASE_URL / DSH_MODEL），
            页面配置覆盖环境变量，两者任一有效即可。API Key 仅存本地，绝不提交。
          </div>
        </div>
      </div>
    </div>
  );
}
