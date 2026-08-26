package com.deepseek.dsh.interaction.approval;

/**
 * 审批请求 —— 一次性的人类确认提示。
 */
public record ApprovalRequest(
        /** 请求标题/摘要。 */
        String title,
        /** 详细描述（要执行的操作）。 */
        String description,
        /** 风险等级。 */
        Risk risk
) {
    public enum Risk { LOW, MEDIUM, HIGH }

    public static ApprovalRequest of(String title, String description) {
        return new ApprovalRequest(title, description, Risk.MEDIUM);
    }
}
