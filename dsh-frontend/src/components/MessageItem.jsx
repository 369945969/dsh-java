import css from './MessageItem.module.css';
import amd from './AssistantMarkdown.module.css';
import { Markdown } from './Markdown';

/** 单条消息（对齐原 Harness MessageItem + AssistantMarkdown）：
 *  user → 右对齐气泡；assistant → 全宽 Markdown 叙述。 */
export function MessageItem({ message }) {
  if (message.role === 'user') {
    return (
      <div className={css.userRow}>
        <div className={css.userStack}>
          <div className={css.bubble}>{message.content}</div>
        </div>
      </div>
    );
  }
  if (message.role === 'tool') {
    return (
      <div className={css.toolRow}>
        <span className={css.toolName}>▸ {message.name || 'tool'}</span>
        <code className={css.toolArgs}>{message.arguments || ''}</code>
        {message.streaming && <span className={css.toolRunning}>…</span>}
      </div>
    );
  }
  if (message.role === 'tool_result') {
    return (
      <div className={css.toolResultRow}>
        <pre className={css.toolResult}>{(message.result || message.content || '').slice(0, 2000)}</pre>
      </div>
    );
  }
  // assistant
  const content = message.content || '';
  const reasoning = message.reasoning || '';
  return (
    <div className={amd.root}>
      <div className={amd.body}>
        {reasoning && (
          <details className={css.think} open>
            <summary>思维链</summary>
            <pre className={css.thinkBody}>{reasoning}</pre>
          </details>
        )}
        {content ? <Markdown>{content}</Markdown> : null}
        {message.streaming && <StreamingCursor />}
        {message.stopped && <span className={amd.stopped}>已停止</span>}
      </div>
    </div>
  );
}

function StreamingCursor() {
  return (
    <span
      style={{
        display: 'inline-block',
        width: 8,
        height: 18,
        marginLeft: 2,
        verticalAlign: 'text-bottom',
        background: 'var(--dsw-alias-state-business-primary)',
        borderRadius: 2,
        animation: 'dsh-blink 1s steps(2) infinite',
      }}
    />
  );
}
