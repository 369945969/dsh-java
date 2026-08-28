import { useEffect, useRef, useState } from 'react';
import css from './ChatView.module.css';

/** 对话消息流（对齐原 Harness ChatView）：居中消息列，回到底部按钮。 */
export function ChatView({ messages, running, children }) {
  const scrollRef = useRef(null);
  const [showToBottom, setShowToBottom] = useState(false);

  const atBottom = (el) => el.scrollHeight - el.scrollTop - el.clientHeight < 80;

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (atBottom(el)) el.scrollTop = el.scrollHeight;
  }, [messages]);

  const onScroll = () => {
    const el = scrollRef.current;
    if (!el) return;
    setShowToBottom(!atBottom(el) && el.scrollHeight > el.clientHeight + 200);
  };

  const toBottom = () => {
    const el = scrollRef.current;
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  };

  return (
    <div className={css.root} data-conversation-view="">
      <div ref={scrollRef} className={css.scroll} onScroll={onScroll}>
        <div className={css.column}>
          {messages.map((m) => (
            <div className={css.flowItem} key={m.id}>
              {children(m)}
            </div>
          ))}
          {running && messages.every((m) => !m.streaming) && (
            <div className={css.flowItem}>
              <span className={css.turnStatus}>思考中…</span>
            </div>
          )}
        </div>
        {showToBottom && (
          <div className={css.toBottomSlot}>
            <button type="button" className={css.toBottom} onClick={toBottom} aria-label="回到底部">
              <ChevronDown />
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

function ChevronDown() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden>
      <path d="M4 6l4 4 4-4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
