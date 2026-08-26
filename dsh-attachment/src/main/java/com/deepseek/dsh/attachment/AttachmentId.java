package com.deepseek.dsh.attachment;

import com.deepseek.dsh.core.brand.Branded;

/**
 * 附件 ID —— 内容寻址标识（{@code sha256:<hex>}），品牌化以防与裸 String 混淆。
 * 对应原 Harness 的 {@code AttachmentId}。
 */
public final class AttachmentId extends Branded<String, AttachmentId.Tag> {
    private AttachmentId(String value) { super(value); }

    public static AttachmentId of(String raw) { return new AttachmentId(raw); }

    /** 幻影标签标记。 */
    public static final class Tag {}
}
