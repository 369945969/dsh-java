package com.deepseek.dsh.interaction.approval;

/**
 * 审批结果。
 */
public record ApprovalResult(
        /** 是否批准。 */
        boolean approved,
        /** 用户附带的反馈/拒绝原因。 */
        String feedback
) {
    public static ApprovalResult granted() {
        return new ApprovalResult(true, null);
    }

    public static ApprovalResult denied(String reason) {
        return new ApprovalResult(false, reason);
    }
}
