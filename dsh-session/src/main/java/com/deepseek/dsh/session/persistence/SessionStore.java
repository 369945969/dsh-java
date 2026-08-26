package com.deepseek.dsh.session.persistence;

import java.io.IOException;
import java.util.List;

import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.session.log.SessionEvent;

/**
 * 会话存储后端接口 —— 事件存储的可插拔抽象。
 *
 * <p>实现：{@link JsonlSessionStore}（JSONL 文件）、SQLite（见后续扩展）。
 *
 * <p>设计模式：策略（可互换后端）。
 */
public interface SessionStore {

    /** 追加一条事件到持久化介质。 */
    void append(SessionEvent event) throws IOException;

    /** 加载某会话的全部历史事件（按序号升序）。 */
    List<SessionEvent> load(SessionId sessionId) throws IOException;
}
