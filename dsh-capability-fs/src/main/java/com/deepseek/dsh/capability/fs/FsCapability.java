package com.deepseek.dsh.capability.fs;

import java.nio.file.Path;
import java.util.List;

/**
 * 文件系统能力缝 —— 对应原 Harness 的 {@code ctx.fs}。
 *
 * <p>能力缝三角色：
 * <ul>
 *   <li><b>服务定义</b>：本接口。</li>
 *   <li><b>服务提供者</b>：{@code FsLocalProvider}（本地）/ {@code FsSandboxProvider}（沙箱）。</li>
 *   <li><b>消费者</b>：{@code read} / {@code write} / {@code edit} / {@code glob} / {@code grep} 工具。</li>
 * </ul>
 *
 * <p>切换提供者即可将文件访问指向远程沙箱。
 *
 * <p>设计模式：策略 + SPI。
 */
public interface FsCapability {

    /** 读取文件全文（受行数限制）。 */
    String read(Path path, int offset, int limit) throws Exception;

    /** 写入文件（覆盖）。 */
    void write(Path path, String content) throws Exception;

    /** 精确字符串替换编辑（对应原 Harness 的 str-replace-editor）。 */
    String edit(Path path, String oldString, String newString) throws Exception;

    /** 按 glob 模式列出匹配的文件路径。 */
    List<String> glob(String pattern, Path baseDir) throws Exception;

    /** 按正则在文件内容中搜索。 */
    List<String> grep(String pattern, Path baseDir, String include) throws Exception;
}
