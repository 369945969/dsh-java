package com.deepseek.dsh.capability.code.python;

import com.deepseek.dsh.capability.code.CodeResult;
import com.deepseek.dsh.capability.code.CodeRuntime;
import com.deepseek.dsh.core.exception.CapabilityException;
import com.deepseek.dsh.core.process.ExecutionResult;
import com.deepseek.dsh.core.process.ProcessRunner;

/**
 * Python 代码运行时提供者 —— 对应原 Harness 的 {@code code-runtime-python}。
 *
 * <p><b>重构后</b>：进程执行逻辑委托给共用的 {@link ProcessRunner}，
 * 消除此前与 BashLocalProvider 逐字节重复的 drain/timeout 样板。
 * 不支持的语言以 {@link CapabilityException} 抛出（而非用错误码塞入返回值）。
 *
 * <p>设计模式：策略的具体实现（委托给 ProcessRunner）。
 */
public final class PythonCodeRuntime implements CodeRuntime {

    @Override
    public CodeResult execute(String language, String code, int timeoutSeconds) {
        if (!"python".equalsIgnoreCase(language) && !"python3".equalsIgnoreCase(language)) {
            throw new CapabilityException("code-runtime",
                    "不支持的代码语言: " + language, null);
        }
        ExecutionResult result = ProcessRunner.run(
                new String[]{"python3", "-c", code}, null, null, timeoutSeconds, "code-runtime");
        return toCodeResult(result);
    }

    /** {@link ExecutionResult} → {@link CodeResult}（保持外部 API 不变）。 */
    private static CodeResult toCodeResult(ExecutionResult r) {
        return new CodeResult(r.stdout(), r.stderr(), r.exitCode(), r.timedOut());
    }
}
