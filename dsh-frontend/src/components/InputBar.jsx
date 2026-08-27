import { useEffect, useRef, useState } from 'react';
import css from './InputBar.module.css';
import { IconPlus, IconSend, IconStop } from './Icons';

/** 输入框（对齐原 Harness InputBar 的卡片式 composer）：mirror 自动增高 + 发送/停止。
 *  简化：去除引用 chip / 附件 / 命令菜单 / 权限选择，保留核心自动增高与主操作。 */
export function InputBar({ variant = 'composer', running = false, onSend, onStop, placeholder, disabled = false, rightItems = null, footer = null }) {
  const [draft, setDraft] = useState('');
  const inputRef = useRef(null);
  const empty = draft.trim() === '';
  const primaryStops = running;

  useEffect(() => {
    const el = inputRef.current;
    if (!el) return;
    el.style.setProperty('--dsh-caret', '0');
  }, []);

  const submit = () => {
    if (empty || disabled || running) return;
    onSend?.(draft);
    setDraft('');
  };

  const onKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      // IME 合成中不发送
      if (e.nativeEvent.isComposing || e.nativeEvent.keyCode === 229) return;
      e.preventDefault();
      if (e.repeat) return;
      submit();
    }
  };

  return (
    <div className={`${css.root} ${variant === 'hero' ? css.hero : ''}`}>
      <div className={css.card}>
        <div className={css.scroll} data-input-scroll>
          <div className={css.grow}>
            <div aria-hidden className={css.mirror} data-input-mirror>{`${draft}\n`}</div>
            <textarea
              ref={inputRef}
              className={css.input}
              value={draft}
              disabled={disabled}
              placeholder={placeholder ?? (variant === 'hero' ? '输入需求，开始一段对话…' : '输入消息，Enter 发送 · Shift+Enter 换行')}
              rows={2}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={onKeyDown}
            />
          </div>
        </div>
        <div className={css.row}>
          <div className={css.tools}>
            <button
              type="button"
              className={css.add}
              aria-label="附加"
              disabled
              title="附件（暂未启用）"
            >
              <IconPlus size={14} />
            </button>
            <div className={css.modes} />
            {null}
          </div>
          <div className={css.trailing}>
            {rightItems}
            <button
              type="button"
              className={css.primary}
              aria-label={primaryStops ? '停止' : '发送'}
              disabled={primaryStops ? !onStop : (empty || disabled)}
              onClick={primaryStops ? onStop : submit}
            >
              {primaryStops ? <IconStop /> : <IconSend />}
            </button>
          </div>
        </div>
      </div>
      {footer}
    </div>
  );
}
