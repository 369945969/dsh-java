package com.deepseek.dsh.core.context;

/**
 * 会话级工作目录（cwd）—— 由 Web 层在 agent 回合开始前设置（取会话所属工作区的目录路径），
 * 供 bash 等工具在未显式指定 workdir 时作为默认工作目录。
 *
 * <p>使用 ThreadLocal：agent 回合在同一线程（虚拟线程）内执行，工具在该线程读取。
 *
 * <p>设计模式：线程局部单例（隐式上下文传递）。
 */
public final class SessionCwd {
    private static final ThreadLocal<String> CWD = new ThreadLocal<>();

    public static void set(String cwd) { CWD.set(cwd); }
    public static String get() { return CWD.get(); }
    public static void clear() { CWD.remove(); }

    private SessionCwd() {}
}
