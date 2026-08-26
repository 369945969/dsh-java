package com.deepseek.dsh.context.reference;

import java.nio.file.Path;

import com.deepseek.dsh.core.context.Service;

/**
 * 文件引用能力缝 —— 对应原 Harness 的 {@code file-reference}。
 *
 * <p>解析 @file 引用并返回文件内容作为模型上下文。
 */
public interface FileReferences extends Service {

    /** 解析一个文件引用，返回其内容文本。 */
    String resolve(Path basePath, String reference);
}
