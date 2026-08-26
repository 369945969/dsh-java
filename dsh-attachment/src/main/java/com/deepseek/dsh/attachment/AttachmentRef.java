package com.deepseek.dsh.attachment;

/**
 * 附件引用 —— 指向已存储附件的不可变描述。
 *
 * @param attachmentId 内容寻址 ID（{@code sha256:<hex>}）
 * @param mediaType    MIME 类型（如 image/png）
 * @param bytes        字节数
 * @param name         原始文件名（可空）
 */
public record AttachmentRef(
        AttachmentId attachmentId,
        String mediaType,
        long bytes,
        String name
) {
    public AttachmentRef {
        if (mediaType == null || mediaType.isBlank()) mediaType = "application/octet-stream";
        if (name == null) name = "";
    }
}
