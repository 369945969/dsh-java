import { useCallback, useRef } from 'react';
import css from './ConversationRoot.module.css';
import { HeroShell, HeroGlow } from './HeroShell';
import { ChatView } from './ChatView';
import { MessageItem } from './MessageItem';
import { InputBar } from './InputBar';

/** 对话列骨架（对齐原 Harness ConversationRoot）：header + scrollBody + composer seat。
 *  hero 阶段（无消息）居中欢迎页 + 输入框；active 阶段滚动消息流 + 底部粘性输入框。 */
export function ConversationRoot({ sessionId, messages, running, onSend, onStop, footer }) {
  const hero = messages.length === 0 && !running;
  const phase = hero ? 'hero' : 'active';
  const seatRef = useRef(null);

  const composerStack = (
    <div className={`${css.composerStack} ${hero ? css.composerHero : ''}`}>
      {hero && <HeroGlow className={css.heroGlow} />}
      {hero && <HeroShell />}
      <InputBar
        variant={hero ? 'hero' : 'composer'}
        running={running}
        onSend={onSend}
        onStop={onStop}
        footer={!hero ? footer : null}
      />
    </div>
  );

  return (
    <div className={css.root} data-phase={phase}>
      <div className={css.header}>
        <div className={css.titleRow}>
          <div className={css.titleCluster}>
            <span className={css.crumbs}>
              <span className={css.crumbSeg}>
                <span className={css.crumb + ' ' + css.crumbCurrent}>
                  {sessionId ? `会话 ${sessionId.slice(0, 8)}` : '新会话'}
                </span>
              </span>
            </span>
          </div>
        </div>
      </div>
      <div className={css.scrollBody} data-conversation-scroll="">
        {hero ? (
          <div className={css.composerSeat} ref={seatRef} data-composer-seat>
            {composerStack}
          </div>
        ) : (
          <>
            <ChatView messages={messages} running={running}>
              {(m) => <MessageItem message={m} />}
            </ChatView>
            <div className={css.composerSeat} ref={seatRef} data-composer-seat>
              {composerStack}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
