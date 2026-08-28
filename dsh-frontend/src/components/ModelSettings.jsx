import { useEffect, useState } from 'react';
import modalCss from './Modal.module.css';
import { IconClose } from './Icons';
import { fetchModels, addModel, setActiveModel, deleteModel } from '../lib/api.js';

const emptyForm = { displayName: '', apiKey: '', baseUrl: '', model: '' };

/** 模型设置弹窗（对齐原 Harness Modal 原语）：管理多个模型档案。
 *  对接 /api/config/models REST CRUD；API Key 脱敏展示。 */
export function ModelSettings({ onClose }) {
  const [profiles, setProfiles] = useState([]);
  const [activeId, setActiveId] = useState('');
  const [form, setForm] = useState(emptyForm);
  const [msg, setMsg] = useState('');

  const load = async () => {
    try {
      const data = await fetchModels();
      setProfiles(data.profiles || []);
      setActiveId(data.activeId || '');
    } catch (e) {
      setMsg('加载失败：' + e.message);
    }
  };

  useEffect(() => { load(); }, []);

  const onAdd = async (e) => {
    e.preventDefault();
    if (!form.model.trim()) { setMsg('模型名不能为空'); return; }
    try {
      await addModel(form);
      setForm(emptyForm);
      setMsg('已添加');
      await load();
    } catch (e) { setMsg('添加失败：' + e.message); }
  };

  const onSelect = async (id) => {
    try {
      await setActiveModel(id);
      await load();
      setMsg('已切换为当前模型');
    } catch (e) { setMsg('切换失败：' + e.message); }
  };

  const onDelete = async (id) => {
    if (!confirm('删除该模型档案？')) return;
    try {
      await deleteModel(id);
      await load();
      setMsg('已删除');
    } catch (e) { setMsg('删除失败：' + e.message); }
  };

  return (
    <div className={modalCss.root} onClick={onClose}>
      <div className={modalCss.mask} />
      <div
        className={modalCss.dialog}
        style={{ width: 'min(560px, 100%)' }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className={modalCss.header}>
          <h2 className={modalCss.title}>模型设置</h2>
          <button type="button" className={modalCss.close} onClick={onClose} aria-label="关闭">
            <IconClose />
          </button>
        </div>
        <div className={modalCss.body} style={{ gap: 16 }}>
          <SectionTitle>已保存模型</SectionTitle>
          {profiles.length === 0 && (
            <div style={emptyStyle}>暂无模型，请在下方添加（也可用环境变量配置）</div>
          )}
          {profiles.map((p) => (
            <div key={p.id} style={p.id === activeId ? activeCardStyle : cardStyle}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 14, fontWeight: 600 }}>
                  {p.id === activeId && <Badge>当前</Badge>}
                  {p.displayName || p.model}
                </div>
                <div style={metaStyle}>
                  {p.model}{p.baseUrl ? ` · ${p.baseUrl}` : ''} · {p.hasKey ? `Key: ${p.apiKeyMasked}` : '无 Key'}
                </div>
              </div>
              <div style={{ display: 'flex', gap: 6, flexShrink: 0 }}>
                {p.id !== activeId && (
                  <button style={smallBtnStyle} onClick={() => onSelect(p.id)}>设为当前</button>
                )}
                <button style={dangerBtnStyle} onClick={() => onDelete(p.id)}>删除</button>
              </div>
            </div>
          ))}

          <SectionTitle>添加自定义模型</SectionTitle>
          <form onSubmit={onAdd} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <Field label="显示名（可选）" value={form.displayName}
              onChange={(v) => setForm({ ...form, displayName: v })} placeholder="如：阿里云 glm-5.2" />
            <Field label="模型名 *" value={form.model}
              onChange={(v) => setForm({ ...form, model: v })} placeholder="如：glm-5.2 / deepseek-chat / qwen-plus" />
            <Field label="API Key" type="password" value={form.apiKey}
              onChange={(v) => setForm({ ...form, apiKey: v })} placeholder="sk-..." />
            <Field label="端点（OpenAI 兼容，可选）" value={form.baseUrl}
              onChange={(v) => setForm({ ...form, baseUrl: v })}
              placeholder="如 https://dashscope.aliyuncs.com/compatible-mode/v1" />
            <button type="submit" style={primaryBtnStyle}>添加模型</button>
          </form>
          {msg && <div style={msgStyle}>{msg}</div>}
          <div style={hintStyle}>
            提示：也可在 .env 用环境变量配置（DEEPSEEK_API_KEY / DSH_BASE_URL / DSH_MODEL），
            页面配置覆盖环境变量，两者任一有效即可。API Key 仅存本地，绝不提交。
          </div>
        </div>
      </div>
    </div>
  );
}

function SectionTitle({ children }) {
  return <div style={{ fontSize: 12, color: 'var(--dsw-alias-label-caption)', textTransform: 'uppercase', letterSpacing: 0.5 }}>{children}</div>;
}
function Field({ label, value, onChange, type = 'text', placeholder }) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, color: 'var(--dsw-alias-label-secondary)' }}>
      {label}
      <input
        type={type} value={value} placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
        style={{
          padding: '10px 12px', background: 'var(--dsw-alias-bg-base)',
          border: '1px solid var(--dsw-alias-border-l2)', borderRadius: 8,
          color: 'var(--dsw-alias-label-primary)', fontSize: 14, outline: 'none', fontFamily: 'inherit',
        }}
      />
    </label>
  );
}
function Badge({ children }) {
  return <span style={{ fontSize: 10, padding: '2px 6px', background: 'var(--dsw-alias-state-business-primary)', color: '#fff', borderRadius: 4, textTransform: 'uppercase' }}>{children}</span>;
}

const cardStyle = { display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '12px 14px', background: 'var(--dsw-alias-bg-base)', border: '1px solid var(--dsw-alias-border-l2)', borderRadius: 8, gap: 12 };
const activeCardStyle = { ...cardStyle, borderColor: 'var(--dsw-alias-state-business-primary)', background: 'var(--dsw-alias-state-business-tertiary)' };
const metaStyle = { fontSize: 12, color: 'var(--dsw-alias-label-secondary)', marginTop: 4, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' };
const smallBtnStyle = { padding: '5px 10px', fontSize: 12, background: 'var(--dsw-alias-button-ghost-active-fill)', color: 'var(--dsw-alias-label-primary)', border: 'none', borderRadius: 8, cursor: 'pointer', fontFamily: 'inherit' };
const dangerBtnStyle = { ...smallBtnStyle, background: 'var(--dsw-alias-interactive-bg-hover-danger)', color: 'var(--dsw-alias-state-error-primary)' };
const primaryBtnStyle = { padding: '10px 16px', background: 'var(--dsw-alias-button-info-fill)', color: '#fff', border: 'none', borderRadius: 8, fontSize: 14, cursor: 'pointer', alignSelf: 'flex-start', fontFamily: 'inherit' };
const msgStyle = { fontSize: 13, color: 'var(--dsw-alias-state-business-primary)', padding: '8px 12px', background: 'var(--dsw-alias-state-business-tertiary)', borderRadius: 8 };
const emptyStyle = { fontSize: 13, color: 'var(--dsw-alias-label-tertiary)', padding: 12, background: 'var(--dsw-alias-bg-base)', borderRadius: 8, border: '1px dashed var(--dsw-alias-border-l2)' };
const hintStyle = { fontSize: 11, color: 'var(--dsw-alias-label-tertiary)', lineHeight: 1.6, paddingTop: 8, borderTop: '1px solid var(--dsw-alias-border-l2)' };
