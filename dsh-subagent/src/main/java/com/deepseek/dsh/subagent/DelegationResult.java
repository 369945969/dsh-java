package com.deepseek.dsh.subagent;

import com.deepseek.dsh.core.brand.ScopeKey;
import com.deepseek.dsh.core.brand.SessionId;
import com.deepseek.dsh.core.context.Context;

/**
 * subagent 委派结果。
 */
public record DelegationResult(
        /** 委派任务的摘要报告。 */
        String report,
        /** 是否成功完成。 */
        boolean success
) {}
