import { useEffect, useState } from 'react';
import { AppFrame } from './components/AppFrame';
import { Sidebar } from './components/Sidebar';
import { ConversationRoot } from './components/ConversationRoot';
import { ModelSettings } from './components/ModelSettings';
import { useChat } from './lib/useChat';

const THEME_KEY = 'dsh-theme';

function applyTheme(dark) {
  if (dark) document.body.dataset.dsDarkTheme = '';
  else delete document.body.dataset.dsDarkTheme;
}

export default function App() {
  const [dark, setDark] = useState(() => {
    const saved = localStorage.getItem(THEME_KEY);
    if (saved) return saved === 'dark';
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? true;
  });
  const [collapsed, setCollapsed] = useState(false);
  const [showSettings, setShowSettings] = useState(false);

  const chat = useChat();

  useEffect(() => { applyTheme(dark); localStorage.setItem(THEME_KEY, dark ? 'dark' : 'light'); }, [dark]);

  const footer = (
    <div style={{ width: '100%', maxWidth: 'var(--dsh-composer-card-max-width)', padding: '4px 8px', display: 'flex', justifyContent: 'space-between', fontSize: 12, color: 'var(--dsw-alias-label-caption)' }}>
      <span>DeepSeek Harness · Java 后端</span>
      <span>{chat.totalTokens.toLocaleString()} tokens</span>
    </div>
  );

  return (
    <AppFrame
      collapsed={collapsed}
      onToggleSidebar={() => setCollapsed((c) => !c)}
      sidebarChildren={
        <Sidebar
          collapsed={collapsed}
          onToggle={() => setCollapsed((c) => !c)}
          sessions={chat.sessions}
          currentSessionId={chat.sessionId}
          onSelectSession={chat.selectSession}
          onNewSession={chat.newSession}
          totalTokens={chat.totalTokens}
          onOpenSettings={() => setShowSettings(true)}
          dark={dark}
          onToggleTheme={() => setDark((d) => !d)}
        />
      }
    >
      <ConversationRoot
        sessionId={chat.sessionId}
        messages={chat.messages}
        running={chat.running}
        onSend={chat.send}
        onStop={chat.stop}
        footer={footer}
      />
      {showSettings && <ModelSettings onClose={() => setShowSettings(false)} />}
    </AppFrame>
  );
}
