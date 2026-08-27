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
  // assistant
  const content = message.content || '';
  return (
    <div className={amd.root}>
      <div className={amd.body}>
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
