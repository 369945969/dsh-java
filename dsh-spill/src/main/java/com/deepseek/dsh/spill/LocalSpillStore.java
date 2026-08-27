package com.deepseek.dsh.spill;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.deepseek.dsh.core.context.AbstractCapabilityPlugin;
import com.deepseek.dsh.core.exception.CapabilityException;

/**
 * 本地文件系统外溢后端 —— 对应原 Harness 的 {@code LocalSpillStore}。
 *
 * <p>把超大文本结果持久化到私有（0700）、会话作用域的文件
 * （{@code <root>/session-<hash>/<消毒名>}），返回路径定位符与本地检索指引。
 *
 * <p><b>安全</b>：私有根 + 独占属主可写（0600），杜绝其他本地用户读取，
 * 也防止符号链接劫持（写入前不跟随既有链接）。名称由调用方建议名消毒为
 * 单个安全路径段，绝不等于调用方输入，避免目录穿越。
 *
 * <p>设计模式：策略的具体实现 + 模板方法（消毒/落盘骨架抽出）。
 */
public final class LocalSpillStore
        extends AbstractCapabilityPlugin<SpillStore>
        implements SpillStore {

    private static final Logger log = LoggerFactory.getLogger(LocalSpillStore.class);
    private static final Set<PosixFilePermission> DIR_PERMS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path root;

    /** 自定义根目录。 */
    public LocalSpillStore(Path root) {
        this.root = root;
        ensurePrivateRoot();
    }

    /** 默认使用系统临时目录下的私有每进程根。 */
    public LocalSpillStore() {
        this(defaultPrivateRoot());
    }

    private static Path defaultPrivateRoot() {
        try {
            return Files.createTempDirectory("dsh-spill-",
                    PosixFilePermissions.asFileAttribute(DIR_PERMS));
        } catch (Exception e) {
            // 非 POSIX 或权限设置失败时退化为普通临时目录
            try {
                return Files.createTempDirectory("dsh-spill-");
            } catch (Exception ex) {
                throw new CapabilityException("spill", "Cannot create private root directory", ex);
            }
        }
    }

    private void ensurePrivateRoot() {
        try {
            Files.createDirectories(root);
            Files.setPosixFilePermissions(root, DIR_PERMS);
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统（如 Windows）跳过权限
        } catch (Exception e) {
            log.warn("Failed to set spill root directory permissions: {}", e.toString());
        }
    }

    @Override
    protected Class<SpillStore> serviceType() {
        return SpillStore.class;
    }

    @Override
    public CompletableFuture<SpillRef> saveText(SaveTextSpill input) {
        return CompletableFuture.supplyAsync(() -> {
            Path sessionDir = root.resolve("session-" + sessionHash(input.ownerSessionId()));
            Path target = sessionDir.resolve(sanitizeName(input.suggestedName()));
            try {
                Files.createDirectories(sessionDir);
                byte[] bytes = input.content().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                // 不跟随既有链接，防符号链接劫持
                Files.write(target, bytes,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                        java.nio.file.StandardOpenOption.WRITE,
                        java.nio.file.StandardOpenOption.SYNC);
                try {
                    Files.setPosixFilePermissions(target, FILE_PERMS);
                } catch (UnsupportedOperationException ignored) {
                    // 非 POSIX 跳过
                }
                return SpillRef.ofPath(target.toAbsolutePath().toString(), bytes.length);
            } catch (Exception e) {
                throw new CapabilityException("spill",
                        "保存外溢产物失败: " + target, e);
            }
        });
    }

    /** 会话 ID 的短哈希，作为目录段，避免原始 ID 出现在路径中。 */
    private static String sessionHash(com.deepseek.dsh.core.brand.SessionId sessionId) {
        int h = sessionId == null ? 0 : sessionId.value().hashCode();
        return Integer.toHexString(h < 0 ? -h : h);
    }

    /**
     * 把调用方建议名消毒为单个安全路径段：保留字母数字与连字符/下划线/点，
     * 其余替换为连字符，杜绝路径分隔符，确保不会穿越目录。
     */
    static String sanitizeName(String suggested) {
        String base = suggested == null || suggested.isBlank() ? "spill.txt" : suggested.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length() && sb.length() < 64; i++) {
            char c = base.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '\0') {
                sb.append('-');
            } else if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('-');
            }
        }
        if (sb.isEmpty()) sb.append("spill");
        if (!sb.toString().contains(".")) sb.append(".txt");
        return sb.toString();
    }
}
