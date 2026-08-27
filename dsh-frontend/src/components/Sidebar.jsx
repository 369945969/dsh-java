import css from './SidebarRoot.module.css';
import { BrandMark, IconPlus, IconSettings, IconPanel, IconSun, IconMoon } from './Icons';

/** 侧边栏（对齐原 Harness SidebarRoot）：品牌、新会话、会话列表、模型、设置、主题。 */
export function Sidebar({
  collapsed, onToggle, sessions, currentSessionId, onSelectSession, onNewSession,
  totalTokens, onOpenSettings, dark, onToggleTheme,
}) {
  const fmt = (n) => Number(n || 0).toLocaleString();
  return (
    <div className={`${css.root} ${collapsed ? css.collapsed : ''}`}>
      <div className={css.logoRow}>
        {!collapsed && (
          <button type="button" className={css.brand} onClick={onNewSession} title="新会话">
            <span className={css.brandIdentity}>
              <span className={css.brandMark}><BrandMark size={24} /></span>
              <span className={css.brandName}>DeepSeek Harness</span>
            </span>
          </button>
        )}
        <button
          type="button"
          className={`${css.iconButton} ${css.toggle}`}
          onClick={onToggle}
          aria-label={collapsed ? '展开侧边栏' : '收起侧边栏'}
          title={collapsed ? '展开侧边栏' : '收起侧边栏'}
        >
          <span className={css.panelIcon}><IconPanel /></span>
          {collapsed && <span className={css.railMark}><BrandMark size={22} /></span>}
        </button>
      </div>

      <button type="button" className={css.newSession} onClick={onNewSession} title="新会话">
        <IconPlus size={16} />
        {!collapsed && <span className={css.newSessionLabel}>新会话</span>}
      </button>

      <div className={css.regionArea}>
        {!collapsed && (
          <SessionList
            sessions={sessions}
            currentId={currentSessionId}
            onSelect={onSelectSession}
          />
        )}
      </div>

      <div className={css.footArea}>
        <div className={css.settingsArea}>
          <button
            type="button"
            className={css.iconButton}
            onClick={onToggleTheme}
            aria-label="切换主题"
            title="切换明暗主题"
          >
            {dark ? <IconSun /> : <IconMoon />}
          </button>
        </div>
        <div className={css.footerActions}>
          <button
            type="button"
            className={css.iconButton}
            onClick={onOpenSettings}
            aria-label="模型设置"
            title="模型设置"
          >
            <IconSettings />
          </button>
        </div>
      </div>

      {!collapsed && (
        <div style={{ padding: '8px 4px 4px', fontSize: 12, color: 'var(--dsw-alias-label-caption)' }}>
          {fmt(totalTokens)} tokens
        </div>
      )}
    </div>
  );
}

function SessionList({ sessions, currentId, onSelect }) {
  if (!sessions || sessions.length === 0) {
    return (
      <div style={{ padding: '12px 8px', color: 'var(--dsw-alias-label-tertiary)', fontSize: 13 }}>
        暂无历史会话
      </div>
    );
  }
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2, padding: '4px 4px 12px' }}>
      {sessions.map((s) => (
        <button
          key={s.sessionId}
          type="button"
          onClick={() => onSelect(s.sessionId)}
          style={{
            textAlign: 'left',
            padding: '8px 10px',
            borderRadius: 10,
            border: 'none',
            background: s.sessionId === currentId ? 'var(--dsw-specific-sidebar-nav-item-active)' : 'transparent',
            color: s.sessionId === currentId ? 'var(--dsw-alias-label-primary)' : 'var(--dsw-alias-label-secondary)',
            cursor: 'pointer',
            fontSize: 13,
            lineHeight: '20px',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
          onMouseEnter={(e) => { if (s.sessionId !== currentId) e.currentTarget.style.background = 'var(--dsw-specific-sidebar-nav-item-hover)'; }}
          onMouseLeave={(e) => { if (s.sessionId !== currentId) e.currentTarget.style.background = 'transparent'; }}
          title={s.title || s.sessionId}
        >
          {s.title || s.sessionId.slice(0, 12)}
        </button>
      ))}
    </div>
  );
}
