package com.deepseek.dsh.spill;

/**
 * 已保存的外溢产物 —— 对应原 Harness 的 {@code SpillRef}。
 *
 * @param locator        后端产生的定位符（本地为文件路径；远程可为 URI/键）
 * @param bytes          完整内容的字节长度
 * @param retrievalHint  面向模型的检索指引（如「用 read offset/limit 或 grep 检索」）
 */
public record SpillRef(
        String locator,
        long bytes,
        String retrievalHint
) {
    /** 本地文件后端的标准检索指引。 */
    public static SpillRef ofPath(String path, long bytes) {
        return new SpillRef(path, bytes,
                "用 read（offset/limit）读取，或用 grep 在此路径内检索。");
    }
}
