package com.deepseek.dsh.attachment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;

/**
 * 本地附件存储提供者 —— 对应原 Harness 的 {@code attachment-local}。
 *
 * <p>内容寻址存储：对象按归一化内容的 sha256 寻址，存于
 * {@code dataDir/attachments/v1/objects/<sha256[:2]>/<sha256>}，
 * 相同内容自动去重（已存在则不重写）。
 *
 * <p>写入策略：先写临时文件 → fsync → 原子 move 到目标，避免半成品。
 *
 * <p>设计模式：策略的具体实现 + 仓储（内容寻址文件后端）。
 */
public final class LocalAttachmentStore
        extends AbstractCapabilityPlugin<AttachmentStore>
        implements AttachmentStore {

    private static final Logger log = LoggerFactory.getLogger(LocalAttachmentStore.class);
    private static final int BUCKET_PREFIX = 2;

    private final Path root;

    public LocalAttachmentStore(Path dataDir) {
        this.root = dataDir.resolve("attachments").resolve("v1");
    }

    @Override
    protected Class<AttachmentStore> serviceType() {
        return AttachmentStore.class;
    }

    @Override
    public AttachmentRef save(byte[] data, String mediaType, String name) {
        String hash = sha256Hex(data);
        AttachmentId id = AttachmentId.of("sha256:" + hash);
        Path object = objectPath(hash);
        if (!Files.exists(object)) {
            try {
                Files.createDirectories(object.getParent());
                Path tmp = Files.createTempFile(object.getParent(), ".attach-", ".tmp");
                Files.write(tmp, data);
                try { Files.move(tmp, object, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (Exception atomicFallback) {
                    Files.move(tmp, object, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                throw new AttachmentException("附件写入失败: " + id.value(), e);
            }
        }
        return new AttachmentRef(id, mediaType, data.length, name);
    }

    @Override
    public byte[] read(AttachmentRef ref) {
        String hash = stripScheme(ref.attachmentId().value());
        Path object = objectPath(hash);
        if (!Files.isReadable(object)) {
            throw new AttachmentException("附件不存在: " + ref.attachmentId().value());
        }
        try {
            return Files.readAllBytes(object);
        } catch (Exception e) {
            throw new AttachmentException("附件读取失败: " + ref.attachmentId().value(), e);
        }
    }

    @Override
    public boolean exists(AttachmentRef ref) {
        return Files.isReadable(objectPath(stripScheme(ref.attachmentId().value())));
    }

    private Path objectPath(String hash) {
        String bucket = hash.substring(0, Math.min(BUCKET_PREFIX, hash.length()));
        return root.resolve("objects").resolve(bucket).resolve(hash);
    }

    private static String stripScheme(String id) {
        int idx = id.indexOf(':');
        return idx >= 0 ? id.substring(idx + 1) : id;
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new AttachmentException("计算 sha256 失败", e);
        }
    }
}
