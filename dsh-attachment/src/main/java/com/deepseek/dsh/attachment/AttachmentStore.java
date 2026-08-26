package com.deepseek.dsh.attachment;

import com.deepseek.dsh.core.context.Service;

/**
 * 附件存储能力缝 —— 对应原 Harness 的 {@code ctx.attachments}。
 *
 * <p>内容寻址存储：按内容 sha256 寻址，相同内容去重。
 * 能力缝三角色：服务定义（本接口）、服务提供者（{@code LocalAttachmentStore}）、
 * 消费者（会话日志，把 ref 存入消息事件）。
 *
 * <p>设计模式：策略 + SPI + 仓储（内容寻址）。
 */
public interface AttachmentStore extends Service {

    /**
     * 保存一段字节内容（如图片），返回内容寻址引用。
     * 相同内容（同 sha256）去重，不重复存储。
     *
     * @param data      字节内容
     * @param mediaType MIME 类型
     * @param name      原始文件名（可空）
     * @return 附件引用
     */
    AttachmentRef save(byte[] data, String mediaType, String name);

    /**
     * 读取已存储附件的字节内容。
     *
     * @param ref 附件引用
     * @return 字节内容
     * @throws AttachmentNotFoundException 引用不存在时抛出
     */
    byte[] read(AttachmentRef ref);

    /** 引用对应的附件是否存在。 */
    boolean exists(AttachmentRef ref);
}
